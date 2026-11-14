package com.dsa.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Tushare 数据接口（需 Token） */
object TushareApi {

    private const val ENDPOINT = "https://api.tushare.pro"

    /** 六位代码 → ts_code */
    fun tsCode(code: String): String {
        val c = code.trim().removePrefix("sh").removePrefix("sz").removePrefix("bj").uppercase()
        return when {
            c.startsWith("6") || c.startsWith("9") -> "$c.SH"
            c.startsWith("4") || c.startsWith("8") -> "$c.BJ"
            else -> "$c.SZ"
        }
    }

    private fun call(token: String, apiName: String, params: Map<String, Any>, fields: String? = null): JSONObject {
        val body = JSONObject()
            .put("api_name", apiName)
            .put("token", token)
            .put("params", JSONObject(params))
        if (!fields.isNullOrBlank()) body.put("fields", fields)
        val resp = Http.postJson(ENDPOINT, body.toString())
        val json = JSONObject(resp)
        if (json.optInt("code", -1) != 0) {
            throw RuntimeException("Tushare ${json.optString("msg", "接口错误")}")
        }
        return json
    }

    /** 拉取日K（自动前复权），start/end 形如 20250101 */
    fun fetchDailyKline(token: String, code: String, start: String, end: String): List<KLineBar> {
        val tc = tsCode(code)
        val daily = call(
            token, "daily",
            mapOf("ts_code" to tc, "start_date" to start, "end_date" to end),
            "trade_date,open,high,low,close,vol"
        )
        val data = daily.optJSONObject("data") ?: throw RuntimeException("Tushare 无数据")
        val items = data.optJSONArray("items") ?: return emptyList()

        // 复权因子（用于前复权）
        val factorMap = HashMap<String, Double>()
        try {
            val adj = call(
                token, "adj_factor",
                mapOf("ts_code" to tc, "start_date" to start, "end_date" to end),
                "trade_date,adj_factor"
            )
            val adjData = adj.optJSONObject("data")
            val adjItems = adjData?.optJSONArray("items")
            if (adjItems != null) {
                for (i in 0 until adjItems.length()) {
                    val row = adjItems.optJSONArray(i) ?: continue
                    if (row.length() >= 2) {
                        val raw = row.optString(0)
                        val key = if (raw.length == 8) {
                            "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
                        } else raw
                        factorMap[key] = row.optDouble(1, 1.0)
                    }
                }
            }
        } catch (e: Exception) {
            // 复权因子失败则用原始价
        }
        val latestFactor = factorMap.values.maxOrNull() ?: 1.0

        // 字段顺序：trade_date,open,high,low,close,vol,amount
        val bars = mutableListOf<KLineBar>()
        for (i in 0 until items.length()) {
            val row = items.optJSONArray(i) ?: continue
            if (row.length() < 6) continue
            val date = row.optString(0).let { d ->
                if (d.length == 8) "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}" else d
            }
            val open = row.optDouble(1, 0.0)
            val high = row.optDouble(2, 0.0)
            val low = row.optDouble(3, 0.0)
            val close = row.optDouble(4, 0.0)
            val vol = row.optDouble(5, 0.0).toLong()
            if (close <= 0) continue
            val k = factorMap[date]?.let { it / latestFactor } ?: 1.0
            bars.add(
                KLineBar(
                    date, open * k, high * k, low * k, close * k,
                    vol
                )
            )
        }
        return bars.sortedBy { it.date }
    }

    // ================= 选股全市场快照（东财限流时降级用） =================

    /** 按 ts_code → 行 JSONArray 的映射辅助 */
    private fun callItems(token: String, apiName: String, params: Map<String, String>, fields: String): JSONArray {
        val body = JSONObject()
            .put("api_name", apiName)
            .put("token", token)
            .put("params", JSONObject().apply { params.forEach { (k, v) -> put(k, v) } })
            .put("fields", fields)
        val resp = Http.postJson(ENDPOINT, body.toString())
        val json = JSONObject(resp)
        if (json.optInt("code", -1) != 0) {
            throw RuntimeException("Tushare $apiName: ${json.optString("msg", "接口错误")}")
        }
        val data = json.optJSONObject("data") ?: return JSONArray()
        return data.optJSONArray("items") ?: JSONArray()
    }

    /**
     * 拉取全 A 股快照（东财同构 ScreenStock 列表，含 60 日涨跌幅）。
     * 交易日通过 daily 接口回溯获取并当日缓存（trade_cal 免费档限频 5 次/天，不依赖它）。
     */
    fun fetchAllStocks(ctx: android.content.Context, token: String, progress: (Int, Int) -> Unit = { _, _ -> }): List<ScreenStock> {
        val today = yyyymmdd(Date())

        // 1) 交易日：优先当日缓存，否则 daily 回溯（最近交易日 + 约 60 个交易日前）
        progress(1, 5)
        val cachedDate = Prefs.tushareCalDate(ctx)
        var latestTd: String
        var day60: String
        if (cachedDate == today) {
            latestTd = Prefs.tushareCalTd(ctx)
            day60 = Prefs.tushareCalDay60(ctx)
        } else {
            latestTd = findTradingDay(token, today, 8)
                ?: throw RuntimeException("Tushare 找不到最近交易日")
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            val c = Calendar.getInstance()
            c.time = fmt.parse(latestTd) ?: throw RuntimeException("日期解析失败")
            c.add(Calendar.DAY_OF_YEAR, -90)
            day60 = findTradingDay(token, yyyymmdd(c.time), 8) ?: latestTd
            Prefs.saveTushareCal(ctx, today, latestTd, day60)
        }

        // 2) 当日日线（close/pct_chg/amount，amount 单位千元）
        progress(2, 5)
        val dailyToday = callItems(
            token, "daily",
            mapOf("trade_date" to latestTd),
            "ts_code,close,pct_chg,amount"
        )
        val todayMap = HashMap<String, Triple<Double, Double, Double>>()
        for (i in 0 until dailyToday.length()) {
            val row = dailyToday.optJSONArray(i) ?: continue
            val ts = row.optString(0, "")
            if (ts.isNotBlank()) todayMap[ts] = Triple(
                row.optDouble(1, 0.0), row.optDouble(2, 0.0), row.optDouble(3, 0.0)
            )
        }

        // 3) 60 个交易日前日线（收盘价，算 60 日涨跌幅）
        progress(3, 5)
        val daily60 = callItems(
            token, "daily",
            mapOf("trade_date" to day60),
            "ts_code,close"
        )
        val close60Map = HashMap<String, Double>()
        for (i in 0 until daily60.length()) {
            val row = daily60.optJSONArray(i) ?: continue
            val ts = row.optString(0, "")
            if (ts.isNotBlank()) close60Map[ts] = row.optDouble(1, 0.0)
        }

        // 4) 当日每日指标（pe/pb/总市值/换手率/量比；total_mv 单位万元）
        progress(4, 5)
        val basic = callItems(
            token, "daily_basic",
            mapOf("trade_date" to latestTd),
            "ts_code,pe,pb,total_mv,turnover_rate,volume_ratio"
        )
        class B {
            var pe = 0.0; var pb = 0.0; var mv = 0.0; var turnover = 0.0; var vr = 0.0
        }
        val basicMap = HashMap<String, B>()
        for (i in 0 until basic.length()) {
            val row = basic.optJSONArray(i) ?: continue
            val ts = row.optString(0, "")
            if (ts.isBlank()) continue
            val b = B()
            if (!row.isNull(1)) b.pe = row.optDouble(1, 0.0)
            if (!row.isNull(2)) b.pb = row.optDouble(2, 0.0)
            if (!row.isNull(3)) b.mv = row.optDouble(3, 0.0)
            if (!row.isNull(4)) b.turnover = row.optDouble(4, 0.0)
            if (!row.isNull(5)) b.vr = row.optDouble(5, 0.0)
            basicMap[ts] = b
        }

        // 5) 股票列表（名称）
        progress(5, 5)
        val stocks = callItems(
            token, "stock_basic",
            mapOf("list_status" to "L"),
            "ts_code,symbol,name"
        )
        val nameMap = HashMap<String, String>()
        for (i in 0 until stocks.length()) {
            val row = stocks.optJSONArray(i) ?: continue
            val ts = row.optString(0, "")
            val name = row.optString(2, "")
            if (ts.isNotBlank() && name.isNotBlank()) nameMap[ts] = name
        }

        // 组装：仅沪深 A 股（剔除北交所 .BJ、B 股）
        val out = mutableListOf<ScreenStock>()
        for ((ts, t) in todayMap) {
            if (!ts.endsWith(".SH") && !ts.endsWith(".SZ")) continue
            val code = ts.substringBefore(".")
            if (!(code.startsWith("6") || code.startsWith("0") || code.startsWith("3"))) continue
            val name = nameMap[ts] ?: continue
            val b = basicMap[ts]
            val c60 = close60Map[ts] ?: 0.0
            val change60 = if (c60 > 0 && t.first > 0) (t.first / c60 - 1.0) * 100.0 else 0.0
            out.add(
                ScreenStock(
                    code = code,
                    name = name,
                    price = t.first,
                    changePct = t.second,
                    volumeRatio = b?.vr ?: 0.0,
                    turnoverRate = b?.turnover ?: 0.0,
                    pe = b?.pe ?: 0.0,
                    pb = b?.pb ?: 0.0,
                    totalMv = (b?.mv ?: 0.0) / 1e4, // 万元 → 亿
                    amount = t.third * 1e3,          // 千元 → 元
                    change60d = change60
                )
            )
        }
        return out
    }

    private fun yyyymmdd(d: Date): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(d)

    /** 从 fromDate 起往前最多 backDays 天，找第一个有行情数据的交易日 */
    private fun findTradingDay(token: String, fromDate: String, backDays: Int): String? {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val cal = Calendar.getInstance()
        cal.time = fmt.parse(fromDate) ?: return null
        for (i in 0 until backDays) {
            val d = yyyymmdd(cal.time)
            val items = try {
                callItems(token, "daily", mapOf("trade_date" to d), "ts_code")
            } catch (e: Exception) {
                JSONArray()
            }
            if (items.length() > 0) return d
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return null
    }
}
