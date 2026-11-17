package com.dsa.mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * TickFlow 行情数据 API（https://docs.tickflow.org/zh-Hans）
 * 认证：x-api-key 请求头
 * 选股数据链路（v2.2.0 新增接入）：
 *   1. GET /v1/quotes?universes=CN_Equity_A —— 全市场 A 股实时行情（一次请求）
 *   2. POST /v1/instruments 分批 —— 总股本 → 总市值
 *   3. POST /v1/financials/metrics 分批 —— EPS/BPS → PE/PB
 *   4. GET /v1/klines/batch 候选补算 —— 5日均量 → 量比；60日涨跌幅（动量）
 * 字段口径：TickFlow change_pct/turnover_rate 为小数（0.01=1%），此处转百分比
 */
object TickFlowApi {

    private const val BASE = "https://api.tickflow.org/v1"
    private const val BATCH = 50
    private const val MAX_CANDIDATES = 600

    /** 内部载体：stock + TickFlow symbol（如 600000.SH） */
    private data class TFItem(val symbol: String, var stock: ScreenStock)

    /**
     * 全市场选股快照（实时行情 + 市值 + PE/PB）
     * volumeRatio 占位 1.0（候选阶段用 klines 补算真实量比）、change60d 占位 0.0
     */
    fun fetchAllStocks(key: String, onStep: (done: Int, total: Int) -> Unit = { _, _ -> }): List<ScreenStock> {
        // 1) 全市场实时行情
        onStep(1, 4)
        val quotes = fetchQuotesAll(key)
        var items = quotes.map { TFItem(it.symbol, it.stock) }

        // 2) 股本 → 总市值
        items = enrichWithShares(key, items)
        onStep(2, 4)

        // 3) 财务指标 → PE/PB
        items = enrichWithFundamentals(key, items)
        onStep(3, 4)

        return items.map { it.stock }
    }

    /**
     * 对候选列表补算真实量比（今日量/过去5日均量）与 60 日涨跌幅（动量）
     * 用于 TickFlow 源下粗筛后的候选增强，避免全市场逐只拉 K 线
     */
    fun enrichMomentum(key: String, candidates: List<ScreenStock>, onBatch: (done: Int, total: Int) -> Unit = { _, _ -> }): List<ScreenStock> {
        if (candidates.isEmpty()) return candidates
        val out = candidates.toMutableList()
        val batches = candidates.chunked(BATCH)
        for ((bi, batch) in batches.withIndex()) {
            val symbols = batch.joinToString(",") { tickSymbol(it.code) }
            try {
                val url = "$BASE/klines/batch?symbols=$symbols&period=1d&count=65"
                val resp = retry429 { Http.get(url, mapOf("x-api-key" to key)) }
                val data = JSONObject(resp).optJSONObject("data") ?: JSONObject()
                val keys = data.keys()
                while (keys.hasNext()) {
                    val sym = keys.next()
                    val v = data.optJSONObject(sym) ?: continue
                    val idx = out.indexOfFirst { tickSymbol(it.code) == sym }
                    if (idx < 0) continue
                    val st = out[idx]
                    val closes = v.optJSONArray("close")
                    val volumes = v.optJSONArray("volume")
                    if (closes == null || volumes == null) continue
                    var vr = 1.0
                    var c60 = 0.0
                    val n = volumes.length()
                    if (n >= 6) {
                        var sum = 0.0
                        for (i in 1 until 6) sum += volumes.optDouble(n - 1 - i, 0.0)
                        val avg = sum / 5.0
                        val last = volumes.optDouble(n - 1, 0.0)
                        if (avg > 0) vr = (last / avg).coerceIn(0.0, 30.0)
                    }
                    val cn = closes.length()
                    if (cn >= 2) {
                        val first = closes.optDouble(0, 0.0)
                        val last = closes.optDouble(cn - 1, 0.0)
                        if (first > 0) c60 = ((last / first - 1.0) * 100.0).coerceIn(-100.0, 500.0)
                    }
                    out[idx] = st.copy(volumeRatio = vr, change60d = c60)
                }
            } catch (e: Exception) {
                // 单批失败跳过（量比/动量缺失时用占位值，不影响整体流程）
            }
            onBatch(bi + 1, batches.size)
        }
        return out
    }

    // ---------- 内部实现 ----------

    /** 全市场实时行情（一次请求） */
    private fun fetchQuotesAll(key: String): List<TFItem> {
        val url = "$BASE/quotes?universes=CN_Equity_A"
        val resp = retry429 { Http.get(url, mapOf("x-api-key" to key)) }
        val data = JSONObject(resp).optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<TFItem>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val symbol = o.optString("symbol", "")
            val name = o.optJSONObject("ext")?.optString("name", "") ?: ""
            val price = o.optDouble("last_price", 0.0)
            val amount = o.optDouble("amount", 0.0)
            val ext = o.optJSONObject("ext")
            val changePct = (ext?.optDouble("change_pct", 0.0) ?: 0.0) * 100.0
            val turnover = (ext?.optDouble("turnover_rate", 0.0) ?: 0.0) * 100.0
            if (symbol.isBlank() || price <= 0) continue
            out.add(
                TFItem(
                    symbol,
                    ScreenStock(
                        code = symbol.substringBefore('.'),
                        name = name.ifBlank { symbol.substringBefore('.') },
                        price = price,
                        changePct = changePct,
                        volumeRatio = 1.0,   // 占位，候选阶段补算
                        turnoverRate = turnover,
                        pe = 0.0,            // 占位，metrics 补
                        pb = 0.0,            // 占位，metrics 补
                        totalMv = 0.0,       // 占位，instruments 补
                        amount = amount,
                        change60d = 0.0      // 占位，候选阶段补算
                    )
                )
            )
        }
        return out
    }

    /** 股本 → 总市值（分批） */
    private fun enrichWithShares(key: String, items: List<TFItem>): List<TFItem> {
        if (items.isEmpty()) return items
        val out = items.toMutableList()
        val batches = items.chunked(BATCH)
        for (batch in batches) {
            val symbols = batch.map { it.symbol }
            try {
                val body = JSONObject().put("symbols", JSONArray(symbols)).toString()
                val resp = retry429 { Http.postJson("$BASE/instruments", body, mapOf("x-api-key" to key)) }
                val data = JSONObject(resp).optJSONArray("data") ?: continue
                val shares = HashMap<String, Double>()
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val sym = o.optString("symbol", "")
                    val ts = o.optJSONObject("ext")?.optDouble("total_shares", 0.0) ?: 0.0
                    if (ts > 0) shares[sym] = ts
                }
                for (item in out) {
                    val ts = shares[item.symbol] ?: 0.0
                    if (ts > 0) {
                        val mv = item.stock.price * ts / 1e8
                        item.stock = item.stock.copy(totalMv = mv)
                    }
                }
            } catch (e: Exception) {
                // 单批失败跳过（市值缺失时后续过滤自然剔除）
            }
        }
        return out
    }

    /** 财务指标 → PE/PB（分批；PE=现价/EPS，PB=现价/BPS） */
    private fun enrichWithFundamentals(key: String, items: List<TFItem>): List<TFItem> {
        if (items.isEmpty()) return items
        val out = items.toMutableList()
        val batches = items.chunked(BATCH)
        for (batch in batches) {
            val symbols = batch.map { it.symbol }
            try {
                val body = JSONObject().put("symbols", JSONArray(symbols)).toString()
                val resp = retry429 { Http.postJson("$BASE/financials/metrics", body, mapOf("x-api-key" to key)) }
                val data = JSONObject(resp).optJSONObject("data") ?: continue
                for (item in out) {
                    val recs = data.optJSONArray(item.symbol) ?: continue
                    if (recs.length() == 0) continue
                    val latest = recs.optJSONObject(recs.length() - 1) ?: continue
                    val eps = latest.optDouble("eps_basic", 0.0)
                    val bps = latest.optDouble("bps", 0.0)
                    val st = item.stock
                    var pe = st.pe
                    var pb = st.pb
                    if (eps > 0) pe = (st.price / eps).coerceIn(0.0, 5000.0)
                    if (bps > 0) pb = (st.price / bps).coerceIn(0.0, 5000.0)
                    item.stock = st.copy(pe = pe, pb = pb)
                }
            } catch (e: Exception) {
                // 单批失败跳过
            }
        }
        return out
    }

    /** 6 位代码 → TickFlow symbol（600000→600000.SH，000001→000001.SZ，830xxx/920xxx→.BJ） */
    fun tickSymbol(code: String): String {
        val c = code.trim()
        return when {
            c.startsWith("920") || c.startsWith("8") || c.startsWith("4") -> "$c.BJ"
            c.startsWith("6") || c.startsWith("9") || c.startsWith("5") -> "$c.SH"
            else -> "$c.SZ"
        }
    }

    /** HTTP 429 限流等待重试（最多 3 次，间隔 5s） */
    private inline fun <T> retry429(block: () -> T): T {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (e.message?.contains("HTTP 429") == true && attempt < 2) {
                    Thread.sleep(5000L)
                } else throw e
            }
        }
        throw last ?: RuntimeException("请求失败")
    }
}
