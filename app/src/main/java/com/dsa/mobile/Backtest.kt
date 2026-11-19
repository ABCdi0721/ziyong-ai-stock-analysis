package com.dsa.mobile

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * A 股日 K 回测引擎（技术信号策略，纯本地计算）
 * 策略：MA金叉 / MACD金叉 / RSI超卖反弹 / 买入持有
 *
 * v2.12.36 起接入 A 股交易约束：
 * - T+1：当日买入次日才可卖；
 * - 信号日收盘出信号，次日开盘成交（避免未来函数）；
 * - 100 股整手；
 * - 佣金双边（费率可配，单笔最低 5 元）、卖出印花税、滑点；
 * - 封涨停不可买入、封跌停不可卖出（跌停顺延次日再试）；
 * - 输出扣费前后收益、盈亏比、累计成本。
 */
object Backtest {

    /** 单笔交易 */
    data class Trade(
        val buyDate: String, val buyPrice: Double,
        val sellDate: String, val sellPrice: Double,
        val pct: Double,          // 单笔净收益率 %（扣费 + 滑点后）
        val grossPct: Double,    // 单笔毛收益率 %（价格价差，不含费用与滑点）
        val cost: Double          // 单笔交易成本（佣金 + 印花税 + 滑点折算，元）
    )

    /** 回测结果 */
    data class Result(
        val totalReturn: Double,    // 扣费后总收益率 %
        val grossReturn: Double,    // 扣费前总收益率 %
        val annualReturn: Double,   // 年化收益率 %（扣费后）
        val winRate: Double,        // 胜率 %
        val profitLossRatio: Double,// 盈亏比（平均盈利 / 平均亏损绝对值）
        val maxDrawdown: Double,    // 最大回撤 %
        val tradeCount: Int,
        val trades: List<Trade>,
        val buyHoldReturn: Double,  // 同期买入持有收益率 %
        val totalCost: Double       // 累计交易成本（元）
    )

    /** 交易参数（A 股出厂口径；后续可由设置页覆写） */
    data class TradeParams(
        val commissionRate: Double = 0.00025, // 佣金双边 万 2.5
        val minCommission: Double = 5.0,       // 单笔最低佣金 5 元
        val stampTax: Double = 0.0005,         // 卖出印花税 千 0.5
        val slippage: Double = 0.001,          // 滑点 0.1%
        val lotSize: Int = 100                 // 整手股数
    )

    val STRATEGIES = listOf("MA金叉", "MACD金叉", "RSI超卖反弹", "买入持有")

    fun run(
        bars: List<KLineBar>,
        strategy: String,
        code: String = "",
        params: TradeParams = TradeParams()
    ): Result {
        val n = bars.size
        if (n < 30) {
            return Result(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, emptyList(), 0.0, 0.0)
        }
        val close = DoubleArray(n) { bars[it].close }
        val ma5 = Indicators.sma(close, 5)
        val ma10 = Indicators.sma(close, 10)
        val (dif, dea, _) = Indicators.macd(close)
        val rsi6 = Indicators.rsi(close, 6)

        var cash = 100000.0          // 初始资金
        var shares = 0               // 持仓股数（整手）
        var costPrice = 0.0          // 持仓成本（买入净支出均价口径）
        var buyFillIdx = -1         // 买入成交下标（T+1 判定用）
        var buyDate = ""; var buyPrice = 0.0
        var buyGrossPrice = 0.0     // 买入开盘毛价（未含滑点/费用）
        val trades = mutableListOf<Trade>()
        var holding = false
        var totalCost = 0.0

        // 挂单：信号在 i 日收盘生成，i+1 日开盘成交；-1 表示无挂单
        var pendingBuy = -1
        var pendingSell = -1

        val equity = DoubleArray(n)
        equity[0] = 100000.0

        fun commission(amount: Double): Double =
            max(amount * params.commissionRate, params.minCommission)

        /** 买入在 idx 日开盘成交；返回是否成交 */
        fun executeBuy(idx: Int): Boolean {
            if (holding || idx <= 0 || idx >= n) return false
            val prevClose = close[idx - 1]
            if (limitHitUp(bars[idx], prevClose, code)) return false // 封涨停买不进
            val rawPrice = bars[idx].open
            if (rawPrice <= 0) return false
            val price = rawPrice * (1 + params.slippage)            // 买入滑点抬高
            // 整手：先扣佣金再倒推可买股数
            val rate = 1 + params.commissionRate
            val affordable = floor(cash / (price * rate) / params.lotSize).toInt()
            if (affordable <= 0) return false
            val qty = affordable * params.lotSize
            val buyAmount = qty * price
            val comm = commission(buyAmount)
            val spend = buyAmount + comm
            if (spend > cash) return false
            cash -= spend
            totalCost += comm
            shares = qty
            costPrice = spend / qty
            buyFillIdx = idx
            buyDate = bars[idx].date
            buyPrice = price
            buyGrossPrice = rawPrice
            holding = true
            return true
        }

        /** 卖出在 idx 日开盘成交；返回是否成交（跌停顺延） */
        fun executeSell(idx: Int): Boolean {
            if (!holding || idx <= 0 || idx >= n) return false
            // T+1：买入成交当日不可卖
            if (idx <= buyFillIdx) return false
            val prevClose = close[idx - 1]
            if (limitHitDown(bars[idx], prevClose, code)) return false // 封跌停卖不出，顺延
            val rawPrice = bars[idx].open
            if (rawPrice <= 0) return false
            val price = rawPrice * (1 - params.slippage)             // 卖出滑点压低
            val proceeds = shares * price
            val comm = commission(proceeds)
            val stamp = proceeds * params.stampTax
            val net = proceeds - comm - stamp
            cash += net
            totalCost += comm + stamp

            val grossPct = (rawPrice - buyGrossPrice) / buyGrossPrice * 100.0
            val buySpend = costPrice * shares
            val netPct = if (buySpend > 0) (net - buySpend) / buySpend * 100.0 else 0.0
            trades.add(
                Trade(buyDate, buyPrice, bars[idx].date, price, netPct, grossPct, comm + stamp)
            )
            shares = 0; costPrice = 0.0; buyFillIdx = -1
            holding = false
            return true
        }

        fun signal(i: Int): Boolean? {
            if (i < 2) return null
            return when (strategy) {
                "MA金叉" -> {
                    if (ma5[i] > ma10[i] && ma5[i - 1] <= ma10[i - 1]) true
                    else if (ma5[i] < ma10[i] && ma5[i - 1] >= ma10[i - 1]) false
                    else null
                }
                "MACD金叉" -> {
                    if (dif[i] > dea[i] && dif[i - 1] <= dea[i - 1]) true
                    else if (dif[i] < dea[i] && dif[i - 1] >= dea[i - 1]) false
                    else null
                }
                "RSI超卖反弹" -> {
                    if (rsi6[i] < 30 && rsi6[i] > rsi6[i - 1]) true
                    else if (rsi6[i] > 70 && rsi6[i] < rsi6[i - 1]) false
                    else null
                }
                "买入持有" -> if (i == 20) true else null
                else -> null
            }
        }

        val start = 20
        for (i in start until n) {
            // 1) 先执行 i 日开盘可成交的挂单（由前一日信号产生）
            if (pendingSell >= 0) {
                if (executeSell(i)) pendingSell = -1 else pendingSell = i - 1 // 跌停顺延，下一交易日再试
            }
            if (pendingBuy >= 0) {
                if (executeBuy(i)) pendingBuy = -1 else pendingBuy = -1 // 涨停买不进，该信号作废
            }
            // 2) 当日收盘生成信号，挂次日成交
            if (strategy == "买入持有") {
                if (!holding && pendingBuy < 0) pendingBuy = i
            } else {
                val sig = signal(i)
                when (sig) {
                    true -> if (!holding && pendingBuy < 0) pendingBuy = i
                    false -> if (holding && pendingSell < 0) pendingSell = i
                    null -> {}
                }
            }
            equity[i] = if (holding) cash + shares * close[i] else cash
        }

        // 期末强制平仓（按最后收盘价了结；回测收尾，不再受跌停顺延约束）
        if (holding) {
            val last = n - 1
            val rawPrice = close[last]
            val price = rawPrice * (1 - params.slippage)
            val proceeds = shares * price
            val comm = commission(proceeds)
            val stamp = proceeds * params.stampTax
            val net = proceeds - comm - stamp
            cash += net
            totalCost += comm + stamp
            val grossPct = (rawPrice - buyGrossPrice) / buyGrossPrice * 100.0
            val buySpend = costPrice * shares
            val netPct = if (buySpend > 0) (net - buySpend) / buySpend * 100.0 else 0.0
            trades.add(Trade(buyDate, buyPrice, bars[last].date, price, netPct, grossPct, comm + stamp))
            shares = 0; holding = false
        }

        val finalValue = cash
        val totalReturn = (finalValue - 100000.0) / 100000.0 * 100
        // 扣费前：按毛价价差、不计佣金/印花税/滑点的近似净值（用各笔 grossPct 加权还原）
        val grossReturn = if (trades.isEmpty()) buyHoldReturn(bars, start) else {
            val grossEquity = 100000.0
            var g = grossEquity
            trades.forEach { t -> g *= (1 + t.grossPct / 100.0) }
            (g - grossEquity) / grossEquity * 100
        }
        val years = (n - start) / 244.0
        val annualReturn = if (years > 0) (Math.pow(finalValue / 100000.0, 1.0 / years) - 1.0) * 100 else 0.0

        val wins = trades.count { it.pct > 0 }
        val winRate = if (trades.isEmpty()) 0.0 else wins * 100.0 / trades.size
        val avgWin = trades.filter { it.pct > 0 }.map { it.pct }.let { if (it.isEmpty()) 0.0 else it.average() }
        val avgLoss = trades.filter { it.pct < 0 }.map { abs(it.pct) }.let { if (it.isEmpty()) 0.0 else it.average() }
        val profitLossRatio = if (avgLoss > 1e-9) avgWin / avgLoss else 0.0

        var peak = equity[start]
        var maxDd = 0.0
        for (i in start until n) {
            if (equity[i] > peak) peak = equity[i]
            val dd = if (peak > 0) (peak - equity[i]) / peak * 100 else 0.0
            if (dd > maxDd) maxDd = dd
        }

        return Result(
            totalReturn, grossReturn, annualReturn, winRate, profitLossRatio, maxDd,
            trades.size, trades, buyHoldReturn(bars, start), totalCost
        )
    }

    private fun buyHoldReturn(bars: List<KLineBar>, start: Int): Double {
        if (bars.size <= start) return 0.0
        return (bars.last().close - bars[start].close) / bars[start].close * 100
    }

    /** 封涨停：开盘涨幅达阈值，或一字板贴板 */
    private fun limitHitUp(bar: KLineBar, prevClose: Double, code: String): Boolean {
        if (prevClose <= 0) return false
        val limit = MarketGuard.limitPct(code)
        val chg = (bar.open / prevClose - 1) * 100
        if (chg >= limit - 0.3) return true
        if (bar.high > 0 && bar.low > 0 && bar.close > 0) {
            val rangePct = (bar.high - bar.low) / bar.close * 100
            if (rangePct <= 0.5 && chg >= limit - 1.0) return true
        }
        return false
    }

    /** 封跌停：开盘跌幅达阈值 */
    private fun limitHitDown(bar: KLineBar, prevClose: Double, code: String): Boolean {
        if (prevClose <= 0) return false
        val limit = MarketGuard.limitPct(code)
        val chg = (bar.open / prevClose - 1) * 100
        return chg <= -(limit - 0.3)
    }
}
