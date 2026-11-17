package com.dsa.mobile

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 东方财富全市场列表接口（免费，用于选股） */
object EastmoneyApi {

    private const val FIELDS =
        "f2,f3,f5,f6,f8,f9,f10,f12,f14,f20,f23,f24"

    private const val FS =
        "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23" // 深主板+创业板+沪主板+科创板

    /** 拉取全 A 股快照（含 60 日涨跌幅），并行翻页 */
    fun fetchAllStocks(progress: (Int, Int) -> Unit = { _, _ -> }): List<ScreenStock> {
        // 先取第一页拿 total（带重试，网络偶发断流时自动重连）
        val first = fetchPageWithRetry(1)
        val total = first.second
        val all = first.first.toMutableList()

        val pageCount = ((total + 99) / 100).coerceAtMost(80)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (2..pageCount).map { p ->
            pool.submit<Pair<List<ScreenStock>, Int>> { fetchPageWithRetry(p) }
        }
        var done = 1
        futures.forEach { fu ->
            try {
                all.addAll(fu.get(60, TimeUnit.SECONDS).first)
            } catch (e: Exception) {
                // 单页失败跳过
            }
            done++
            progress(done, pageCount)
        }
        pool.shutdown()
        progress(pageCount, pageCount)
        return all
    }

    /** 单页请求（失败自动重试 3 次，间隔 400ms） */
    private fun fetchPageWithRetry(page: Int): Pair<List<ScreenStock>, Int> {
        var last: Exception? = null
        repeat(3) {
            try {
                return fetchPage(page)
            } catch (e: Exception) {
                last = e
                Thread.sleep(400L)
            }
        }
        throw last ?: RuntimeException("获取第 $page 页失败")
    }

    private fun fetchPage(page: Int): Pair<List<ScreenStock>, Int> {
        val url = "https://push2.eastmoney.com/api/qt/clist/get" +
            "?pn=$page&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3" +
            "&fs=$FS&fields=$FIELDS"
        val json = JSONObject(Http.get(url))
        val data = json.optJSONObject("data") ?: return Pair(emptyList(), 0)
        val total = data.optInt("total", 0)
        val diff = data.optJSONArray("diff") ?: return Pair(emptyList(), total)
        val list = mutableListOf<ScreenStock>()
        for (i in 0 until diff.length()) {
            val o = diff.optJSONObject(i) ?: continue
            val code = o.optString("f12", "")
            val name = o.optString("f14", "")
            if (code.isBlank() || name.isBlank()) continue
            list.add(
                ScreenStock(
                    code = code,
                    name = name,
                    price = o.optDouble("f2", 0.0),
                    changePct = o.optDouble("f3", 0.0),
                    volumeRatio = o.optDouble("f10", 0.0),
                    turnoverRate = o.optDouble("f8", 0.0),
                    pe = o.optDouble("f9", 0.0),
                    pb = o.optDouble("f23", 0.0),
                    totalMv = o.optDouble("f20", 0.0) / 1e8,
                    amount = o.optDouble("f6", 0.0),
                    change60d = o.optDouble("f24", 0.0)
                )
            )
        }
        return Pair(list, total)
    }

    /** 个股代码 → 东财 secid（用于跳转，未使用则返回 null）；北交所 4/8/920 归市场号 0 */
    fun secid(code: String): String? {
        return when {
            code.startsWith("920") || code.startsWith("4") || code.startsWith("8") -> "0.$code"
            code.startsWith("6") || code.startsWith("9") -> "1.$code"
            code.startsWith("0") || code.startsWith("2") || code.startsWith("3") -> "0.$code"
            else -> null
        }
    }

    /**
     * v2.6.3：拉取日K（前复权，klt=101 日线）
     * 腾讯日K被 WAF 拦截时的快照信号回退源。
     * klines 每行格式：date,open,close,high,low,volume,amount
     */
    fun fetchDailyKline(code: String, count: Int = 120): List<KLineBar> {
        val c = code.trim().lowercase().removePrefix("sh").removePrefix("sz").removePrefix("bj")
        val sec = when {
            c.startsWith("6") || c.startsWith("5") -> "1.$c"
            c.startsWith("9") && !c.startsWith("920") -> "1.$c"   // 900 开头沪市 B 股
            else -> "0.$c"                                        // 深市 / 北交所 4/8/920
        }
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
            "?secid=$sec&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57" +
            "&klt=101&fqt=1&end=20500101&lmt=$count"
        val json = JSONObject(Http.get(url, mapOf("Referer" to "https://quote.eastmoney.com/")))
        val data = json.optJSONObject("data") ?: throw RuntimeException("K线返回异常")
        val klines = data.optJSONArray("klines") ?: throw RuntimeException("K线无数据")
        val bars = mutableListOf<KLineBar>()
        for (i in 0 until klines.length()) {
            val row = klines.optString(i, "").split(",")
            if (row.size < 6) continue
            val date = row[0]
            if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) continue
            val open = row[1].toDoubleOrNull() ?: 0.0
            val close = row[2].toDoubleOrNull() ?: 0.0
            val high = row[3].toDoubleOrNull() ?: 0.0
            val low = row[4].toDoubleOrNull() ?: 0.0
            val vol = (row[5].toDoubleOrNull() ?: 0.0).toLong()
            if (open <= 0 || close <= 0) continue
            bars.add(KLineBar(date, open, high, low, close, vol))
        }
        if (bars.isEmpty()) throw RuntimeException("K线数据为空")
        return bars.sortedBy { it.date }
    }
}
