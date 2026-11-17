package com.dsa.mobile

import org.json.JSONObject

/** 腾讯行情与K线接口（免费，无需 Key） */
object TencentApi {

    /** 股票 / ETF 代码 → 腾讯前缀；5 开头为沪市 ETF/基金，15/16/18 为深市 ETF */
    fun symbol(code: String): String {
        val c = code.trim().lowercase().removePrefix("sh").removePrefix("sz").removePrefix("bj")
        return when {
            c.startsWith("6") || c.startsWith("9") || c.startsWith("5") -> "sh$c"
            c.startsWith("4") || c.startsWith("8") -> "bj$c"
            else -> "sz$c"
        }
    }

    /** 拉取实时行情 */
    fun fetchQuote(code: String): Quote {
        val sym = symbol(code)
        val raw = Http.get("https://qt.gtimg.cn/q=$sym")
        val m = Regex("=\"([^\"]*)\"").find(raw) ?: throw RuntimeException("行情返回异常")
        return parseLine(code, m.groupValues[1])
    }

    /** 批量拉取实时行情（一次请求多只股票） */
    fun fetchQuotes(codes: List<String>): Map<String, Quote> {
        if (codes.isEmpty()) return emptyMap()
        val syms = codes.map { symbol(it) }
        val raw = Http.get("https://qt.gtimg.cn/q=${syms.joinToString(",")}")
        val out = LinkedHashMap<String, Quote>()
        val regex = Regex("v_(\\w+)=\"([^\"]*)\"")
        regex.findAll(raw).forEach { m ->
            val sym = m.groupValues[1]
            val code = codes.firstOrNull { symbol(it) == sym } ?: return@forEach
            try {
                out[code] = parseLine(code, m.groupValues[2])
            } catch (e: Exception) {
                // 单只解析失败跳过
            }
        }
        return out
    }

    private fun parseLine(code: String, line: String): Quote {
        val f = line.split("~")
        if (f.size < 50) throw RuntimeException("行情字段不足")
        fun d(i: Int): Double = f.getOrNull(i)?.toDoubleOrNull() ?: 0.0
        return Quote(
            code = code,
            name = f[1],
            price = d(3),
            prevClose = d(4),
            open = d(5),
            high = d(33),
            low = d(34),
            change = d(31),
            changePct = d(32),
            volume = d(36).toLong(),
            amount = d(37) * 10000,
            turnover = d(38),
            pe = d(39),
            pb = d(46),
            totalMv = d(45),
            volumeRatio = d(49)
        )
    }

    /**
     * 拉取日K（前复权）
     * @param count 需要的K线根数
     */
    fun fetchDailyKline(code: String, count: Int = 320): List<KLineBar> {
        val sym = symbol(code)
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$sym,day,,,$count,qfq"
        val json = JSONObject(Http.get(url))
        return parseDailyKline(json, sym)
    }

    /**
     * v2.6.3：腾讯备用域拉取日K（主域 web.ifzq.gtimg.cn 被 WAF 拦截时使用）
     */
    fun fetchDailyKlineAlt(code: String, count: Int = 120): List<KLineBar> {
        val sym = symbol(code)
        val url = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/fqkline/get?param=$sym,day,,,$count,qfq"
        val json = JSONObject(Http.get(url))
        return parseDailyKline(json, sym)
    }

    /**
     * v2.12.8：拉取指数日K（sym 形如 sh000001 / sz399001 / sz399006 / sh000688）
     * 用于「严重异常波动」偏离值的基准指数。
     */
    fun fetchIndexDailyKline(sym: String, count: Int = 20): List<KLineBar> {
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$sym,day,,,$count,qfq"
        val json = JSONObject(Http.get(url))
        return parseDailyKline(json, sym)
    }

    /** 解析腾讯 fqkline 日K JSON（主域/备用域共用） */
    private fun parseDailyKline(json: JSONObject, sym: String): List<KLineBar> {
        val data = json.optJSONObject("data") ?: throw RuntimeException("K线返回异常")
        val node = data.optJSONObject(sym) ?: throw RuntimeException("K线无数据")
        val arr = node.optJSONArray("qfqday") ?: node.optJSONArray("day") ?: throw RuntimeException("K线无数据")
        val bars = mutableListOf<KLineBar>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            if (row.length() < 6) continue
            val date = row.optString(0)
            if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) continue
            val open = row.optDouble(1, 0.0)
            val close = row.optDouble(2, 0.0)
            val high = row.optDouble(3, 0.0)
            val low = row.optDouble(4, 0.0)
            val vol = row.optDouble(5, 0.0).toLong()
            if (open <= 0 || close <= 0) continue
            bars.add(KLineBar(date, open, high, low, close, vol))
        }
        if (bars.isEmpty()) throw RuntimeException("K线数据为空")
        return bars.sortedBy { it.date }
    }

    /**
     * 拉取当日分时数据（分钟价格序列）
     * 返回 KLineBar 列表：date=HH:MM，open=close=high=low=price，volume=0
     */
    fun fetchMinute(code: String): List<KLineBar> {
        val sym = symbol(code)
        val url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=$sym"
        val raw = Http.get(url)
        val json = JSONObject(raw)
        val data = json.optJSONObject("data") ?: throw RuntimeException("分时返回异常")
        val node = data.optJSONObject(sym) ?: throw RuntimeException("分时无数据")
        val inner = node.optJSONObject("data") ?: throw RuntimeException("分时无数据")
        val arr = inner.optJSONArray("data") ?: throw RuntimeException("分时无数据")
        val bars = mutableListOf<KLineBar>()
        for (i in 0 until arr.length()) {
            // 腾讯分时每行是单字符串："0930 1343.88 210 28221480.00"
            val line = arr.optString(i).trim()
            if (line.isEmpty()) continue
            val parts = line.split(" ")
            if (parts.size < 2) continue
            val price = parts[1].toDoubleOrNull() ?: continue
            if (price <= 0) continue
            bars.add(KLineBar(parts[0], price, price, price, price, 0L))
        }
        if (bars.isEmpty()) throw RuntimeException("分时数据为空")
        return bars
    }
}
