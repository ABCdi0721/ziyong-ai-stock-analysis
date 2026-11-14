package com.dsa.mobile

import java.time.LocalDate
import kotlin.math.abs

/**
 * v2.12.8：严重异常波动（异动）提醒。
 *
 * 规则口径（沪深交易所「严重异常波动」）：
 * 连续 10 个交易日内，日收盘价涨跌幅偏离值累计达到 +100%（或 −50%）。
 * 偏离值 = 个股当日涨跌幅 − 对应基准指数当日涨跌幅。
 *
 * 卡片上的「当日收盘涨幅达到 X% 将触发」中：
 *   X = 阈值(100%) − 前 9 个交易日累计偏离值
 * 即当日收盘涨幅再达到 X% 时，10 日累计偏离值正好达到 +100%。
 * 负向同理：−50% − 前 9 日累计值。
 */
object AbnormalAlert {

    /** 计算结果（所有百分比单位均为 %） */
    data class Result(
        val dev10: Double,          // 最近 10 日累计偏离值（含当日）
        val pre9: Double,           // 前 9 个交易日累计偏离值
        val todayPct: Double,       // 当日涨跌幅（实时）
        val needTodayPct: Double,   // 当日收盘涨幅达到该值 → 触发 +100%
        val needDownPct: Double,    // 当日收盘涨幅低至该值 → 触发 −50%
        val triggeredUp: Boolean,
        val triggeredDown: Boolean,
        val daysUsed: Int,          // 实际参与计算的历史交易日数
        val dataOk: Boolean         // 指数基准是否齐全
    ) {
        val triggered: Boolean get() = triggeredUp || triggeredDown
    }

    /** 该股票严重异常波动的基准指数（腾讯代码）；北交所等无对应基准时返回 null */
    fun indexSymbol(code: String): String? {
        val c = code.trim().lowercase()
            .removePrefix("sh").removePrefix("sz").removePrefix("bj")
        return when {
            c.startsWith("300") || c.startsWith("301") -> "sz399006"   // 创业板指
            c.startsWith("688") || c.startsWith("689") -> "sh000688"   // 科创 50
            c.startsWith("6") || c.startsWith("9") -> "sh000001"       // 上证综指
            c.startsWith("0") || c.startsWith("2") || c.startsWith("3") -> "sz399001" // 深证成指
            else -> null                                                // 北交所等不适用
        }
    }

    fun indexName(sym: String?): String = when (sym) {
        "sh000001" -> "上证综指"
        "sz399001" -> "深证成指"
        "sz399006" -> "创业板指"
        "sh000688" -> "科创 50"
        else -> "基准指数"
    }

    private fun empty(todayPct: Double) = Result(
        dev10 = 0.0, pre9 = 0.0, todayPct = todayPct,
        needTodayPct = 0.0, needDownPct = 0.0,
        triggeredUp = false, triggeredDown = false,
        daysUsed = 0, dataOk = false
    )

    /** 相邻收盘价算日涨跌幅（%） */
    private fun dailyPct(bars: List<KLineBar>): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>(bars.size)
        for (i in 1 until bars.size) {
            val prev = bars[i - 1].close
            if (prev > 0.0 && bars[i].close > 0.0) {
                out.add(bars[i].date to (bars[i].close / prev - 1) * 100.0)
            }
        }
        return out
    }

    /**
     * 计算最近 10 个交易日的偏离值。
     * @param liveTodayPct 实时当日涨跌幅（来自行情接口，盘中 K 线未必已更新）
     */
    fun compute(
        bars: List<KLineBar>,
        indexBars: List<KLineBar>,
        liveTodayPct: Double
    ): Result {
        if (bars.size < 3 || indexBars.size < 3) return empty(liveTodayPct)

        val sPct = dailyPct(bars)
        val iPctMap = dailyPct(indexBars).toMap()
        if (sPct.isEmpty()) return empty(liveTodayPct)

        val today = LocalDate.now().toString()
        val lastDate = sPct.last().first
        val lastIsToday = lastDate == today

        // 不含当日的历史日涨跌幅；若 K 线最后一根不是今天，则只用它之前的历史
        val hist = if (lastIsToday) sPct.dropLast(1) else sPct
        val histDates = hist.takeLast(9)

        var pre9 = 0.0
        var used = 0
        histDates.forEach { (d, p) ->
            val ip = iPctMap[d]
            pre9 += p - (ip ?: 0.0)
            if (ip != null) used++
        }

        // 基准指数当日涨跌幅：优先取「今天」那根，没有则视为 0
        val idxTodayPct = iPctMap[today] ?: if (lastIsToday) (iPctMap[lastDate] ?: 0.0) else 0.0

        val dev10 = pre9 + (liveTodayPct - idxTodayPct)
        return Result(
            dev10 = dev10,
            pre9 = pre9,
            todayPct = liveTodayPct,
            needTodayPct = 100.0 - pre9,
            needDownPct = -50.0 - pre9,
            triggeredUp = dev10 >= 100.0,
            triggeredDown = dev10 <= -50.0,
            daysUsed = used.coerceAtMost(9),
            dataOk = used >= 9
        )
    }

    /** 距离两档阈值里更近的一档（用于决定卡片提示「涨幅」还是「跌幅」） */
    fun nearerGap(r: Result): Double = abs(100.0 - r.dev10).let { up ->
        val down = abs(r.dev10 + 50.0)
        minOf(up, down)
    }
}
