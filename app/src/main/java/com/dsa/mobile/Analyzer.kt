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
    val REGIME_ZH = mapOf(
        "strong_trend" to "强趋势", "weak_trend" to "弱趋势", "ranging" to "震荡", "transition" to "过渡"
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
        val high = DoubleArray(n) { bars[it].high }
        val low = DoubleArray(n) { bars[it].low }
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

        // ===== v2.12.30 突破/整理/波动复核（ATR / 唐奇安 / 近4日振幅 / NR7）=====
        val atrArr = if (n >= 14) Indicators.atr(high, low, close, 14) else DoubleArray(n) { Double.NaN }
        val atr14 = if (!atrArr[latest].isNaN()) atrArr[latest] else 0.0
        val dc = if (n >= 20) Indicators.donchian(high, low, 20)
        else Pair(DoubleArray(n) { Double.NaN }, DoubleArray(n) { Double.NaN })
        val dcHigh = if (!dc.first[latest].isNaN()) dc.first[latest] else 0.0
        val dcLow = if (!dc.second[latest].isNaN()) dc.second[latest] else 0.0

        val win4 = minOf(4, n)
        var h4 = Double.MIN_VALUE; var l4 = Double.MAX_VALUE
        for (i in n - win4 until n) { h4 = maxOf(h4, high[i]); l4 = minOf(l4, low[i]) }
        val range4d = if (l4 > 0) (h4 - l4) / l4 * 100 else 0.0

        val win7 = minOf(7, n)
        val dayRanges = DoubleArray(win7) { i -> val idx = n - win7 + i; high[idx] - low[idx] }
        val todayRange = dayRanges.last()
        val nr7 = win7 >= 7 && dayRanges.all { it >= todayRange - 1e-9 }

        val nearBreakout = dcHigh > 0 && price >= dcHigh * 0.95
        val tightRange = range4d <= 12.0
        val stopRef = minOf(if (m10 > 0) m10 else price, l4)

        // ===== v2.12.33 OpenBB 移植指标（ADX/DI/Chop/Regime/Aroon/量价流/波动/Clenow/Hurst/突破/交叉/风险调整）=====
        val volArr = LongArray(n) { bars[it].volume }
        val adxTriple = if (n >= 15) Indicators.adx(high, low, close, 14)
            else Triple(DoubleArray(n) { Double.NaN }, DoubleArray(n) { Double.NaN }, DoubleArray(n) { Double.NaN })
        val adx14 = if (!adxTriple.first[latest].isNaN()) adxTriple.first[latest] else 0.0
        val plusDi = if (!adxTriple.second[latest].isNaN()) adxTriple.second[latest] else 0.0
        val minusDi = if (!adxTriple.third[latest].isNaN()) adxTriple.third[latest] else 0.0
        val chopArr = if (n >= 14) Indicators.choppiness(high, low, close, 14) else DoubleArray(n) { Double.NaN }
        val chop14 = if (!chopArr[latest].isNaN()) chopArr[latest] else 0.0

        // Regime 四态（OpenBB regime 口径：ADX>25 且 Chop<38.2 强趋势；ADX>12.5 弱趋势；Chop>61.8 震荡；其余过渡）
        val regime = when {
            adx14 > 25 && chop14 > 0 && chop14 < 38.2 -> "strong_trend"
            adx14 > 12.5 -> "weak_trend"
            chop14 > 61.8 -> "ranging"
            adx14 > 0 || chop14 > 0 -> "transition"
            else -> ""
        }

        val aroonTriple = if (n >= 25) Indicators.aroon(high, low, 25)
            else Triple(DoubleArray(n) { Double.NaN }, DoubleArray(n) { Double.NaN }, DoubleArray(n) { Double.NaN })
        val aroonUp = if (!aroonTriple.first[latest].isNaN()) aroonTriple.first[latest] else 0.0
        val aroonDown = if (!aroonTriple.second[latest].isNaN()) aroonTriple.second[latest] else 0.0

        val obvArr = Indicators.obv(close, volArr)
        val adArr = Indicators.adLine(high, low, close, volArr)
        val adoscArr = Indicators.adosc(adArr)
        val adosc = adoscArr[latest]
        val obvSlope = if (n >= 6) {
            val d = obvArr[latest] - obvArr[latest - 5]
            val base = maxOf(abs(obvArr[latest - 5]), 1.0)
            (d / base).coerceIn(-1.0, 1.0)
        } else 0.0
        val vwapArr = Indicators.vwap(high, low, close, volArr)
        val vwap = if (!vwapArr[latest].isNaN()) vwapArr[latest] else 0.0

        val rvArr = Indicators.realizedVolatility(close, 30)
        val annVol = if (!rvArr[latest].isNaN()) rvArr[latest] * 100 else 0.0
        val pkArr = Indicators.parkinsonVolatility(high, low, 30)
        val parkinsonVol = if (!pkArr[latest].isNaN()) pkArr[latest] * 100 else 0.0

        val clenow = Indicators.clenow(close, 90)
        val clenowFactor = if (!clenow.factor.isNaN()) clenow.factor else 0.0
        val clenowR2 = if (!clenow.r2.isNaN()) clenow.r2 else 0.0
        val hurstV = Indicators.hurst(close)
        val hurst = if (!hurstV.isNaN()) hurstV else 0.0

        val brk = Indicators.breakoutSignal(high, low, close, 20)
        val breakoutDir = brk?.direction ?: "none"
        val breakoutMag = brk?.magnitude ?: 0.0
        val breakoutBars = brk?.barsInRange ?: 0

        val cross = Indicators.maCross(ma5, ma20)
        val maCrossDir = cross?.direction ?: "none"
        val maCrossDist = cross?.distance ?: 0.0

        val rs = Indicators.returnsStats(close, 60)
        val sharpe = if (!rs.sharpe.isNaN()) rs.sharpe else 0.0
        val sortino = if (!rs.sortino.isNaN()) rs.sortino else 0.0
        val calmar = if (!rs.calmar.isNaN()) rs.calmar else 0.0
        val maxDrawdown = if (!rs.maxDrawdown.isNaN()) rs.maxDrawdown * 100 else 0.0
        val var95 = if (!rs.var95.isNaN()) rs.var95 * 100 else 0.0

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

        // ADX 连续趋势强度融合：均线档与 ADX 各半，ADX 无效时保持均线档
        if (adx14 > 0) trendStrength = (trendStrength * 0.5 + adx14 * 0.5).coerceIn(0.0, 100.0)

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

        // ===== v2.12.30 突破/整理加分 + 高波动扣分 =====
        var breakoutBonus = 0
        if (nearBreakout && tightRange) {
            breakoutBonus += 6; reasons.add("✅ 临近20日高点且窄幅整理，突破候选")
        } else if (nearBreakout) {
            breakoutBonus += 3; reasons.add("⚡ 临近20日高点，关注突破")
        }
        if (tightRange && !nearBreakout) {
            breakoutBonus += 3; reasons.add("✅ 近4日振幅收敛，整理充分")
        }
        if (nr7) reasons.add("✅ NR7 窄幅日，次日留意方向选择")
        var rangePenalty = 0
        if (range4d > 15.0) {
            rangePenalty += 6; risks.add("⚠️ 近4日振幅 ${fmt1(range4d)}%>15%，波动过大")
        }

        // ===== v2.12.33 OpenBB 移植：Regime 闸门 / 量价配合 / 波动 / 突破新鲜度 / 风险调整 =====
        var openbbBonus = 0
        var openbbPenalty = 0
        when (regime) {
            "strong_trend" -> { openbbBonus += 3; reasons.add("✅ 强趋势状态（ADX ${fmt1(adx14)}，Chop ${fmt1(chop14)}）") }
            "weak_trend" -> reasons.add("⚡ 弱趋势状态（ADX ${fmt1(adx14)}）")
            "ranging" -> { openbbPenalty += 3; reasons.add("⚡ 震荡整理（Chop ${fmt1(chop14)}>61.8），突破信号需打折") }
            "transition" -> reasons.add("⚡ 趋势过渡状态，方向待确认")
        }
        if (obvSlope > 0.1 && adosc > 0) {
            openbbBonus += 2; reasons.add("✅ 量价资金流入（OBV/ADOSC 同向向上）")
        } else if (obvSlope < -0.1 && n >= 2 && close[latest] > close[latest - 1]) {
            openbbPenalty += 3; risks.add("⚠️ 价涨量能背离（OBV 下行）")
        }
        if (annVol > 45.0) { openbbPenalty += 3; risks.add("⚠️ 年化波动 ${fmt1(annVol)}% 偏高，注意回撤") }
        if (breakoutDir == "upside" && breakoutMag > 0) {
            if (breakoutBars <= 3) { openbbBonus += 3; reasons.add("✅ 突破20日高点，幅度 ${fmt2(breakoutMag)} 元（${breakoutBars} 根K线内）") }
            else reasons.add("⚡ 已突破20日高点 ${breakoutBars} 根K线，追高需谨慎")
        } else if (breakoutDir == "downside" && breakoutMag < 0) {
            openbbPenalty += 3; risks.add("⚠️ 跌破20日低点，幅度 ${fmt2(breakoutMag)} 元")
        }
        if (sharpe > 1.0) reasons.add("✅ 近60日夏普 ${fmt2(sharpe)}，风险调整后收益较好")
        if (maxDrawdown < -25.0) { openbbPenalty += 2; risks.add("⚠️ 近60日最大回撤 ${fmt1(maxDrawdown)}%") }
        if (var95 < -5.0) risks.add("⚠️ Var95 日收益 ${fmt1(var95)}%，尾部风险偏高")
        if (clenowR2 > 0.3) {
            if (clenowFactor > 0.05) reasons.add("✅ Clenow 动量 ${fmt2(clenowFactor)}（R²=${fmt2(clenowR2)}）")
            else if (clenowFactor < -0.05) risks.add("⚠️ Clenow 动量走弱 ${fmt2(clenowFactor)}（R²=${fmt2(clenowR2)}）")
        }
        if (hurst > 0.55) reasons.add("✅ Hurst ${fmt2(hurst)}，趋势延续性较强")
        else if (hurst > 0 && hurst < 0.45) reasons.add("⚡ Hurst ${fmt2(hurst)}，偏均值回归，勿追单边")
        if (aroonUp > 70 && aroonDown < 30) reasons.add("✅ Aroon 新高动能占优（Up ${fmt1(aroonUp)}/Down ${fmt1(aroonDown)}）")
        else if (aroonDown > 70 && aroonUp < 30) risks.add("⚠️ Aroon 下行动能占优（Down ${fmt1(aroonDown)}）")

        score = (score + breakoutBonus - rangePenalty + openbbBonus - openbbPenalty).coerceIn(0, 100)

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
            signalReasons = reasons, riskFactors = risks,
            atr14 = atr14, donchianHigh20 = dcHigh, donchianLow20 = dcLow,
            range4d = range4d, nr7 = nr7, nearBreakout = nearBreakout,
            tightRange = tightRange, stopRef = stopRef,
            adx14 = adx14, plusDi = plusDi, minusDi = minusDi, chop14 = chop14, regime = regime,
            aroonUp = aroonUp, aroonDown = aroonDown, obvSlope = obvSlope, adosc = adosc, vwap = vwap,
            annVol = annVol, parkinsonVol = parkinsonVol, clenowFactor = clenowFactor, clenowR2 = clenowR2,
            hurst = hurst, breakoutDir = breakoutDir, breakoutMag = breakoutMag, breakoutBars = breakoutBars,
            maCrossDir = maCrossDir, maCrossDist = maCrossDist, sharpe = sharpe, sortino = sortino,
            calmar = calmar, maxDrawdown = maxDrawdown, var95 = var95
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
        if (r.regime.isNotEmpty()) sb.appendLine("状态：${REGIME_ZH[r.regime] ?: r.regime}，ADX=${fmt1(r.adx14)}，+DI=${fmt1(r.plusDi)}，−DI=${fmt1(r.minusDi)}，Chop=${fmt1(r.chop14)}")
        sb.appendLine("量价流：OBV斜率=${fmt2(r.obvSlope)}，ADOSC=${fmt2(r.adosc)}，VWAP=${fmt2(r.vwap)}；年化波动=${fmt1(r.annVol)}%，Parkinson=${fmt1(r.parkinsonVol)}%")
        sb.appendLine("动量/结构：Clenow=${fmt2(r.clenowFactor)}（R²=${fmt2(r.clenowR2)}），Hurst=${fmt2(r.hurst)}，Aroon Up/Down=${fmt1(r.aroonUp)}/${fmt1(r.aroonDown)}")
        sb.appendLine("风险调整：夏普=${fmt2(r.sharpe)}，索诺=${fmt2(r.sortino)}，卡玛=${fmt2(r.calmar)}，最大回撤=${fmt1(r.maxDrawdown)}%，Var95=${fmt1(r.var95)}%")
        sb.appendLine("综合评分：${r.signalScore}/100，信号：${SIGNAL_ZH[r.buySignal]}")
        sb.appendLine("看多理由：${r.signalReasons.joinToString("；")}")
        sb.appendLine("风险因素：${r.riskFactors.joinToString("；")}")
        return sb.toString()
    }

    private fun abs(v: Double) = if (v < 0) -v else v
    private fun fmt1(v: Double) = String.format("%.1f", v)
    private fun fmt2(v: Double) = String.format("%.2f", v)
}
