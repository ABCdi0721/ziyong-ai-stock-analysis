package com.dsa.mobile

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** 技术指标计算（移植自 daily_stock_analysis，口径与 pandas 一致） */
object Indicators {

    /** 简单移动平均数组（窗口不满时返回 NaN） */
    fun sma(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    /** EMA，adjust=false，alpha=1/span，与 pandas ewm(span=.., adjust=false) 一致 */
    fun ema(values: DoubleArray, span: Int): DoubleArray {
        val out = DoubleArray(values.size)
        val alpha = 1.0 / span
        out[0] = values[0]
        for (i in 1 until values.size) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
        }
        return out
    }

    /** MACD：DIF=EMA12-EMA26，DEA=EMA(DIF,9)，BAR=(DIF-DEA)*2 */
    fun macd(close: DoubleArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val emaFast = ema(close, 12)
        val emaSlow = ema(close, 26)
        val dif = DoubleArray(close.size) { emaFast[it] - emaSlow[it] }
        val dea = ema(dif, 9)
        val bar = DoubleArray(close.size) { (dif[it] - dea[it]) * 2 }
        return Triple(dif, dea, bar)
    }

    /**
     * RSI（Wilder SMMA，与 pandas 一致）：
     * gain[0]=0；avgGain[t] = alpha*gain[t] + (1-alpha)*avgGain[t-1]，alpha=1/period
     * RSI = 100 - 100/(1+avgGain/avgLoss)；0/0 → 50
     */
    fun rsi(close: DoubleArray, period: Int): DoubleArray {
        val n = close.size
        val out = DoubleArray(n)
        val alpha = 1.0 / period
        var avgGain = 0.0
        var avgLoss = 0.0
        for (t in 0 until n) {
            if (t > 0) {
                val d = close[t] - close[t - 1]
                val gain = if (d > 0) d else 0.0
                val loss = if (d < 0) -d else 0.0
                avgGain = alpha * gain + (1 - alpha) * avgGain
                avgLoss = alpha * loss + (1 - alpha) * avgLoss
            }
            val rs = avgGain / avgLoss
            out[t] = if (rs.isNaN()) 50.0 else 100.0 - 100.0 / (1.0 + rs)
        }
        return out
    }

    /** BOLL(20,2)：中轨=SMA20，上下轨=中轨±2*std（总体标准差，ddof=0） */
    fun boll(close: DoubleArray, period: Int = 20, k: Double = 2.0):
        Triple<DoubleArray, DoubleArray, DoubleArray> {
        val n = close.size
        val mid = sma(close, period)
        val up = DoubleArray(n) { Double.NaN }
        val low = DoubleArray(n) { Double.NaN }
        for (i in period - 1 until n) {
            val mean = mid[i]
            var sq = 0.0
            for (j in i - period + 1..i) sq += (close[j] - mean) * (close[j] - mean)
            val sd = Math.sqrt(sq / period)
            up[i] = mean + k * sd
            low[i] = mean - k * sd
        }
        return Triple(mid, up, low)
    }

    /** KDJ(9,3,3)：RSV → K/D/J */
    fun kdj(high: DoubleArray, low: DoubleArray, close: DoubleArray, n: Int = 9, m1: Int = 3, m2: Int = 3):
        Triple<DoubleArray, DoubleArray, DoubleArray> {
        val size = close.size
        val k = DoubleArray(size)
        val d = DoubleArray(size)
        val j = DoubleArray(size)
        var prevK = 50.0
        var prevD = 50.0
        for (i in 0 until size) {
            var hh = Double.MIN_VALUE
            var ll = Double.MAX_VALUE
            for (j2 in maxOf(0, i - n + 1)..i) {
                hh = maxOf(hh, high[j2])
                ll = minOf(ll, low[j2])
            }
            val rsv = if (hh > ll) (close[i] - ll) / (hh - ll) * 100.0 else 50.0
            prevK = (m1 - 1.0) / m1 * prevK + rsv / m1
            prevD = (m2 - 1.0) / m2 * prevD + prevK / m2
            k[i] = prevK
            d[i] = prevD
            j[i] = 3 * prevK - 2 * prevD
        }
        return Triple(k, d, j)
    }

    /** BBI：多空指标 (MA3+MA6+MA12+MA24)/4 */
    fun bbi(close: DoubleArray): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        val a = sma(close, 3)
        val b = sma(close, 6)
        val c = sma(close, 12)
        val d = sma(close, 24)
        for (i in 0 until n) {
            if (!a[i].isNaN() && !d[i].isNaN()) out[i] = (a[i] + b[i] + c[i] + d[i]) / 4.0
        }
        return out
    }

    /**
     * ATR（Wilder 平滑，口径同 RSI 的 SMMA）：
     * TR[t] = max(high−low, |high−prevClose|, |low−prevClose|)；
     * 首值取前 period 根 TR 简单均值，之后 ATR[t] = (ATR[t−1]·(period−1) + TR[t]) / period。
     * 用于止损位与波动度量；窗口不足处返回 NaN。
     */
    fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 14): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        if (n < period) return out
        val tr = DoubleArray(n)
        for (i in 0 until n) {
            val h = high[i]; val l = low[i]
            tr[i] = if (i == 0) h - l else {
                val pc = close[i - 1]
                maxOf(h - l, abs(h - pc), abs(l - pc))
            }
        }
        var sum = 0.0
        for (i in 0 until period) sum += tr[i]
        var prev = sum / period
        out[period - 1] = prev
        for (i in period until n) {
            prev = (prev * (period - 1) + tr[i]) / period
            out[i] = prev
        }
        return out
    }

    /**
     * 唐奇安通道：上轨 = 近 period 根最高价的最高值，下轨 = 近 period 根最低价的最低值。
     * 用于临近突破（现价距上轨 ≤5%）与支撑位判断；窗口不足处返回 NaN。
     */
    fun donchian(high: DoubleArray, low: DoubleArray, period: Int = 20): Pair<DoubleArray, DoubleArray> {
        val n = high.size
        val upper = DoubleArray(n) { Double.NaN }
        val lower = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            val start = max(0, i - period + 1)
            var hh = Double.MIN_VALUE
            var ll = Double.MAX_VALUE
            for (j in start..i) {
                hh = max(hh, high[j])
                ll = min(ll, low[j])
            }
            if (i >= period - 1) {
                upper[i] = hh
                lower[i] = ll
            }
        }
        return upper to lower
    }

    // ============ v2.12.33 移植自 OpenBB technical / quantitative ============

    /** Wilder 平滑序列：首值前 period 根简单均值，之后 prev + (cur − prev)/period；窗口不足返回 NaN */
    private fun wilderSmooth(src: DoubleArray, period: Int): DoubleArray {
        val n = src.size
        val out = DoubleArray(n) { Double.NaN }
        if (n < period) return out
        var prev = 0.0
        for (i in 0 until period) prev += src[i]
        prev /= period
        out[period - 1] = prev
        for (i in period until n) {
            prev = prev + (src[i] - prev) / period
            out[i] = prev
        }
        return out
    }

    /**
     * ADX(14) + 方向指标（Wilder 平滑）。返回 Triple(adx, +DI, −DI)。
     * 口径对齐 pandas_ta ADX：TR/+DM/−DM 先 Wilder 平滑，DI=100·DMsmooth/TRsmooth，
     * DX=100·|+DI−−DI|/(+DI+−DI)，ADX 为 DX 的 Wilder 平滑。
     */
    fun adx(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 14):
        Triple<DoubleArray, DoubleArray, DoubleArray> {
        val n = close.size
        val adxArr = DoubleArray(n) { Double.NaN }
        val plusDi = DoubleArray(n) { Double.NaN }
        val minusDi = DoubleArray(n) { Double.NaN }
        if (n < period + 1) return Triple(adxArr, plusDi, minusDi)

        val tr = DoubleArray(n)
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)
        for (i in 0 until n) {
            val h = high[i]; val l = low[i]
            tr[i] = if (i == 0) h - l else {
                val pc = close[i - 1]
                maxOf(h - l, abs(h - pc), abs(l - pc))
            }
            if (i == 0) { plusDm[i] = 0.0; minusDm[i] = 0.0 } else {
                val upMove = high[i] - high[i - 1]
                val downMove = low[i - 1] - low[i]
                plusDm[i] = if (upMove > downMove && upMove > 0) upMove else 0.0
                minusDm[i] = if (downMove > upMove && downMove > 0) downMove else 0.0
            }
        }
        val trS = wilderSmooth(tr, period)
        val pdmS = wilderSmooth(plusDm, period)
        val mdmS = wilderSmooth(minusDm, period)
        val dx = DoubleArray(n) { Double.NaN }
        for (i in period - 1 until n) {
            val trv = trS[i]; val pd = pdmS[i]; val md = mdmS[i]
            if (trv <= 0) continue
            val pdi = 100.0 * pd / trv
            val mdi = 100.0 * md / trv
            plusDi[i] = pdi; minusDi[i] = mdi
            val denom = pdi + mdi
            dx[i] = if (denom > 0) 100.0 * abs(pdi - mdi) / denom else 0.0
        }
        // ADX = DX 的 Wilder 平滑（首值取前 period 个有效 DX 均值）
        var started = false; var seed = 0.0; var seedCount = 0; var prevAdx = 0.0
        for (i in period - 1 until n) {
            if (dx[i].isNaN()) continue
            if (!started) {
                seed += dx[i]; seedCount++
                if (seedCount == period) { prevAdx = seed / period; adxArr[i] = prevAdx; started = true }
            } else {
                prevAdx = prevAdx + (dx[i] - prevAdx) / period
                adxArr[i] = prevAdx
            }
        }
        return Triple(adxArr, plusDi, minusDi)
    }

    /**
     * Choppiness Index(14)：100·ln(ΣTR/(HH−LL))/ln(period)。
     * >61.8 震荡，<38.2 趋势；窗口不足返回 NaN。
     */
    fun choppiness(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 14): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        if (n < period) return out
        val tr = DoubleArray(n)
        for (i in 0 until n) {
            val h = high[i]; val l = low[i]
            tr[i] = if (i == 0) h - l else {
                val pc = close[i - 1]
                maxOf(h - l, abs(h - pc), abs(l - pc))
            }
        }
        val lnP = ln(period.toDouble())
        for (i in period - 1 until n) {
            var sumTr = 0.0; var hh = Double.MIN_VALUE; var ll = Double.MAX_VALUE
            for (j in i - period + 1..i) {
                sumTr += tr[j]; hh = maxOf(hh, high[j]); ll = minOf(ll, low[j])
            }
            val range = hh - ll
            if (range > 0 && sumTr > 0) out[i] = 100.0 * ln(sumTr / range) / lnP
        }
        return out
    }

    /** Aroon(25)：返回 Triple(aroonUp, aroonDown, oscillator=up−down)，取值 0..100 */
    fun aroon(high: DoubleArray, low: DoubleArray, period: Int = 25):
        Triple<DoubleArray, DoubleArray, DoubleArray> {
        val n = high.size
        val up = DoubleArray(n) { Double.NaN }
        val down = DoubleArray(n) { Double.NaN }
        val osc = DoubleArray(n) { Double.NaN }
        if (n < period) return Triple(up, down, osc)
        for (i in period - 1 until n) {
            var hiIdx = i; var loIdx = i
            var hi = high[i]; var lo = low[i]
            for (j in i - period + 1..i) {
                if (high[j] > hi) { hi = high[j]; hiIdx = j }
                if (low[j] < lo) { lo = low[j]; loIdx = j }
            }
            up[i] = 100.0 * (period - (i - hiIdx)) / period
            down[i] = 100.0 * (period - (i - loIdx)) / period
            osc[i] = up[i] - down[i]
        }
        return Triple(up, down, osc)
    }

    /** OBV 能量潮：上涨日累加成交量，下跌日递减，平盘不变 */
    fun obv(close: DoubleArray, volume: LongArray): DoubleArray {
        val n = close.size
        val out = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            if (i > 0) {
                when {
                    close[i] > close[i - 1] -> acc += volume[i]
                    close[i] < close[i - 1] -> acc -= volume[i]
                }
            }
            out[i] = acc
        }
        return out
    }

    /** A/D 累积分配线：CLV=((C−L)−(H−C))/(H−L)，AD += CLV·V */
    fun adLine(high: DoubleArray, low: DoubleArray, close: DoubleArray, volume: LongArray): DoubleArray {
        val n = close.size
        val out = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            val h = high[i]; val l = low[i]; val c = close[i]
            val rng = h - l
            val clv = if (rng > 0) ((c - l) - (h - c)) / rng else 0.0
            acc += clv * volume[i]
            out[i] = acc
        }
        return out
    }

    /** Chaikin A/D 振荡器：EMA(AD,fast) − EMA(AD,slow) */
    fun adosc(ad: DoubleArray, fast: Int = 3, slow: Int = 10): DoubleArray {
        val ef = ema(ad, fast); val es = ema(ad, slow)
        return DoubleArray(ad.size) { ef[it] - es[it] }
    }

    /** VWAP（日锚累计）：Σ典型价·量 / Σ量；典型价=(H+L+C)/3 */
    fun vwap(high: DoubleArray, low: DoubleArray, close: DoubleArray, volume: LongArray): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        var pv = 0.0; var vv = 0.0
        for (i in 0 until n) {
            val tp = (high[i] + low[i] + close[i]) / 3.0
            pv += tp * volume[i]; vv += volume[i]
            if (vv > 0) out[i] = pv / vv
        }
        return out
    }

    /** 简单收益率序列（首值 NaN） */
    private fun simpleReturns(close: DoubleArray): DoubleArray {
        val n = close.size
        val r = DoubleArray(n) { Double.NaN }
        for (i in 1 until n) {
            if (close[i - 1] > 0) r[i] = close[i] / close[i - 1] - 1.0
        }
        return r
    }

    /** 年化实现波动率（close-to-close，std×√252）；窗口不足返回 NaN */
    fun realizedVolatility(close: DoubleArray, window: Int = 30, periodsPerYear: Int = 252): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        if (n < window + 1) return out
        val r = simpleReturns(close)
        for (i in window until n) {
            var mean = 0.0; var cnt = 0
            for (j in i - window + 1..i) { if (!r[j].isNaN()) { mean += r[j]; cnt++ } }
            if (cnt < 2) continue
            mean /= cnt
            var varSum = 0.0
            for (j in i - window + 1..i) { if (!r[j].isNaN()) varSum += (r[j] - mean).pow(2) }
            out[i] = sqrt(varSum / cnt) * sqrt(periodsPerYear.toDouble())
        }
        return out
    }

    /** Parkinson 区间波动率（年化）：1/(4·ln2)·mean(ln(H/L))² 开方 ×√252 */
    fun parkinsonVolatility(high: DoubleArray, low: DoubleArray, window: Int = 30, periodsPerYear: Int = 252): DoubleArray {
        val n = high.size
        val out = DoubleArray(n) { Double.NaN }
        if (n < window) return out
        val ln4 = 4.0 * ln(2.0)
        for (i in window - 1 until n) {
            var s = 0.0; var cnt = 0
            for (j in i - window + 1..i) {
                if (low[j] > 0 && high[j] > 0) { s += ln(high[j] / low[j]).pow(2); cnt++ }
            }
            if (cnt >= 2) out[i] = sqrt(s / cnt / ln4) * sqrt(periodsPerYear.toDouble())
        }
        return out
    }

    /** Clenow 波动调整动量结果：r2 拟合度、年化斜率、factor=年化斜率×R² */
    data class ClenowResult(val r2: Double, val annualizedCoef: Double, val factor: Double)

    /**
     * Clenow 波动调整动量：近 period 根 log 价对时间索引最小二乘回归。
     * 窗口不足返回 NaN 结果。
     */
    fun clenow(close: DoubleArray, period: Int = 90, periodsPerYear: Int = 252): ClenowResult {
        val n = close.size
        if (n < 5) return ClenowResult(Double.NaN, Double.NaN, Double.NaN)
        val start = maxOf(0, n - period)
        val m = n - start
        val y = DoubleArray(m) { ln(close[start + it].coerceAtLeast(1e-9)) }
        val x = DoubleArray(m) { it.toDouble() }
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until m) {
            val dx = x[i] - mx; val dy = y[i] - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        if (sxx <= 0.0) return ClenowResult(0.0, 0.0, 0.0)
        val slope = sxy / sxx
        val intercept = my - slope * mx
        var ssRes = 0.0
        for (i in 0 until m) { val fit = intercept + slope * x[i]; ssRes += (y[i] - fit).pow(2) }
        val r2 = if (syy > 0) (1.0 - ssRes / syy).coerceIn(0.0, 1.0) else 0.0
        val annualized = slope * periodsPerYear
        return ClenowResult(r2, annualized, annualized * r2)
    }

    /** Hurst 指数（R/S 法）：H>0.55 趋势，<0.45 均值回归，其余随机游走；样本不足返回 NaN */
    fun hurst(close: DoubleArray, minLag: Int = 2, maxLag: Int = 100): Double {
        val n = close.size
        if (n < 20) return Double.NaN
        val y = DoubleArray(n) { ln(close[it].coerceAtLeast(1e-9)) }
        val lags = mutableListOf<Int>(); val rs = mutableListOf<Double>()
        val upper = minOf(maxLag, n / 2)
        for (lag in minLag until upper) {
            val chunks = n / lag
            if (chunks < 1) continue
            val rsChunk = mutableListOf<Double>()
            for (c in 0 until chunks) {
                val block = y.copyOfRange(c * lag, (c + 1) * lag)
                val bm = block.average()
                var cum = 0.0; var cumMin = 0.0; var cumMax = 0.0
                var bs = 0.0
                for (bv in block) {
                    val dev = bv - bm
                    cum += dev; cumMin = minOf(cumMin, cum); cumMax = maxOf(cumMax, cum)
                    bs += dev.pow(2)
                }
                val r = cumMax - cumMin
                val s = sqrt(bs / block.size)
                if (s > 0) rsChunk.add(r / s)
            }
            if (rsChunk.isNotEmpty()) { lags.add(lag); rs.add(rsChunk.average()) }
        }
        if (lags.size < 2) return Double.NaN
        val lx = DoubleArray(lags.size) { ln(lags[it].toDouble()) }
        val ly = DoubleArray(rs.size) { ln(rs[it]) }
        val mx = lx.average(); val my = ly.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in lx.indices) { val dx = lx[i] - mx; val dy = ly[i] - my; sxy += dx * dy; sxx += dx * dx }
        return if (sxx > 0.0) sxy / sxx else Double.NaN
    }

    /** 均线交叉事件：direction=bullish/bearish/none，distance=fast−slow */
    data class CrossEvent(val direction: String, val fast: Double, val slow: Double, val distance: Double)

    /** 最近一根快慢均线交叉；distance=fast−slow；数据不足返回 null */
    fun maCross(fast: DoubleArray, slow: DoubleArray): CrossEvent? {
        val n = fast.size
        if (n < 2) return null
        val prev = fast[n - 2] - slow[n - 2]
        val curr = fast[n - 1] - slow[n - 1]
        if (prev.isNaN() || curr.isNaN()) return null
        val direction = when {
            prev <= 0 && curr > 0 -> "bullish"
            prev >= 0 && curr < 0 -> "bearish"
            else -> "none"
        }
        return CrossEvent(direction, fast[n - 1], slow[n - 1], curr)
    }

    /** 通道突破事件：direction=upside/downside/none，magnitude=price−band，barsInRange=距上次突破根数 */
    data class BreakoutEvent(val direction: String, val price: Double, val band: Double, val magnitude: Double, val barsInRange: Int)

    /** 最近一根唐奇安通道突破；窗口不足返回 null */
    fun breakoutSignal(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 20): BreakoutEvent? {
        val n = close.size
        if (n < period + 1) return null
        val dc = donchian(high, low, period)
        val latest = n - 1
        val prevUpper = dc.first[latest - 1]; val prevLower = dc.second[latest - 1]
        if (prevUpper.isNaN() || prevLower.isNaN()) return null
        val price = close[latest]
        var lastBar = -1
        for (i in latest - 1 downTo period) {
            val up = close[i] > dc.first[i - 1]
            val dn = close[i] < dc.second[i - 1]
            if (up || dn) { lastBar = i; break }
        }
        val barsInRange = if (lastBar < 0) latest else latest - lastBar
        return when {
            price > prevUpper -> BreakoutEvent("upside", price, prevUpper, price - prevUpper, barsInRange)
            price < prevLower -> BreakoutEvent("downside", price, prevLower, price - prevLower, barsInRange)
            else -> BreakoutEvent("none", price, if (price >= prevUpper) prevUpper else prevLower, 0.0, barsInRange)
        }
    }

    /** 收益风险统计：mean/std/夏普/索诺/卡玛/最大回撤/Var95 */
    data class ReturnStats(
        val meanReturn: Double, val stdReturn: Double, val sharpe: Double,
        val sortino: Double, val calmar: Double, val maxDrawdown: Double, val var95: Double
    )

    /**
     * 日收益序列风险调整统计（年化 √252）。样本不足返回中性 NaN 结果。
     */
    fun returnsStats(close: DoubleArray, window: Int = 60, periodsPerYear: Int = 252): ReturnStats {
        val n = close.size
        val empty = ReturnStats(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        if (n < 10) return empty
        val start = maxOf(0, n - window)
        val r = mutableListOf<Double>()
        for (i in start + 1 until n) { if (close[i - 1] > 0) r.add(close[i] / close[i - 1] - 1.0) }
        if (r.size < 5) return empty
        val mean = r.average()
        val std = sqrt(r.map { (it - mean).pow(2) }.average())
        val sqrtY = sqrt(periodsPerYear.toDouble())
        val sharpe = if (std > 0) (mean / std) * sqrtY else Double.NaN
        val down = r.filter { it < 0 }
        val dsDev = if (down.size >= 2) sqrt(down.map { it * it }.average()) else 0.0
        val sortino = if (dsDev > 0) (mean / dsDev) * sqrtY else Double.NaN
        var cum = 1.0; var peak = 1.0; var maxDd = 0.0
        for (v in r) { cum *= (1 + v); peak = maxOf(peak, cum); maxDd = minOf(maxDd, cum / peak - 1.0) }
        val annReturn = if (cum > 0) cum.pow(periodsPerYear.toDouble() / r.size) - 1.0 else Double.NaN
        val calmar = if (maxDd < 0 && !annReturn.isNaN()) annReturn / abs(maxDd) else Double.NaN
        val sorted = r.sorted()
        val varIdx = (0.05 * sorted.size).toInt().coerceIn(0, sorted.size - 1)
        return ReturnStats(mean, std, sharpe, sortino, calmar, maxDd, sorted[varIdx])
    }

    /** 序列分位数（线性插值，q∈0..1）；空序列返回 NaN */
    fun quantile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val pos = q * (s.size - 1)
        val lo = pos.toInt(); val hi = minOf(lo + 1, s.size - 1)
        val frac = pos - lo
        return s[lo] + (s[hi] - s[lo]) * frac
    }
}
