package com.dsa.mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * 智兔数服（https://www.zhituapi.com）免费档接入层。
 *
 * 已核实边界（通读沪深数据 API 文档 78112 字符）：
 *  - 免费公开端点**没有**按个股代码查单只实时行情的接口，也没有个股日 K 线接口；
 *  - 字段最全的 A 股实时快照只挂在「沪深股通成分股行情」两个榜单：
 *      · 沪股通成分  GET /ht/nbzj/hgtc?token=
 *      · 深股通成分  GET /ht/nbzj/sgtc?token=
 *    返回成分股数组（按涨跌幅降序），不接受代码入参，仅覆盖股通成分（非全市场）；
 *  - 其余端点（龙虎榜/资金流/市场榜单/财报/机构持股/北向历史）均为榜单或按日期、年季汇总，
 *    无估值/市值/量比，也不能按代码单查。
 *
 * 因此本层定位为「批量成分快照补充源」：拉取股通成分全字段快照，按代码与主链结果
 * 保守合并补缺（只补主链缺失/为 0 的字段，不用 0 冲掉已有非空值），不主动扩充候选集。
 *
 * 字段口径（hgtc/sgtc，verbatim）：
 *  dm 代码(纯6位)、mc 名称、p 当前价(元)、pc 涨跌幅(%)、h 最高价(元)、l 最低价(元)、
 *  v 成交量(手)、cje 成交额(元)、hs 换手(%)、lb 量比、pe 动态市盈率、sjl 市净率、
 *  sz 总市值(元)、zdf60 60日涨跌幅(%)。
 */
object ZhituApi {

    private const val BASE = "https://api.zhituapi.com"
    private const val ENDPOINT_HGT = "/ht/nbzj/hgtc"   // 沪股通成分
    private const val ENDPOINT_SGT = "/ht/nbzj/sgtc"   // 深股通成分

    /**
     * 拉取沪深股通成分快照，按 6 位代码索引。
     * 单个端点失败不影响另一个；全部失败返回空 map（上层保持主链原样）。
     */
    fun fetchConstituents(token: String): Map<String, ScreenStock> {
        val map = HashMap<String, ScreenStock>()
        listOf(ENDPOINT_HGT, ENDPOINT_SGT).forEach { path ->
            try {
                val url = "$BASE$path?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
                val resp = Http.get(url)
                val arr = extractArray(resp)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val stock = parseStock(o) ?: continue
                    // 先到先得，不被后到的空值覆盖
                    if (!map.containsKey(stock.code)) map[stock.code] = stock
                }
            } catch (e: Exception) {
                // 单端点失败跳过，不阻断主链
            }
        }
        return map
    }

    /**
     * 高层补缺：token 为空或拉取失败时原样返回主链；
     * 命中同代码的成分记录时，仅补主链缺失/为 0 的字段。
     */
    fun enrich(token: String, base: List<ScreenStock>): List<ScreenStock> {
        if (token.isBlank() || base.isEmpty()) return base
        val quotes = try {
            fetchConstituents(token)
        } catch (e: Exception) {
            return base
        }
        if (quotes.isEmpty()) return base
        return base.map { st ->
            val q = quotes[st.code] ?: return@map st
            st.copy(
                name = st.name.ifBlank { q.name },
                price = if (st.price <= 0.0) q.price else st.price,
                changePct = if (st.changePct == 0.0 && q.changePct != 0.0) q.changePct else st.changePct,
                volumeRatio = if (st.volumeRatio <= 0.0) q.volumeRatio else st.volumeRatio,
                turnoverRate = if (st.turnoverRate <= 0.0) q.turnoverRate else st.turnoverRate,
                pe = if (st.pe <= 0.0) q.pe else st.pe,
                pb = if (st.pb <= 0.0) q.pb else st.pb,
                totalMv = if (st.totalMv <= 0.0) q.totalMv else st.totalMv,
                amount = if (st.amount <= 0.0) q.amount else st.amount,
                change60d = if (st.change60d == 0.0) q.change60d else st.change60d,
                high = if (st.high <= 0.0) q.high else st.high,
                low = if (st.low <= 0.0) q.low else st.low,
                volume = if (st.volume == 0L) q.volume else st.volume
            )
        }
    }

    // ---------- 内部实现 ----------

    private fun parseStock(o: JSONObject): ScreenStock? {
        val rawCode = o.optString("dm", "")
        val code = rawCode
            .removePrefix("sh").removePrefix("sz").removePrefix("bj")
            .filter { it.isDigit() }
        val price = o.optDouble("p", 0.0)
        if (code.isBlank() || price <= 0.0) return null
        val totalMvYuan = o.optDouble("sz", 0.0)
        return ScreenStock(
            code = code,
            name = o.optString("mc", code).ifBlank { code },
            price = price,
            changePct = o.optDouble("pc", 0.0),
            volumeRatio = o.optDouble("lb", 0.0),
            turnoverRate = o.optDouble("hs", 0.0),
            pe = o.optDouble("pe", 0.0),
            pb = o.optDouble("sjl", 0.0),
            totalMv = if (totalMvYuan > 0.0) totalMvYuan / 1e8 else 0.0, // 元 → 亿
            amount = o.optDouble("cje", 0.0),
            change60d = o.optDouble("zdf60", 0.0),
            high = o.optDouble("h", 0.0),
            low = o.optDouble("l", 0.0),
            volume = o.optDouble("v", 0.0).toLong()
        )
    }

    /** 兼容裸数组或外层包裹 data/list/rank_list/items 的响应结构 */
    private fun extractArray(resp: String): JSONArray {
        val trimmed = resp.trim()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val obj = JSONObject(trimmed)
        obj.optJSONArray("data")?.let { return it }
        obj.optJSONObject("data")?.let { d ->
            d.optJSONArray("list")?.let { return it }
            d.optJSONArray("rank_list")?.let { return it }
            d.optJSONArray("items")?.let { return it }
        }
        obj.optJSONArray("list")?.let { return it }
        obj.optJSONArray("rank_list")?.let { return it }
        return JSONArray()
    }
}
