package com.dsa.mobile

import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * v2.12.23 选股页排行榜（v2.12.24 放开前 100 名、条目补现价/信号/评分）
 *
 * 四类榜单：热股榜 / 龙虎榜 / 板块热榜 / ETF热榜；数据源：同花顺 / 东方财富 / 腾讯。
 *
 * 条数口径：经实测东财 clist、龙虎榜 datacenter、腾讯批量行情单页 100 条均无截断、
 * 未触发限流；日K兜底源（腾讯备用域 / 东财）并发拉取稳定，故一次性加载前 [TOP_N] 名，
 * 不做分页。
 *
 * 免费公开接口现实口径：
 *  · 热股榜：东方财富人气/关注、同花顺 24h/今日；腾讯无公开热榜，按行情成交额口径回退排序。
 *  · 龙虎榜 / 板块热榜 / ETF热榜：当前主要由东方财富公开接口开放；同花顺、腾讯对应榜单不可用时
 *    回退东方财富，并在条目来源标签上注明。
 *  · 单源不可用或返回空时返回空列表，由界面展示空态，不编造名次。
 */
object RankBoardApi {

    enum class Type(val label: String) {
        HOT("热股榜"),
        LONGHU("龙虎榜"),
        SECTOR("板块热榜"),
        ETF("ETF热榜")
    }

    enum class Source(val label: String) {
        THS("同花顺"),
        EM("东方财富"),
        TX("腾讯")
    }

    data class RankItem(
        val code: String,
        val name: String,
        /** 副标题：个股为代码；板块为涨跌家数；ETF 为成交额 */
        val sub: String,
        val changePct: Double,
        val rank: Int,
        val sourceLabel: String,
        /** 现价（个股 / ETF 行情，0 表示无现价） */
        val price: Double = 0.0,
        /** 技术面中文信号（强烈买入/买入/观望/卖出/强烈卖出），空串表示不显示 */
        val signal: String = "",
        /** 技术面综合评分（0 表示不显示） */
        val score: Int = 0
    )

    /** 每类榜单展示条数（放开完整榜单，最多前 100 名） */
    private const val TOP_N = 100

    private const val EM_CLIST = "https://push2.eastmoney.com/api/qt/clist/get"
    private const val EM_DATACENTER = "https://datacenter-web.eastmoney.com/api/data/v1/get"

    fun fetch(type: Type, source: Source): List<RankItem> = when (type) {
        Type.HOT -> fetchHot(source)
        Type.LONGHU -> fetchLongHu(source)
        Type.SECTOR -> fetchSector(source)
        Type.ETF -> fetchEtf(source)
    }

    // ---------------- 热股榜 ----------------

    private fun fetchHot(source: Source): List<RankItem> {
        // 第一步：按数据源取候选 code + 名称
        val raw: List<Pair<String, String>> = when (source) {
            Source.THS -> {
                val hour = HotRankApi.ths("hour")
                val day = HotRankApi.ths("day")
                val merged = LinkedHashMap<String, Pair<Int, String>>()
                hour.forEach { (c, v) -> merged[c] = v }
                day.forEach { (c, v) ->
                    val prev = merged[c]
                    merged[c] = if (prev == null) v else prev.copy(first = minOf(prev.first, v.first))
                }
                merged.entries.sortedBy { it.value.first }.take(TOP_N)
                    .map { it.key to it.value.second }
            }
            Source.EM -> {
                val em = HotRankApi.emCurrent()
                val focus = HotRankApi.emFocus()
                val merged = LinkedHashMap<String, Int>()
                em.forEach { (c, r) -> merged[c] = r }
                focus.forEach { (c, r) -> merged[c] = minOf(merged[c] ?: r, r) }
                merged.entries.sortedBy { it.value }.take(TOP_N).map { it.key to "" }
            }
            Source.TX -> {
                // 腾讯无公开热榜：按行情成交额口径取活跃个股，标签注明腾讯行情口径
                emClistStocks(fid = "f6", pz = TOP_N)
            }
        }

        val codes = raw.map { it.first }
        val quotes = try {
            TencentApi.fetchQuotes(codes)
        } catch (e: Exception) {
            emptyMap()
        }

        val base = raw.mapIndexed { idx, (code, name0) ->
            val q = quotes[code]
            val name = q?.name?.ifBlank { name0 } ?: name0
            val pct = q?.changePct ?: 0.0
            val price = q?.price ?: 0.0
            RankItem(
                code = code,
                name = name.ifBlank { code },
                sub = code,
                changePct = pct,
                rank = idx + 1,
                sourceLabel = source.label,
                price = price
            )
        }
        return enrichStockSignals(base)
    }

    // ---------------- 龙虎榜 ----------------

    private fun fetchLongHu(source: Source): List<RankItem> {
        val note = if (source != Source.EM) "（当前仅东方财富开放）" else ""
        val headers = mapOf("Referer" to "https://data.eastmoney.com/")

        // 1) 取最近一个有龙虎榜记录的交易日
        val latest = try {
            val u1 = "$EM_DATACENTER?reportName=RPT_DAILYBILLBOARD_DETAILS" +
                "&columns=TRADE_DATE&sortColumns=TRADE_DATE&sortTypes=-1" +
                "&pageSize=1&pageNumber=1&source=WEB&client=WEB"
            val arr1 = JSONObject(Http.get(u1, headers))
                .optJSONObject("result")?.optJSONArray("data")
            val raw = arr1?.optJSONObject(0)?.optString("TRADE_DATE", "") ?: ""
            if (raw.length >= 10) raw.substring(0, 10) else ""
        } catch (e: Exception) {
            ""
        }
        if (latest.isBlank()) return emptyList()

        // 2) 该交易日按净买入额降序；同一股票多条席位记录按代码去重
        val u2 = "$EM_DATACENTER?reportName=RPT_DAILYBILLBOARD_DETAILS" +
            "&columns=ALL&filter=(TRADE_DATE%3D%27$latest%27)" +
            "&sortColumns=BILLBOARD_NET_AMT&sortTypes=-1" +
            "&pageSize=$TOP_N&pageNumber=1&source=WEB&client=WEB"
        val arr = try {
            JSONObject(Http.get(u2, headers)).optJSONObject("result")?.optJSONArray("data")
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val out = LinkedHashMap<String, RankItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("SECURITY_CODE", "")
            if (code.isBlank() || out.containsKey(code)) continue
            val name = o.optString("SECURITY_NAME_ABBR", "")
            val pct = o.optDouble("CHANGE_RATE", 0.0)
            val netBuy = o.optDouble("BILLBOARD_NET_AMT", 0.0) / 1e8
            val explain = o.optString("EXPLANATION", "").trim().take(14)
            val sign = if (netBuy >= 0) "净买入" else "净卖出"
            val sub = "$sign ${String.format("%.2f", kotlin.math.abs(netBuy))} 亿" +
                if (explain.isNotBlank()) " · $explain" else ""
            out[code] = RankItem(
                code = code,
                name = name.ifBlank { code },
                sub = sub,
                changePct = pct,
                rank = out.size + 1,
                sourceLabel = Source.EM.label + note
            )
        }
        val list = out.values.toList()

        // 批量补现价 / 名称，再补技术面信号与评分
        val quotes = try {
            TencentApi.fetchQuotes(list.map { it.code })
        } catch (e: Exception) {
            emptyMap()
        }
        val priced = list.map { it ->
            val q = quotes[it.code]
            it.copy(
                price = q?.price ?: 0.0,
                name = q?.name?.ifBlank { it.name } ?: it.name
            )
        }
        return enrichStockSignals(priced)
    }

    // ---------------- 板块热榜 ----------------

    private fun fetchSector(source: Source): List<RankItem> {
        val url = "$EM_CLIST?pn=1&pz=$TOP_N&po=1&np=1&fltt=2&invt=2&fid=f3" +
            "&fs=m:90+t:2&fields=f12,f14,f3,f104,f105"
        val arr = try {
            val json = JSONObject(Http.get(url, mapOf("Referer" to "https://quote.eastmoney.com/")))
            json.optJSONObject("data")?.optJSONArray("diff")
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val out = ArrayList<RankItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("f14", "")
            val pct = o.optDouble("f3", 0.0)
            val up = o.optInt("f104", 0)
            val down = o.optInt("f105", 0)
            out += RankItem(
                code = o.optString("f12", ""),
                name = name,
                sub = "涨 $up · 跌 $down",
                changePct = pct,
                rank = i + 1,
                sourceLabel = Source.EM.label
            )
        }
        return out
    }

    // ---------------- ETF热榜 ----------------

    private fun fetchEtf(source: Source): List<RankItem> {
        val url = "$EM_CLIST?pn=1&pz=$TOP_N&po=1&np=1&fltt=2&invt=2&fid=f6" +
            "&fs=b:MK0021,b:MK0022,b:MK0023,b:MK0024" +
            "&fields=f12,f14,f2,f3,f6"
        val arr = try {
            val json = JSONObject(Http.get(url, mapOf("Referer" to "https://quote.eastmoney.com/")))
            json.optJSONObject("data")?.optJSONArray("diff")
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val out = ArrayList<RankItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("f14", "")
            val pct = o.optDouble("f3", 0.0)
            val price = o.optDouble("f2", 0.0)
            val amt = o.optDouble("f6", 0.0) / 1e8
            out += RankItem(
                code = o.optString("f12", ""),
                name = name,
                sub = "成交 ${String.format("%.2f", amt)} 亿",
                changePct = pct,
                rank = i + 1,
                sourceLabel = Source.EM.label,
                price = price
            )
        }
        return out
    }

    // ---------------- 通用 ----------------

    /** 东财全 A 快照按指定字段排序，返回 code → 名称 */
    private fun emClistStocks(fid: String, pz: Int): List<Pair<String, String>> {
        val url = "$EM_CLIST?pn=1&pz=$pz&po=1&np=1&fltt=2&invt=2&fid=$fid" +
            "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23&fields=f12,f14"
        val arr = try {
            val json = JSONObject(Http.get(url, mapOf("Referer" to "https://quote.eastmoney.com/")))
            json.optJSONObject("data")?.optJSONArray("diff")
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += o.optString("f12", "") to o.optString("f14", "")
        }
        return out
    }

    /**
     * 对个股条目并发补技术面信号与评分（与详情页"操作信号"同源，Analyzer 本地日K分析）。
     * 8 线程并发；单只拉取 / 分析失败则保留空信号、0 分，不影响其余条目。
     * 板块 / ETF 不调用本方法。
     */
    private fun enrichStockSignals(items: List<RankItem>): List<RankItem> {
        if (items.isEmpty()) return items
        val exec = Executors.newFixedThreadPool(8)
        try {
            val futures = items.map { item ->
                exec.submit<Pair<String, Pair<String, Int>?>> { item.code to analyzeOne(item.code) }
            }
            val map = HashMap<String, Pair<String, Int>>()
            futures.forEach { f ->
                try {
                    val (c, v) = f.get()
                    if (v != null) map[c] = v
                } catch (e: Exception) {
                    // 单只失败跳过
                }
            }
            return items.map {
                val v = map[it.code]
                it.copy(signal = v?.first ?: "", score = v?.second ?: 0)
            }
        } finally {
            exec.shutdown()
        }
    }

    /** 拉日K做快照分析，返回（中文信号, 评分）；失败返回 null */
    private fun analyzeOne(code: String): Pair<String, Int>? {
        if (!Regex("^\\d{6}$").matches(code)) return null
        return try {
            // 备用域 → 东财 → 腾讯主域 → 新浪：主域在部分网络被 WAF 拦截，优先走稳的源
            val bars = try {
                TencentApi.fetchDailyKlineAlt(code, 120)
            } catch (e1: Exception) {
                try {
                    EastmoneyApi.fetchDailyKline(code, 120)
                } catch (e2: Exception) {
                    try {
                        TencentApi.fetchDailyKline(code, 120)
                    } catch (e3: Exception) {
                        SinaApi.fetchDailyKline(code, 120)
                    }
                }
            }
            val r = Analyzer.analyze(bars, code)
            val sig = Analyzer.SIGNAL_ZH[r.buySignal] ?: r.buySignal
            sig to r.signalScore
        } catch (e: Exception) {
            null
        }
    }
}
