package com.dsa.mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.9.0 大盘分析数据层
 * 数据源策略参考 GitHub PR#36（东财优先 + 智能限流 + 三级降级）：
 *  - 指数实时行情：腾讯 qt.gtimg.cn（sh000001 / sz399001 / sh000300）
 *  - 指数日K：东财 push2his（限流防封，失败自动跳过）→ 腾讯 fqkline → 新浪 K线
 *  - 行业板块强弱：东财 push2 clist（fs=m:90+t:2）
 */
object MarketApi {

    data class Sector(
        val name: String,
        val changePct: Double,
        val upCount: Int,
        val downCount: Int
    )

    data class MarketData(
        val indexName: String,
        val indexPrice: Double,
        val indexChangePct: Double,
        val ma20: Double,
        val ma60: Double,
        val lightBelowMa20: Boolean,
        val lightWeak60: Boolean,
        val lightBelowMa60: Boolean,
        val lightsOn: Int,
        val strategy: String,
        val tactic: String,
        val practice: String,
        val risk: String,
        val warnLine1: String,
        val daoLine: String,
        val upSectors: List<Sector>,
        val downSectors: List<Sector>,
        val totalSectors: Int,
        val elapsedMs: Long,
        val updateTime: String
    )

    // ---- 东财限流防封（PR#36 em_get 精简版）----
    @Volatile private var emBanned = false
    private var emFails = 0
    private var lastEmTs = 0L
    private val emLock = Any()

    /** 东财统一请求：间隔≥900ms + 连续3次失败标记封禁后自动跳过 */
    private fun emGet(url: String): String? {
        if (emBanned) return null
        synchronized(emLock) {
            val now = System.currentTimeMillis()
            val wait = 900L - (now - lastEmTs)
            if (wait > 0) Thread.sleep(wait)
            lastEmTs = System.currentTimeMillis()
        }
        return try {
            val r = Http.get(url, mapOf("Referer" to "https://quote.eastmoney.com/"))
            emFails = 0
            r
        } catch (e: Exception) {
            emFails++
            if (emFails >= 3) {
                emBanned = true
            }
            null
        }
    }

    private fun fmt(v: Double): String = String.format("%.2f", v)

    /** 主入口：拉取全部大盘数据（后台线程调用） */
    fun load(): MarketData {
        val t0 = System.currentTimeMillis()

        // 1) 三指数实时行情（腾讯）
        val quotes = fetchIndexQuotes()
        val sh = quotes["上证指数"] ?: QuotePoint("上证指数", 0.0, 0.0, 0.0)

        // 2) 上证指数日K（三级降级）
        val closes = fetchIndexCloses()
        val ma20 = ma(closes, 20)
        val ma60 = ma(closes, 60)

        // 3) 三盏灯（123法则趋势反转）
        val price = if (sh.price > 0) sh.price else closes.lastOrNull() ?: 0.0
        val light1 = ma20 > 0 && price < ma20
        val light3 = ma60 > 0 && price < ma60
        val light2 = weak60(closes)
        val lightsOn = listOf(light1, light2, light3).count { it }

        // 4) 动态文案
        val (strategy, tactic, practice, risk, warn1) = when (lightsOn) {
            3 -> Quintuple(
                "战略：趋势向下·空头结构", "战术：空仓观望",
                "实战：仓位 ≤10%", "风控：空仓等待，不抢反弹",
                "上证 ${fmt(price)} 跌破 MA20 ${fmt(ma20)} / MA60 ${fmt(ma60)} —— 趋势转弱，停止一切新开仓"
            )
            2 -> Quintuple(
                "战略：趋势走弱·背离预警", "战术：减仓观望",
                "实战：仓位 ≤30%", "风控：不追高，破位即止损",
                "上证 ${fmt(price)} 逼近 MA20 ${fmt(ma20)} / MA60 ${fmt(ma60)} —— 趋势转弱，控制仓位"
            )
            1 -> Quintuple(
                "战略：趋势震荡·方向不明", "战术：轻仓试探",
                "实战：仓位 ≤50%", "风控：设好止损，快进快出",
                "上证 ${fmt(price)} 位于 MA20 ${fmt(ma20)} / MA60 ${fmt(ma60)} 附近 —— 震荡市，勿追涨杀跌"
            )
            else -> Quintuple(
                "战略：趋势向上·多头结构", "战术：持股待涨",
                "实战：仓位 ≥70%", "风控：跌破 MA20 减仓",
                "上证 ${fmt(price)} 站上 MA20 ${fmt(ma20)} / MA60 ${fmt(ma60)} —— 趋势走强，顺势持仓"
            )
        }

        // 5) 道氏相互验证（三指数当前涨跌幅统计）
        var daoUp = 0; var daoDown = 0; var daoShake = 0
        val weakNames = mutableListOf<String>()
        quotes.values.forEach { q ->
            when {
                q.changePct > 0.1 -> daoUp++
                q.changePct < -0.1 -> { daoDown++; weakNames += q.name }
                else -> daoShake++
            }
        }
        val daoLine = if (daoDown > 0) {
            "道氏相互验证：⚠ 指数背离（升$daoUp·降$daoDown·震$daoShake）—— ${weakNames.joinToString("、")} 走弱 → 宜降低仓位"
        } else {
            "道氏相互验证：✓ 指数共振（升$daoUp·降$daoDown·震$daoShake）—— ${quotes.keys.joinToString("、")} 走强 → 可顺势持仓"
        }

        // 6) 行业板块强弱（东财，限流；失败降级为空）
        val (up, down, total) = fetchSectors()

        val now = java.util.Date()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

        return MarketData(
            indexName = sh.name,
            indexPrice = price,
            indexChangePct = sh.changePct,
            ma20 = ma20, ma60 = ma60,
            lightBelowMa20 = light1, lightWeak60 = light2, lightBelowMa60 = light3,
            lightsOn = lightsOn,
            strategy = strategy, tactic = tactic, practice = practice, risk = risk,
            warnLine1 = warn1, daoLine = daoLine,
            upSectors = up, downSectors = down, totalSectors = total,
            elapsedMs = System.currentTimeMillis() - t0,
            updateTime = sdf.format(now)
        )
    }

    private data class Quintuple(
        val a: String, val b: String, val c: String, val d: String, val e: String
    )

    private data class QuotePoint(val name: String, val price: Double, val prevClose: Double, val changePct: Double)

    /** 腾讯三指数实时行情 */
    private fun fetchIndexQuotes(): Map<String, QuotePoint> {
        val syms = listOf("sh000001" to "上证指数", "sz399001" to "深证成指", "sh000300" to "沪深300")
        val raw = try {
            Http.get("https://qt.gtimg.cn/q=${syms.joinToString(",") { it.first }}")
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, QuotePoint>()
        Regex("v_(\\w+)=\"([^\"]*)\"").findAll(raw).forEach { m ->
            val sym = m.groupValues[1]
            val parts = m.groupValues[2].split("~")
            if (parts.size < 33) return@forEach
            val name = parts[1]
            val price = parts[3].toDoubleOrNull() ?: 0.0
            val prev = parts[4].toDoubleOrNull() ?: 0.0
            val pct = parts[32].toDoubleOrNull() ?: 0.0
            out[name] = QuotePoint(name, price, prev, pct)
        }
        return out
    }

    /** 上证指数收盘价序列：东财 push2his → 腾讯 fqkline → 新浪 K线 */
    private fun fetchIndexCloses(): List<Double> {
        // ① 东财 push2his（限流）
        if (!emBanned) {
            try {
                val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
                    "?secid=1.000001&fields1=f1,f2,f3&fields2=f51,f53&klt=101&fqt=0&end=20500101&lmt=90"
                val raw = emGet(url)
                if (raw != null) {
                    val arr = JSONObject(raw).optJSONObject("data")?.optJSONArray("klines")
                    if (arr != null && arr.length() >= 60) {
                        val list = ArrayList<Double>()
                        for (i in 0 until arr.length()) {
                            arr.optString(i).split(",").getOrNull(1)?.toDoubleOrNull()?.let { list += it }
                        }
                        if (list.size >= 60) return list
                    }
                }
            } catch (_: Exception) {}
        }
        // ② 腾讯 fqkline
        try {
            val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sh000001,day,,90,qfq"
            val json = JSONObject(Http.get(url))
            val node = json.optJSONObject("data")?.optJSONObject("sh000001")
            val arr: JSONArray? = node?.optJSONArray("day") ?: node?.optJSONArray("qfqday")
            if (arr != null && arr.length() >= 60) {
                val list = ArrayList<Double>()
                for (i in 0 until arr.length()) {
                    arr.optJSONArray(i)?.optString(2)?.toDoubleOrNull()?.let { list += it }
                }
                if (list.size >= 60) return list
            }
        } catch (_: Exception) {}
        // ③ 新浪 K线（末位降级）
        try {
            val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData" +
                "?symbol=sh000001&scale=240&datalen=90&ma=no"
            val arr = JSONArray(Http.get(url))
            if (arr.length() >= 60) {
                val list = ArrayList<Double>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("close")?.toDoubleOrNull()?.let { list += it }
                }
                if (list.size >= 60) return list
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    /** 东财行业板块强弱排行（领涨5/领跌5） */
    private fun fetchSectors(): Triple<List<Sector>, List<Sector>, Int> {
        if (emBanned) return Triple(emptyList(), emptyList(), 0)
        fun page(po: Int): Pair<List<Sector>, Int> {
            val url = "https://push2.eastmoney.com/api/qt/clist/get" +
                "?pn=1&pz=5&po=$po&np=1&fltt=2&invt=2&fid=f3" +
                "&fs=m:90+t:2&fields=f12,f14,f3,f104,f105"
            val raw = emGet(url) ?: return Pair(emptyList(), 0)
            val data = JSONObject(raw).optJSONObject("data") ?: return Pair(emptyList(), 0)
            val total = data.optInt("total", 0)
            val arr = data.optJSONArray("diff") ?: return Pair(emptyList(), total)
            val list = ArrayList<Sector>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list += Sector(
                    name = o.optString("f14", ""),
                    changePct = o.optDouble("f3", 0.0),
                    upCount = o.optInt("f104", 0),
                    downCount = o.optInt("f105", 0)
                )
            }
            return Pair(list, total)
        }
        val up = page(1)
        val down = page(0)
        return Triple(up.first, down.first, maxOf(up.second, down.second))
    }

    private fun ma(closes: List<Double>, n: Int): Double {
        if (closes.size < n) return 0.0
        return closes.takeLast(n).average()
    }

    /** 60日转弱：近20日重心低于前20日，且 MA5 下穿 MA20 */
    private fun weak60(closes: List<Double>): Boolean {
        if (closes.size < 40) return false
        val recent20 = closes.takeLast(20).average()
        val prev20 = closes.takeLast(40).dropLast(20).average()
        val ma5 = closes.takeLast(5).average()
        val ma20 = ma(closes, 20)
        return recent20 < prev20 && ma5 < ma20
    }
}
