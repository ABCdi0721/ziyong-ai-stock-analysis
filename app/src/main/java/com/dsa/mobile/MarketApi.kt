package com.dsa.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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

    /** 大盘页横滑指数卡片行情 */
    data class IndexQuote(
        val name: String,
        val price: Double,
        val change: Double,
        val changePct: Double
    )

    /** 涨跌分布单档：kind 1 涨 / 0 平 / -1 跌 */
    data class DistBucket(
        val label: String,
        val count: Int,
        val kind: Int
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
        val indices: List<IndexQuote>,
        val breadthUp: Int,
        val breadthDown: Int,
        val breadthFlat: Int,
        val sectorSource: String,
        val distribution: List<DistBucket>?,
        val medianPct: Double,
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

    // ---- 财联社限流防封（与东财同一套策略：间隔≥900ms + 连续3次失败标记封禁）----
    @Volatile private var clsBanned = false
    private var clsFails = 0
    private var lastClsTs = 0L
    private val clsLock = Any()

    private fun hexLower(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** 财联社签名：参数排序拼串 → SHA-1 → MD5（小写 hex） */
    private fun clsSign(params: Map<String, String>): String {
        val raw = params.keys.sorted().joinToString("&") { "$it=${params[it]}" }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
            .let { hexLower(it) }
        return MessageDigest.getInstance("MD5").digest(sha1.toByteArray(Charsets.UTF_8))
            .let { hexLower(it) }
    }

    /** 财联社统一 GET：补公共参数、带 sign；业务码非 200 或异常记失败，连续 3 次后跳过 */
    private fun clsGet(path: String, extra: Map<String, String> = emptyMap()): JSONObject? {
        if (clsBanned) return null
        synchronized(clsLock) {
            val now = System.currentTimeMillis()
            val wait = 900L - (now - lastClsTs)
            if (wait > 0) Thread.sleep(wait)
            lastClsTs = System.currentTimeMillis()
        }
        return try {
            val params = mutableMapOf("os" to "web", "sv" to "8.7.9", "app" to "CailianpressWeb")
            params.putAll(extra)
            params["sign"] = clsSign(params)
            val qs = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            val raw = Http.get(
                "https://x-quote.cls.cn$path?$qs",
                mapOf("Referer" to "https://www.cls.cn/quotation")
            )
            val j = JSONObject(raw)
            if (j.optInt("code", -1) == 200 && j.optJSONObject("data") != null) {
                clsFails = 0
                j
            } else {
                clsFails++
                if (clsFails >= 3) clsBanned = true
                null
            }
        } catch (e: Exception) {
            clsFails++
            if (clsFails >= 3) clsBanned = true
            null
        }
    }

    /** 财联社大盘总览解析结果：A 股指数行情 + 全市场涨跌家数 + 分档分布 */
    private data class ClsOverview(
        val indices: Map<String, IndexQuote>,
        val breadth: Triple<Int, Int, Int>?,
        val distribution: List<DistBucket>?,
        val medianPct: Double
    )

    /** 拉取财联社总览（一次返回指数分时与涨跌分布）；失败返回 null */
    private fun fetchClsOverview(): ClsOverview? {
        val j = clsGet("/v2/quote/a/web/overview") ?: return null
        val data = j.optJSONObject("data") ?: return null

        val indices = LinkedHashMap<String, IndexQuote>()
        val tlines = data.optJSONObject("index_tlines")
        if (tlines != null) {
            tlines.keys().forEach { code ->
                val o = tlines.optJSONObject(code) ?: return@forEach
                val name = o.optString("secu_name", "")
                if (name.isBlank()) return@forEach
                val price = o.optString("last_px", "0").toDoubleOrNull() ?: 0.0
                val changePx = o.optString("change_px", "0").toDoubleOrNull() ?: 0.0
                val pct = o.optString("change", "0").removePrefix("+").removeSuffix("%")
                    .toDoubleOrNull() ?: 0.0
                if (price > 0) indices[name] = IndexQuote(name, price, changePx, pct)
            }
        }

        var breadth: Triple<Int, Int, Int>? = null
        var distribution: List<DistBucket>? = null
        var medianPct = 0.0
        val dis = data.optJSONObject("up_down_dis")
        if (dis != null && dis.optBoolean("status", false)) {
            val up = dis.optInt("rise_num", 0)
            val down = dis.optInt("fall_num", 0)
            val flat = dis.optInt("flat_num", 0)
            breadth = Triple(up, down, flat)
            medianPct = dis.optDouble("average_rise", 0.0) * 100.0
            val rawBuckets = listOf(
                "涨停" to dis.optInt("up_num", 0) to 1,
                "+10%" to dis.optInt("up_10", 0) to 1,
                "+8%" to dis.optInt("up_8", 0) to 1,
                "+6%" to dis.optInt("up_6", 0) to 1,
                "+4%" to dis.optInt("up_4", 0) to 1,
                "+2%" to dis.optInt("up_2", 0) to 1,
                "0%" to dis.optInt("flat_num", 0) to 0,
                "-2%" to dis.optInt("down_2", 0) to -1,
                "-4%" to dis.optInt("down_4", 0) to -1,
                "-6%" to dis.optInt("down_6", 0) to -1,
                "-8%" to dis.optInt("down_8", 0) to -1,
                "-10%" to dis.optInt("down_10", 0) to -1,
                "跌停" to dis.optInt("down_num", 0) to -1
            )
            distribution = rawBuckets.map { (pair, kind) ->
                DistBucket(pair.first, pair.second, kind)
            }
        }
        if (indices.isEmpty() && breadth == null) return null
        return ClsOverview(indices, breadth, distribution, medianPct)
    }

    /** 主入口：拉取全部大盘数据（后台线程调用） */
    fun load(): MarketData {
        val t0 = System.currentTimeMillis()

        // 1) 指数实时行情：财联社优先，缺失回落腾讯
        val cls = fetchClsOverview()
        val quotes = mergeTopQuotes(cls)
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

        // 6) 板块强弱：财联社优先，失败回落东财
        val sectorBundle = fetchSectors()
        val up = sectorBundle.up
        val down = sectorBundle.down
        val total = sectorBundle.total
        val sectorSrc = sectorBundle.source

        // 7) 多指数横滑行情（财联社 A 股 + 腾讯补海外/其余）；涨跌家数财联社优先，失败回落东财
        val indices = mergeAllIndices(cls)
        val breadth = cls?.breadth ?: fetchBreadthEm()
        val bUp = breadth?.first ?: -1
        val bDown = breadth?.second ?: -1
        val bFlat = breadth?.third ?: -1

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
            indices = indices,
            breadthUp = bUp, breadthDown = bDown, breadthFlat = bFlat,
            sectorSource = sectorSrc,
            distribution = cls?.distribution,
            medianPct = cls?.medianPct ?: 0.0,
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

    /** 顶部三指数：财联社优先，缺失的用腾讯补齐 */
    private fun mergeTopQuotes(cls: ClsOverview?): Map<String, QuotePoint> {
        val out = LinkedHashMap<String, QuotePoint>()
        cls?.indices?.forEach { (name, q) ->
            out[name] = QuotePoint(name, q.price, q.price - q.change, q.changePct)
        }
        fetchIndexQuotes().forEach { (name, q) -> out.putIfAbsent(name, q) }
        return out
    }

    /** 大盘页横滑指数清单：A 股 / 港股 / 美股；日经代码兼容失败时静默跳过 */
    private val INDEX_SYMS = listOf(
        "sh000001" to "上证指数",
        "sz399001" to "深证成指",
        "sz399006" to "创业板指",
        "sh000300" to "沪深300",
        "sh000688" to "科创50",
        "sh000015" to "上证红利",
        "sh000016" to "上证50",
        "sh000905" to "中证500",
        "bj899050" to "北证50",
        "hkHSI" to "恒生指数",
        "hkHSTECH" to "恒生科技",
        "usDJI" to "道琼斯",
        "usIXIC" to "纳斯达克",
        "usSPX" to "标普500",
        "int_nikkei" to "日经225"
    )

    /** 批量拉取横滑指数行情（腾讯 qt 接口）；解析不到的指数跳过 */
    private fun fetchAllIndices(): List<IndexQuote> {
        val raw = try {
            Http.get("https://qt.gtimg.cn/q=${INDEX_SYMS.joinToString(",") { it.first }}")
        } catch (e: Exception) {
            return emptyList()
        }
        val bySym = HashMap<String, Triple<Double, Double, Double>>()
        Regex("v_(\\w+)=\"([^\"]*)\"").findAll(raw).forEach { m ->
            val sym = m.groupValues[1]
            val parts = m.groupValues[2].split("~")
            if (parts.size < 33) return@forEach
            val price = parts[3].toDoubleOrNull() ?: 0.0
            val prev = parts[4].toDoubleOrNull() ?: 0.0
            val pct = parts[32].toDoubleOrNull() ?: 0.0
            if (price > 0) bySym[sym] = Triple(price, prev, pct)
        }
        return INDEX_SYMS.mapNotNull { (sym, name) ->
            val q = bySym[sym] ?: return@mapNotNull null
            IndexQuote(name, q.first, q.first - q.second, q.third)
        }
    }

    /** 横滑指数：腾讯清单为骨架，财联社 A 股行情覆盖同名项；海外/其余沿用腾讯 */
    private fun mergeAllIndices(cls: ClsOverview?): List<IndexQuote> {
        val tencent = fetchAllIndices()
        val clsByName = cls?.indices
        return tencent.map { q -> clsByName?.get(q.name) ?: q }
    }

    /** 全市场涨跌家数：东财 clist 按涨跌幅分页统计；失败返回 null（由上层降级） */
    private fun fetchBreadthEm(): Triple<Int, Int, Int>? {
        val fs = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23"
        var up = 0; var down = 0; var flat = 0
        try {
            for (page in 1..6) {
                val url = "https://push2.eastmoney.com/api/qt/clist/get" +
                    "?pn=$page&pz=1000&po=1&np=1&fltt=2&invt=2&fid=f3&fs=$fs&fields=f3"
                val raw = Http.get(url) ?: return null
                val data = JSONObject(raw).optJSONObject("data") ?: break
                val arr = data.optJSONArray("diff") ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i)?.optDouble("f3", 0.0) ?: 0.0
                    when { v > 0 -> up++; v < 0 -> down++; else -> flat++ }
                }
                if (up + down + flat >= data.optInt("total", 0)) break
            }
        } catch (e: Exception) {
            return null
        }
        return Triple(up, down, flat)
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

    private data class SectorBundle(
        val up: List<Sector>, val down: List<Sector>, val total: Int, val source: String
    )

    /** 板块调度：先财联社行业/概念/地域榜，失败或限流回落东财行业板块 */
    private fun fetchSectors(): SectorBundle {
        fetchClsSectors()?.let {
            return SectorBundle(it.first, it.second, it.third, "财联社行业/概念板块")
        }
        val em = fetchSectorsEm()
        return SectorBundle(em.first, em.second, em.third, "东方财富行业板块")
    }

    /** 财联社热门板块（行业+概念+地域），按涨幅取领涨5/领跌5；失败返回 null */
    private fun fetchClsSectors(): Triple<List<Sector>, List<Sector>, Int>? {
        val j = clsGet(
            "/web_quote/plate/hot_plate",
            mapOf("type" to "industry,concept,area", "way" to "change", "rever" to "1")
        ) ?: return null
        val data = j.optJSONObject("data") ?: return null
        val all = ArrayList<Sector>()
        listOf("industry", "concept", "area").forEach { key ->
            val arr = data.optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("secu_name", "")
                if (name.isBlank()) continue
                val change = o.optDouble("change", 0.0) * 100.0
                all += Sector(name, change, 0, 0)
            }
        }
        if (all.isEmpty()) return null
        val up = all.sortedByDescending { it.changePct }.take(5)
        val down = all.sortedBy { it.changePct }.take(5)
        return Triple(up, down, all.size)
    }

    /** 东财行业板块强弱排行（领涨5/领跌5） */
    private fun fetchSectorsEm(): Triple<List<Sector>, List<Sector>, Int> {
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
