package com.dsa.mobile

import org.json.JSONObject

/**
 * 腾讯自选股 rank 全市场选股（v2.3.0，免费，无需任何 Key）
 * GET /cgi/cgi-bin/rank/hs/getBoardRankList?board_code=aStock&sort_type=price&direct=down&offset=&count=200
 * 翻页拉全市场（每页 200，约 26-28 页），字段一次拿全：
 * zxj=现价、zdf=涨跌幅%、lb=量比、hsl=换手率%、pe_ttm=PE(TTM)、pn=市净率、
 * zsz=总市值(亿)、turnover=成交额(万元→×10000转元)、zdf_d60=60日涨跌幅%
 * 注意：name 字段为 GBK 编码，Http.detectAndDecode 自动转码
 */
object TencentScreenApi {

    private const val RANK_URL =
        "https://proxy.finance.qq.com/cgi/cgi-bin/rank/hs/getBoardRankList?board_code=aStock&sort_type=price&direct=down&offset=%d&count=200"
    private const val MAX_PAGES = 30

    /** 拉取全市场 A 股快照（ScreenStock 全字段就绪） */
    fun fetchAllStocks(onPage: (done: Int, total: Int) -> Unit = { _, _ -> }): List<ScreenStock> {
        val out = mutableListOf<ScreenStock>()
        val first = fetchPage(0)
        if (first.isEmpty()) return out
        out.addAll(first)
        val total = if (first.size < 200) 1 else MAX_PAGES
        onPage(1, total)
        var page = 1
        while (page < MAX_PAGES) {
            val batch = fetchPage(page * 200)
            if (batch.isEmpty()) break
            out.addAll(batch)
            onPage(page + 1, total)
            if (batch.size < 200) break
            page++
        }
        return out
    }

    private fun fetchPage(offset: Int): List<ScreenStock> {
        val url = RANK_URL.format(offset)
        val resp = Http.get(
            url,
            mapOf(
                "Referer" to "https://stockapp.finance.qq.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
            )
        )
        val data = JSONObject(resp).optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("rank_list") ?: return emptyList()
        val out = mutableListOf<ScreenStock>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val code = o.optString("code", "")
            val price = o.optDouble("zxj", 0.0)
            if (code.isBlank() || price <= 0) continue
            out.add(
                ScreenStock(
                    code = code.substringAfter("sh").substringAfter("sz").substringAfter("bj"),
                    name = o.optString("name", code),
                    price = price,
                    changePct = o.optDouble("zdf", 0.0),
                    volumeRatio = o.optDouble("lb", 1.0),
                    turnoverRate = o.optDouble("hsl", 0.0),
                    pe = o.optDouble("pe_ttm", 0.0),
                    pb = o.optDouble("pn", 0.0),
                    totalMv = o.optDouble("zsz", 0.0),
                    amount = o.optDouble("turnover", 0.0) * 10000.0,
                    change60d = o.optDouble("zdf_d60", 0.0)
                )
            )
        }
        return out
    }
}
