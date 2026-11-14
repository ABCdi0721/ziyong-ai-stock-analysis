package com.dsa.mobile

/**
 * 单股趋势分析引擎
 * 移植自 daily_stock_analysis/src/stock_analyzer.py + schemas/decision_scale.py
 */
object Analyzer {

    // 参数（与 Python 端一致）
    private const val VOLUME_SHRINK_RATIO = 0.7
    private const val VOLUME_HEAVY_RATIO = 1.5
    private const val MA_SUPPORT_TOLERANCE = 0.02
    private const val RSI_OVERBOUGHT = 70.0
    private const val RSI_OVERSOLD = 30.0
    private const val BIAS_THRESHOLD = 5.0

    // 状态中文名
    val TREND_ZH = mapOf(
        "STRONG_BULL" to "强势多头", "BULL" to "多头", "WEAK_BULL" to "弱势多头",
        "CONSOLIDATION" to "横盘整理", "WEAK_BEAR" to "弱势空头", "BEAR" to "空头", "STRONG_BEAR" to "强势空头"
    )
    val VOLUME_ZH = mapOf(
        "HEAVY_VOLUME_UP" to "放量上涨", "HEAVY_VOLUME_DOWN" to "放量下跌",
        "SHRINK_VOLUME_UP" to "缩量上涨", "SHRINK_VOLUME_DOWN" to "缩量回调", "NORMAL" to "量能正常"
    )
    val MACD_ZH = mapOf(
        "GOLDEN_CROSS_ZERO" to "零轴上金叉", "GOLDEN_CROSS" to "金叉", "CROSSING_UP" to "DIF上穿零轴",
        "BULLISH" to "多头", "BEARISH" to "空头", "CROSSING_DOWN" to "DIF下穿零轴", "DEATH_CROSS" to "死叉"
    )
    val RSI_ZH = mapOf(
        "OVERBOUGHT" to "超买", "STRONG_BUY" to "强势", "NEUTRAL" to "中性", "WEAK" to "弱势", "OVERSOLD" to "超卖"
    )
    val SIGNAL_ZH = mapOf(
        "STRONG_BUY" to "强烈买入", "BUY" to "买入", "WAIT" to "观望", "SELL" to "卖出", "STRONG_SELL" to "强烈卖出"
    )

    fun analyze(bars: List<KLineBar>, code: String): AnalysisResult {
        val n = bars.size
        if (n < 20) {
            return AnalysisResult(
                code, "CONSOLIDATION", "数据不足", 50.0,
                0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, "NORMAL", 1.0, "数据不足",
                false, false, 0.0, 0.0, 0.0, "BULLISH", "数据不足",
                50.0, 50.0, 50.0, "NEUTRAL", "数据不足",
                "WAIT", 0, emptyList(), listOf("数据不足，无法完成分析")
            )
        }

        val close = DoubleArray(n) { bars[it].close }
        val ma5 = Indicators.sma(close, 5)
        val ma10 = Indicators.sma(close, 10)
        val ma20 = Indicators.sma(close, 20)
        val ma60 = if (n >= 60) Indicators.sma(close, 60) else ma20
        val (dif, dea, bar) = Indicators.macd(close)
        val rsi6 = Indicators.rsi(close, 6)
        val rsi12 = Indicators.rsi(close, 12)
        val rsi24 = Indicators.rsi(close, 24)

        val latest = n - 1
        val price = close[latest]
        val m5 = ma5[latest]; val m10 = ma10[latest]; val m20 = ma20[latest]; val m60 = ma60[latest]

        // ===== 1. 趋势判断 =====
        var trendStatus = "CONSOLIDATION"; var maAlignment = "均线缠绕，趋势不明"; var trendStrength = 50.0
        val prevIdx = if (n >= 5) latest - 5 else latest
        if (m5 > m10 && m10 > m20) {
            val prevSpread = if (ma20[prevIdx] > 0) (ma5[prevIdx] - ma20[prevIdx]) / ma20[prevIdx] * 100 else 0.0
            val currSpread = if (m20 > 0) (m5 - m20) / m20 * 100 else 0.0
            if (currSpread > prevSpread && currSpread > 5) {
                trendStatus = "STRONG_BULL"; maAlignment = "强势多头排列，均线发散上行"; trendStrength = 90.0
            } else {
                trendStatus = "BULL"; maAlignment = "多头排列 MA5>MA10>MA20"; trendStrength = 75.0
            }
        } else if (m5 > m10 && m10 <= m20) {
            trendStatus = "WEAK_BULL"; maAlignment = "弱势多头，MA5>MA10 但 MA10≤MA20"; trendStrength = 55.0
        } else if (m5 < m10 && m10 < m20) {
            val prevSpread = if (ma5[prevIdx] > 0) (ma20[prevIdx] - ma5[prevIdx]) / ma5[prevIdx] * 100 else 0.0
            val currSpread = if (m5 > 0) (m20 - m5) / m5 * 100 else 0.0
            if (currSpread > prevSpread && currSpread > 5) {
                trendStatus = "STRONG_BEAR"; maAlignment = "强势空头排列，均线发散下行"; trendStrength = 10.0
            } else {
                trendStatus = "BEAR"; maAlignment = "空头排列 MA5<MA10<MA20"; trendStrength = 25.0
            }
        } else if (m5 < m10 && m10 >= m20) {
            trendStatus = "WEAK_BEAR"; maAlignment = "弱势空头，MA5<MA10 但 MA10≥MA20"; trendStrength = 40.0
        }

        // ===== 2. 乖离率 =====
        val bias5 = if (m5 > 0) (price - m5) / m5 * 100 else 0.0
        val bias10 = if (m10 > 0) (price - m10) / m10 * 100 else 0.0
        val bias20 = if (m20 > 0) (price - m20) / m20 * 100 else 0.0

        // ===== 3. 量能分析 =====
        var volumeStatus = "NORMAL"; var volumeTrend = "量能正常"; var volRatio = 1.0
        if (n >= 5) {
            var vol5Sum = 0.0
            for (i in (n - 6) until (n - 1)) vol5Sum += bars[i].volume
            val vol5Avg = vol5Sum / 5
            if (vol5Avg > 0) volRatio = bars[latest].volume / vol5Avg
            val priceChange = if (bars[latest - 1].close > 0)
                (bars[latest].close - bars[latest - 1].close) / bars[latest - 1].close * 100 else 0.0
            when {
                volRatio >= VOLUME_HEAVY_RATIO -> {
                    if (priceChange > 0) {
                        volumeStatus = "HEAVY_VOLUME_UP"; volumeTrend = "放量上涨，多头力量强劲"
                    } else {
                        volumeStatus = "HEAVY_VOLUME_DOWN"; volumeTrend = "放量下跌，注意风险"
                    }
                }
                volRatio <= VOLUME_SHRINK_RATIO -> {
                    if (priceChange > 0) {
                        volumeStatus = "SHRINK_VOLUME_UP"; volumeTrend = "缩量上涨，上攻动能不足"
                    } else {
                        volumeStatus = "SHRINK_VOLUME_DOWN"; volumeTrend = "缩量回调，洗盘特征明显（好）"
                    }
                }
            }
        }

        // ===== 4. 支撑压力 =====
        var supportMa5 = false; var supportMa10 = false
        val supportLevels = mutableListOf<Double>()
        val resistanceLevels = mutableListOf<Double>()
        if (m5 > 0 && abs(price - m5) / m5 <= MA_SUPPORT_TOLERANCE && price >= m5) {
            supportMa5 = true; supportLevels.add(m5)
        }
        if (m10 > 0 && abs(price - m10) / m10 <= MA_SUPPORT_TOLERANCE && price >= m10) {
            supportMa10 = true
            if (m10 !in supportLevels) supportLevels.add(m10)
        }
        if (m20 > 0 && price >= m20) supportLevels.add(m20)
        if (n >= 20) {
            var recentHigh = 0.0
            for (i in (n - 20) until n) recentHigh = maxOf(recentHigh, bars[i].high)
            if (recentHigh > price) resistanceLevels.add(recentHigh)
        }

        // ===== 5. MACD =====
        var macdStatus = "BULLISH"; var macdSignal = " MACD 中性区域"
        if (n >= 26) {
            val prevDifDea = dif[latest - 1] - dea[latest - 1]
            val currDifDea = dif[latest] - dea[latest]
            val isGolden = prevDifDea <= 0 && currDifDea > 0
            val isDeath = prevDifDea >= 0 && currDifDea < 0
            val isUpZero = prevDifDea <= 0 && dif[latest] > 0
            val isDownZero = prevDifDea >= 0 && dif[latest] < 0
            when {
                isGolden && dif[latest] > 0 -> { macdStatus = "GOLDEN_CROSS_ZERO"; macdSignal = "⭐ 零轴上金叉，强烈买入信号！" }
                isUpZero -> { macdStatus = "CROSSING_UP"; macdSignal = "⚡ DIF上穿零轴，趋势转强" }
                isGolden -> { macdStatus = "GOLDEN_CROSS"; macdSignal = "✅ 金叉，趋势向上" }
                isDeath -> { macdStatus = "DEATH_CROSS"; macdSignal = "❌ 死叉，趋势向下" }
                isDownZero -> { macdStatus = "CROSSING_DOWN"; macdSignal = "⚠️ DIF下穿零轴，趋势转弱" }
                dif[latest] > 0 && dea[latest] > 0 -> { macdStatus = "BULLISH"; macdSignal = "✓ 多头排列，持续上涨" }
                dif[latest] < 0 && dea[latest] < 0 -> { macdStatus = "BEARISH"; macdSignal = "⚠ 空头排列，持续下跌" }
            }
        } else {
            macdSignal = "数据不足"
        }

        // ===== 6. RSI =====
        var rsiStatus = "NEUTRAL"; var rsiSignal = " RSI中性"
        val r6 = rsi6[latest]; val r12 = rsi12[latest]; val r24 = rsi24[latest]
        if (n < 24) {
            rsiSignal = "数据不足"
        } else {
            when {
                r12 > RSI_OVERBOUGHT -> { rsiStatus = "OVERBOUGHT"; rsiSignal = "⚠️ RSI超买(${fmt1(r12)}>70)，短期回调风险高" }
                r12 > 60 -> { rsiStatus = "STRONG_BUY"; rsiSignal = "✅ RSI强势(${fmt1(r12)})，多头力量充足" }
                r12 >= 40 -> { rsiStatus = "NEUTRAL"; rsiSignal = " RSI中性(${fmt1(r12)})，震荡整理中" }
                r12 >= RSI_OVERSOLD -> { rsiStatus = "WEAK"; rsiSignal = "⚡ RSI弱势(${fmt1(r12)})，关注反弹" }
                else -> { rsiStatus = "OVERSOLD"; rsiSignal = "⭐ RSI超卖(${fmt1(r12)}<30)，反弹机会大" }
            }
        }

        // ===== 7. 综合评分 =====
        var score = 0
        val reasons = mutableListOf<String>()
        val risks = mutableListOf<String>()

        // 趋势 30 分
        val trendScores = mapOf(
            "STRONG_BULL" to 30, "BULL" to 26, "WEAK_BULL" to 18, "CONSOLIDATION" to 12,
            "WEAK_BEAR" to 8, "BEAR" to 4, "STRONG_BEAR" to 0
        )
        score += trendScores[trendStatus] ?: 12
        if (trendStatus == "STRONG_BULL" || trendStatus == "BULL") {
            reasons.add("✅ ${TREND_ZH[trendStatus]}，顺势做多")
        } else if (trendStatus == "BEAR" || trendStatus == "STRONG_BEAR") {
            risks.add("⚠️ ${TREND_ZH[trendStatus]}，不宜做多")
        }

        // 乖离率 20 分
        val strongTrend = trendStatus == "STRONG_BULL" && trendStrength >= 70
        val effectiveThreshold = if (strongTrend) BIAS_THRESHOLD * 1.5 else BIAS_THRESHOLD
        val bias = bias5
        when {
            bias < 0 -> {
                if (bias > -3) { score += 20; reasons.add("✅ 价格略低于MA5(${fmt1(bias)}%)，回踩买点") }
                else if (bias > -5) { score += 16; reasons.add("✅ 价格回踩MA5(${fmt1(bias)}%)，观察支撑") }
                else { score += 8; risks.add("⚠️ 乖离率过大(${fmt1(bias)}%)，可能破位") }
            }
            bias < 2 -> { score += 18; reasons.add("✅ 价格贴近MA5(${fmt1(bias)}%)，介入好时机") }
            bias < BIAS_THRESHOLD -> { score += 14; reasons.add("⚡ 价格略高于MA5(${fmt1(bias)}%)，可小仓介入") }
            bias > effectiveThreshold -> { score += 4; risks.add("❌ 乖离率过高(${fmt1(bias)}%>${fmt1(effectiveThreshold)}%)，严禁追高！") }
            strongTrend -> { score += 10; reasons.add("⚡ 强势趋势中乖离率偏高(${fmt1(bias)}%)，可轻仓追踪") }
            else -> { score += 4; risks.add("❌ 乖离率过高(${fmt1(bias)}%>${fmt1(BIAS_THRESHOLD)}%)，严禁追高！") }
        }

        // 量能 15 分
        val volumeScores = mapOf(
            "SHRINK_VOLUME_DOWN" to 15, "HEAVY_VOLUME_UP" to 12, "NORMAL" to 10,
            "SHRINK_VOLUME_UP" to 6, "HEAVY_VOLUME_DOWN" to 0
        )
        score += volumeScores[volumeStatus] ?: 8
        if (volumeStatus == "SHRINK_VOLUME_DOWN") reasons.add("✅ 缩量回调，主力洗盘")
        else if (volumeStatus == "HEAVY_VOLUME_DOWN") risks.add("⚠️ 放量下跌，注意风险")

        // 支撑 10 分
        if (supportMa5) { score += 5; reasons.add("✅ MA5支撑有效") }
        if (supportMa10) { score += 5; reasons.add("✅ MA10支撑有效") }

        // MACD 15 分
        val macdScores = mapOf(
            "GOLDEN_CROSS_ZERO" to 15, "GOLDEN_CROSS" to 12, "CROSSING_UP" to 10, "BULLISH" to 8,
            "BEARISH" to 2, "CROSSING_DOWN" to 0, "DEATH_CROSS" to 0
        )
        score += macdScores[macdStatus] ?: 5
        if (macdStatus == "GOLDEN_CROSS_ZERO" || macdStatus == "GOLDEN_CROSS") reasons.add("✅ $macdSignal")
        else if (macdStatus == "DEATH_CROSS" || macdStatus == "CROSSING_DOWN") risks.add("⚠️ $macdSignal")
        else reasons.add(macdSignal)

        // RSI 10 分
        val rsiScores = mapOf(
            "OVERSOLD" to 10, "STRONG_BUY" to 8, "NEUTRAL" to 5, "WEAK" to 3, "OVERBOUGHT" to 0
        )
        score += rsiScores[rsiStatus] ?: 5
        if (rsiStatus == "OVERSOLD" || rsiStatus == "STRONG_BUY") reasons.add("✅ $rsiSignal")
        else if (rsiStatus == "OVERBOUGHT") risks.add("⚠️ $rsiSignal")
        else reasons.add(rsiSignal)

        // ===== 8. 买入信号（decision_scale 分档）=====
        val signalKey = when {
            score in 80..100 -> "strong_buy"
            score in 60..79 -> "buy"
            score in 40..59 -> "watch"
            score in 20..39 -> "reduce"
            else -> "sell"
        }
        val buySignal = when {
            signalKey == "strong_buy" && trendStatus in setOf("STRONG_BULL", "BULL") -> "STRONG_BUY"
            signalKey in setOf("strong_buy", "buy") && trendStatus in setOf("STRONG_BULL", "BULL", "WEAK_BULL") -> "BUY"
            signalKey in setOf("strong_buy", "buy") && trendStatus in setOf("CONSOLIDATION", "WEAK_BEAR") -> "WAIT"
            signalKey == "watch" -> "WAIT"
            signalKey == "sell" || trendStatus in setOf("BEAR", "STRONG_BEAR") -> "STRONG_SELL"
            else -> "SELL"
        }

        return AnalysisResult(
            code = code,
            trendStatus = trendStatus, maAlignment = maAlignment, trendStrength = trendStrength,
            ma5 = m5, ma10 = m10, ma20 = m20, ma60 = m60, currentPrice = price,
            biasMa5 = bias5, biasMa10 = bias10, biasMa20 = bias20,
            volumeStatus = volumeStatus, volumeRatio5d = volRatio, volumeTrend = volumeTrend,
            supportMa5 = supportMa5, supportMa10 = supportMa10,
            macdDif = dif[latest], macdDea = dea[latest], macdBar = bar[latest],
            macdStatus = macdStatus, macdSignal = macdSignal,
            rsi6 = r6, rsi12 = r12, rsi24 = r24, rsiStatus = rsiStatus, rsiSignal = rsiSignal,
            buySignal = buySignal, signalScore = score,
            signalReasons = reasons, riskFactors = risks
        )
    }

    /** 构造 LLM 分析用的数据摘要 */
    fun buildAnalysisSummary(code: String, name: String, q: Quote?, r: AnalysisResult): String {
        val sb = StringBuilder()
        sb.appendLine("股票：$name（$code）")
        q?.let {
            sb.appendLine("最新价：${fmt2(it.price)} 元，涨跌幅：${fmt2(it.changePct)}%，今开 ${fmt2(it.open)}，最高 ${fmt2(it.high)}，最低 ${fmt2(it.low)}，昨收 ${fmt2(it.prevClose)}")
            sb.appendLine("成交量：${it.volume} 手，成交额：${(it.amount / 1e8).let { v -> fmt2(v) }} 亿元，换手率：${fmt2(it.turnover)}%，量比：${fmt2(it.volumeRatio)}")
            if (it.pe > 0) sb.appendLine("市盈率(TTM)：${fmt2(it.pe)}，市净率：${fmt2(it.pb)}，总市值：${fmt2(it.totalMv)} 亿元")
        }
        sb.appendLine("--- 技术指标 ---")
        sb.appendLine("趋势状态：${TREND_ZH[r.trendStatus]}（${r.maAlignment}），趋势强度 ${r.trendStrength.toInt()}/100")
        sb.appendLine("现价 ${fmt2(r.currentPrice)}，MA5=${fmt2(r.ma5)}（乖离 ${fmt2(r.biasMa5)}%），MA10=${fmt2(r.ma10)}（乖离 ${fmt2(r.biasMa10)}%），MA20=${fmt2(r.ma20)}（乖离 ${fmt2(r.biasMa20)}%）")
        sb.appendLine("量能：${VOLUME_ZH[r.volumeStatus]}（${r.volumeTrend}），量比(5日) ${fmt2(r.volumeRatio5d)}")
        sb.appendLine("MACD：${MACD_ZH[r.macdStatus]}，DIF=${fmt2(r.macdDif)}，DEA=${fmt2(r.macdDea)}，柱=${fmt2(r.macdBar)}；${r.macdSignal}")
        sb.appendLine("RSI：RSI6=${fmt1(r.rsi6)}，RSI12=${fmt1(r.rsi12)}，RSI24=${fmt1(r.rsi24)}（${RSI_ZH[r.rsiStatus]}）；${r.rsiSignal}")
        sb.appendLine("综合评分：${r.signalScore}/100，信号：${SIGNAL_ZH[r.buySignal]}")
        sb.appendLine("看多理由：${r.signalReasons.joinToString("；")}")
        sb.appendLine("风险因素：${r.riskFactors.joinToString("；")}")
        return sb.toString()
    }

    private fun abs(v: Double) = if (v < 0) -v else v
    private fun fmt1(v: Double) = String.format("%.1f", v)
    private fun fmt2(v: Double) = String.format("%.2f", v)
}
