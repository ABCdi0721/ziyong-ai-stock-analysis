package com.dsa.mobile

import kotlin.math.max
import kotlin.math.min

/**
 * 碗口反弹因子引擎（候选 1）。
 *
 * 移植自 GitHub 高星仓库 Dzy-HW-XD/a-share-quant-selector（516★，MIT）的
 * BowlReboundStrategy「碗口反弹」，并按手机端可得数据（日K 开高低收量 + 快照）适配。
 *
 * 原始口径（README 策略说明）：
 *  1. 上升趋势：短期趋势线 > 多空线；
 *  2. 近 M 日内存在放量阳线：成交量 ≥ 前日 × N 倍且收阳（close > open）；
 *  3. 剔除：回顾期内成交量最大的一天若是阴线（大资金出逃），直接剔除；
 *  4. KDJ 低位：J 值处超卖区域；
 *  5. 市值 / 价格 / 成交额 / 当日涨幅做当日可买过滤。
 *
 * 与原 18 策略的差异：原「超跌反转」只看当日/60 日跌幅与位置，本引擎用
 * KDJ 超卖低位 + 放量阳线确认 + 最大量非阴量 + 趋势未破位 四重共振。
 */
object BowlRebound {

    /** 回溯窗口（原 M=20） */
    private const val LOOKBACK = 20

    /** 放量倍数（原 N=2.4）：当日量 ≥ 前一日量 × 2.4 视为放量 */
    private const val VOL_MULT = 2.4

    /** KDJ-J 低位阈值（原 J_VAL=0 偏极端，手机端放宽到超卖区 20 以避免样本过少） */
    private const val J_LOW = 20.0

    /** 趋势线周期：短期趋势线 MA14，多空线 MA28（原项目 M1=14 / M2=28） */
    private const val MA_SHORT = 14
    private const val MA_LONG = 28

    /** 因子命中结果；score 0-100，reasons 为命中依据（调试/展示用） */
    data class Hit(val score: Double, val reasons: List<String>)

    /**
     * 当日可买预筛参数（快照层过滤，避免逐只拉日K浪费流量）。
     * 当日涨幅留买入空间：不追涨停（<7%），允许小幅低开/回调。
     */
    fun prefilter(): ScreenStrategy = ScreenStrategy(
        name = "碗口反弹",
        techWeight = 0.5,
        priceMin = 3.0, priceMax = 200.0,
        mvMin = 20.0, peMax = 200.0,
        turnoverMin = 0.8,
        changeMin = -3.0, changeMax = 7.0,
        amountMin = 5e7
    )

    /**
     * 判定单只股票日K是否命中碗口反弹。
     * @return 命中返回 [Hit]（评分 + 依据）；未命中返回 null。
     */
    fun evaluate(bars: List<KLineBar>): Hit? {
        val n = bars.size
        if (n < 25) return null

        val close = DoubleArray(n) { bars[it].close }
        val open = DoubleArray(n) { bars[it].open }
        val high = DoubleArray(n) { bars[it].high }
        val low = DoubleArray(n) { bars[it].low }
        val vol = DoubleArray(n) { bars[it].volume.toDouble() }

        val latest = n - 1

        // ---- 条件 4：KDJ 低位超卖 ----
        val (_, _, jArr) = Indicators.kdj(high, low, close)
        val jLatest = jArr[latest]
        if (jLatest > J_LOW) return null

        // ---- 条件 1：短期趋势线在多空线上方（未破位） ----
        val maShort = Indicators.sma(close, MA_SHORT)
        val maLong = Indicators.sma(close, MA_LONG)
        val ms = maShort[latest]; val ml = maLong[latest]
        if (ms.isNaN() || ml.isNaN() || ms <= ml) return null

        // ---- 近 LOOKBACK 日窗口 ----
        val from = maxOf(1, n - LOOKBACK)

        // 条件 2：窗口内至少一根放量阳线（量 ≥ 前日 × 倍数且收阳）
        var multMax = 0.0
        var hasVolumeYang = false
        for (i in from..latest) {
            if (vol[i - 1] <= 0.0) continue
            val mult = vol[i] / vol[i - 1]
            val yang = close[i] > open[i]
            if (mult >= VOL_MULT && yang) {
                hasVolumeYang = true
                multMax = max(multMax, mult)
            }
        }
        if (!hasVolumeYang) return null

        // 条件 3：窗口内成交量最大的一天不能是阴线（剔除大资金出逃）
        var maxVolIdx = from
        var maxVol = vol[from]
        for (i in from..latest) {
            if (vol[i] > maxVol) { maxVol = vol[i]; maxVolIdx = i }
        }
        if (close[maxVolIdx] < open[maxVolIdx]) return null

        // ---- 评分 ----
        val reasons = mutableListOf<String>()

        // KDJ 低位深度（满分 40）：J≤0 满档，J=20 半档，J≥40 归零
        val jScore = when {
            jLatest <= 0.0 -> 40.0
            else -> maxOf(0.0, 40.0 - jLatest / J_LOW * 20.0)
        }
        reasons.add("KDJ-J=${String.format("%.1f", jLatest)} 超卖低位")

        // 放量阳线强度（满分 25）：2.4 倍起步给 15，4 倍及以上满档
        val volScore = when {
            multMax >= 4.0 -> 25.0
            else -> 15.0 + (multMax - VOL_MULT) / (4.0 - VOL_MULT) * 10.0
        }.coerceIn(0.0, 25.0)
        reasons.add("近${LOOKBACK}日放量阳线 ${String.format("%.1f", multMax)} 倍")

        // 趋势贴合（满分 20）：趋势线在上 10；价在 MA20 上方 5；短期线上翘 5
        val ma20 = Indicators.sma(close, 20)
        var trendScore = 10.0
        if (!ma20[latest].isNaN() && close[latest] >= ma20[latest]) trendScore += 5.0
        val prevMs = maShort[maxOf(0, latest - 5)]
        if (!prevMs.isNaN() && ms > prevMs) trendScore += 5.0
        trendScore = trendScore.coerceIn(0.0, 20.0)
        reasons.add("MA$MA_SHORT 站于 MA$MA_LONG 上方，趋势未破位")

        // 反弹确认（满分 15）：最新收阳 10；当日小幅上涨 0~3% 再加 5
        var reboundScore = 0.0
        if (close[latest] > open[latest]) reboundScore += 10.0
        val dayPct = if (close[latest - 1] > 0)
            (close[latest] - close[latest - 1]) / close[latest - 1] * 100.0 else 0.0
        if (dayPct in 0.0..3.0) reboundScore += 5.0
        reboundScore = reboundScore.coerceIn(0.0, 15.0)
        if (reboundScore > 0.0) reasons.add("当日反弹确认")

        val total = (jScore + volScore + trendScore + reboundScore).coerceIn(0.0, 100.0)
        return Hit(total, reasons)
    }

    /** 快照层 ST/退市/停牌/涨跌停等通用可买过滤（与其它短线引擎同口径） */
    fun tradable(st: ScreenStock): Boolean {
        if (st.price <= 0.0 || st.amount <= 0.0) return false
        if (st.name.contains("ST") || st.name.contains("退")) return false
        if (st.changePct >= 9.5 || st.changePct <= -9.5) return false
        return true
    }
}
