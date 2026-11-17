package com.dsa.mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * 新浪日K接口（免费，无需 Key）
 * v2.6.3：腾讯日K主域/备用域被 WAF 拦截、东财接口不可达时的兜底源
 * 接口返回 JSONP：var _=([{"day":"2026-10-26","open":"6.500","high":"6.640","low":"6.480","close":"6.600","volume":"..."}])
 */
object SinaApi {

    /** 股票代码 → 新浪前缀（sh600000 / sz000001 / bj430xxx；5 开头为沪市 ETF） */
    fun symbol(code: String): String {
        val c = code.trim().lowercase().removePrefix("sh").removePrefix("sz").removePrefix("bj")
        return when {
            c.startsWith("6") || c.startsWith("9") || c.startsWith("5") -> "sh$c"
            c.startsWith("4") || c.startsWith("8") -> "bj$c"
            else -> "sz$c"
        }
    }

    /** 拉取日K（scale=240 日线，不复权） */
    fun fetchDailyKline(code: String, count: Int = 120): List<KLineBar> {
        val sym = symbol(code)
        val url = "https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_=/CN_MarketDataService.getKLineData" +
            "?symbol=$sym&scale=240&ma=no&datalen=$count"
        val raw = Http.get(url)
        if (raw.contains("<script>", true) || raw.contains("location.href", true)) {
            throw RuntimeException("新浪K线被反爬拦截")
        }
        val body = raw.substringAfter("([").substringBeforeLast("])")
        val arr = JSONArray("[$body]")
        val bars = mutableListOf<KLineBar>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val day = o.optString("day", "")
            if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(day)) continue
            val open = o.optDouble("open", 0.0)
            val close = o.optDouble("close", 0.0)
            val high = o.optDouble("high", 0.0)
            val low = o.optDouble("low", 0.0)
            val vol = o.optLong("volume", 0L)
            if (open <= 0 || close <= 0) continue
            bars.add(KLineBar(day, open, high, low, close, vol))
        }
        if (bars.isEmpty()) throw RuntimeException("K线数据为空")
        return bars.sortedBy { it.date }
    }
}
