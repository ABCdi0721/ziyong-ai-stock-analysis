package com.dsa.mobile

import org.json.JSONObject

/**
 * v2.12.6 热股榜（人气榜）
 *
 * 首页「热股榜前 500」由多个公开热榜合并去重得到（单榜接口每次只开放前 100 名）：
 *  · 东方财富 今日人气榜（POST getAllCurrentList）
 *  · 东方财富 关注榜（POST getAllHisRcList，名次取 hrcrk）
 *  · 同花顺 24 小时热榜（type=hour，带股票名称）
 *  · 同花顺 今日热榜（type=day，带股票名称）
 *
 * 合并规则：同一只股票在多榜出现时保留各榜名次，热度分 = Σ 权重/(名次+10)，
 * 按热度分降序取前 [POOL_LIMIT] 只（实测约 300 余只），用于详情页排名与选股股票池。
 */
object HotRankApi {

    /** 热股榜池上限（用户口径为前 500，实际受数据源开放条数限制） */
    const val POOL_LIMIT = 500

    /** 单榜排名 */
    data class RankSlot(
        val name: String,       // 榜单名
        val rank: Int,          // 名次（1 起）
        val total: Int          // 该榜收录只数
    )

    /** 个股在热股榜中的位置 */
    data class RankInfo(
        val code: String,
        val slots: List<RankSlot>
    ) {
        val ranked: Boolean get() = slots.isNotEmpty()
        val bestRank: Int get() = slots.minOfOrNull { it.rank } ?: 0
        val best: RankSlot? get() = slots.minByOrNull { it.rank }
    }

    /** 热股榜池成员 */
    data class HotStock(
        val code: String,
        val name: String,
        val rank: Int,          // 综合热度名次（1 起）
        val tags: List<String>  // 所在榜单，如 "东财人气第3"
    )

    private const val EM_CURRENT = "https://emappdata.eastmoney.com/stockrank/getAllCurrentList"
    private const val EM_FOCUS = "https://emappdata.eastmoney.com/stockrank/getAllHisRcList"
    private const val THS_HOT = "https://dq.10jqka.com.cn/fuyao/hot_list_data/out/hot_list/v1/stock"

    private val emHeaders = mapOf(
        "Content-Type" to "application/json",
        "Referer" to "https://guba.eastmoney.com/"
    )

    private fun emBody(): String = JSONObject().apply {
        put("appId", "appId01")
        put("globalId", "786e4c21-70dc-435a-93bb-38")
        put("marketType", "")
        put("pageNo", 1)
        put("pageSize", 100)
    }.toString()

    /** 去掉 SH/SZ/BJ 前缀，统一成 6 位代码 */
    fun normalize(code: String): String {
        val c = code.trim().uppercase()
        return when {
            c.startsWith("SH") || c.startsWith("SZ") || c.startsWith("BJ") -> c.substring(2)
            else -> c
        }
    }

    /** 东方财富今日人气榜：code → 名次 */
    fun emCurrent(): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        try {
            val json = JSONObject(Http.postJson(EM_CURRENT, emBody(), emHeaders))
            val arr = json.optJSONArray("data") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = normalize(o.optString("sc", ""))
                val rk = o.optInt("rk", 0)
                if (code.isNotBlank() && rk > 0) out[code] = rk
            }
        } catch (e: Exception) {
            // 单源不可用时不影响其他榜单
        }
        return out
    }

    /** 东方财富关注榜：code → 名次（hrcrk） */
    fun emFocus(): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        try {
            val json = JSONObject(Http.postJson(EM_FOCUS, emBody(), emHeaders))
            val arr = json.optJSONArray("data") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = normalize(o.optString("sc", ""))
                val rk = o.optInt("hrcrk", 0)
                if (code.isNotBlank() && rk > 0) out[code] = rk
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** 同花顺热榜（type=hour / day）：code → Pair(名次, 名称) */
    fun ths(type: String): LinkedHashMap<String, Pair<Int, String>> {
        val out = LinkedHashMap<String, Pair<Int, String>>()
        try {
            val url = "$THS_HOT?stock_type=a&type=$type&list_type=normal"
            val json = JSONObject(Http.get(url))
            val data = json.optJSONObject("data") ?: return out
            val arr = data.optJSONArray("stock_list") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = normalize(o.optString("code", ""))
                val name = o.optString("name", "")
                val rank = o.optInt("order", i + 1)
                if (code.isNotBlank() && rank > 0) out[code] = Pair(rank, name)
            }
        } catch (e: Exception) {
        }
        return out
    }

    /**
     * 合并热股榜池（按热度分降序，最多 POOL_LIMIT 只）
     * 热度分 = Σ 权重/(名次+10)：在多个榜单同时靠前的股票排在更前
     */
    fun fetchPool(): List<HotStock> {
        val em = emCurrent()
        val focus = emFocus()
        val hour = ths("hour")
        val day = ths("day")

        val heat = HashMap<String, Double>()
        val names = HashMap<String, String>()
        val tags = HashMap<String, MutableList<String>>()

        fun add(map: Map<String, Int>, label: String, weight: Double) {
            map.forEach { (code, rank) ->
                heat[code] = (heat[code] ?: 0.0) + weight / (rank + 10.0)
                tags.getOrPut(code) { mutableListOf() }.add("${label}第$rank")
            }
        }

        add(em, "东财人气", 1.2)
        add(focus, "东财关注", 0.8)
        add(hour.mapValues { it.value.first }, "同花顺24h", 1.0)
        add(day.mapValues { it.value.first }, "同花顺今日", 1.0)
        hour.forEach { (code, v) -> if (v.second.isNotBlank()) names[code] = v.second }
        day.forEach { (code, v) -> if (v.second.isNotBlank()) names[code] = v.second }

        val sorted = heat.entries.sortedByDescending { it.value }.take(POOL_LIMIT)
        return sorted.mapIndexed { idx, e ->
            HotStock(
                code = e.key,
                name = names[e.key] ?: "",
                rank = idx + 1,
                tags = tags[e.key] ?: emptyList()
            )
        }
    }

    /** 查单只股票在各热榜的名次（详情页用）；三源全部不可达时返回空 slots */
    fun query(code: String): RankInfo {
        val c = normalize(code)
        val slots = mutableListOf<RankSlot>()
        val em = emCurrent()
        em[c]?.let { slots.add(RankSlot("东财人气榜", it, em.size)) }
        val focus = emFocus()
        focus[c]?.let { slots.add(RankSlot("东财关注榜", it, focus.size)) }
        val hour = ths("hour")
        hour[c]?.let { slots.add(RankSlot("同花顺24小时榜", it.first, hour.size)) }
        val day = ths("day")
        day[c]?.let { slots.add(RankSlot("同花顺今日榜", it.first, day.size)) }
        return RankInfo(c, slots)
    }
}
