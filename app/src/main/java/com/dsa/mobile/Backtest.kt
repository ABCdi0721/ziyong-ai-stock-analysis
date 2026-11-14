package com.dsa.mobile

/**
 * A 股日 K 回测引擎（技术信号策略，纯本地计算）
 * 策略：MA金叉 / MACD金叉 / RSI超卖反弹 / 买入持有
 */
object Backtest {

    data class Trade(
        val buyDate: String, val buyPrice: Double,
        val sellDate: String, val sellPrice: Double,
        val pct: Double          // 单笔收益率 %
    )

    data class Result(
        val totalReturn: Double,   // 总收益率 %
        val annualReturn: Double,  // 年化收益率 %
        val winRate: Double,       // 胜率 %
        val maxDrawdown: Double,   // 最大回撤 %
        val tradeCount: Int,
        val trades: List<Trade>,
        val buyHoldReturn: Double  // 同期买入持有收益率 %
    )

    val STRATEGIES = listOf("MA金叉", "MACD金叉", "RSI超卖反弹", "买入持有")

    fun run(bars: List<KLineBar>, strategy: String): Result {
        val n = bars.size
        if (n < 30) {
            return Result(0.0, 0.0, 0.0, 0.0, 0, emptyList(), 0.0)
        }
        val close = DoubleArray(n) { bars[it].close }
        val ma5 = Indicators.sma(close, 5)
        val ma10 = Indicators.sma(close, 10)
        val (dif, dea, _) = Indicators.macd(close)
        val rsi6 = Indicators.rsi(close, 6)

        var cash = 100000.0          // 初始资金
        var shares = 0               // 持仓股数
        var costPrice = 0.0          // 持仓成本
        var buyDate = ""; var buyPrice = 0.0
        val trades = mutableListOf<Trade>()
        var holding = false

        // 净值曲线（用于最大回撤）
        val equity = DoubleArray(n)
        equity[0] = 100000.0

        fun buy(i: Int) {
            if (holding) return
            shares = (cash / close[i]).toInt()
            if (shares <= 0) return
            cash -= shares * close[i]
            costPrice = close[i]
            buyDate = bars[i].date; buyPrice = close[i]
            holding = true
        }

        fun sell(i: Int) {
            if (!holding) return
            val proceeds = shares * close[i]
            cash += proceeds
            val pct = (close[i] - costPrice) / costPrice * 100
            trades.add(Trade(buyDate, buyPrice, bars[i].date, close[i], pct))
            shares = 0; costPrice = 0.0
            holding = false
        }

        // 策略信号：返回 true 表示买入信号，false 表示卖出信号，null 表示无操作
        fun signal(i: Int): Boolean? {
            if (i < 2) return null
            return when (strategy) {
                "MA金叉" -> {
                    if (ma5[i] > ma10[i] && ma5[i - 1] <= ma10[i - 1]) true      // 金叉买入
                    else if (ma5[i] < ma10[i] && ma5[i - 1] >= ma10[i - 1]) false // 死叉卖出
                    else null
                }
                "MACD金叉" -> {
                    if (dif[i] > dea[i] && dif[i - 1] <= dea[i - 1]) true
                    else if (dif[i] < dea[i] && dif[i - 1] >= dea[i - 1]) false
                    else null
                }
                "RSI超卖反弹" -> {
                    if (rsi6[i] < 30 && rsi6[i] > rsi6[i - 1]) true              // 超卖回升买入
                    else if (rsi6[i] > 70 && rsi6[i] < rsi6[i - 1]) false        // 超买回落卖出
                    else null
                }
                "买入持有" -> if (i == 20) true else null
                else -> null
            }
        }

        val start = 20
        for (i in start until n) {
            if (strategy == "买入持有") {
                // 期初全仓买入，永不卖出
                if (!holding) buy(i)
            } else {
                val sig = signal(i)
                if (sig == true) buy(i)
                else if (sig == false) sell(i)
            }
            // 记录净值
            equity[i] = if (holding) cash + shares * close[i] else cash
        }
        // 期末平仓（买入持有除外）
        if (holding && strategy != "买入持有") sell(n - 1)

        val finalValue = if (holding) cash + shares * close[n - 1] else cash
        val totalReturn = (finalValue - 100000.0) / 100000.0 * 100
        val years = (n - start) / 244.0
        val annualReturn = if (years > 0) (Math.pow(finalValue / 100000.0, 1.0 / years) - 1.0) * 100 else 0.0

        val wins = trades.count { it.pct > 0 }
        val winRate = if (trades.isEmpty()) 0.0 else wins * 100.0 / trades.size

        // 最大回撤
        var peak = equity[start]
        var maxDd = 0.0
        for (i in start until n) {
            if (equity[i] > peak) peak = equity[i]
            val dd = if (peak > 0) (peak - equity[i]) / peak * 100 else 0.0
            if (dd > maxDd) maxDd = dd
        }

        val buyHoldReturn = (close[n - 1] - close[start]) / close[start] * 100

        return Result(
            totalReturn, annualReturn, winRate, maxDd,
            trades.size, trades, buyHoldReturn
        )
    }
}
