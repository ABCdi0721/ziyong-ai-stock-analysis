package com.dsa.mobile

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
}
