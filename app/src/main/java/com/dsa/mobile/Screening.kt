package com.dsa.mobile

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * 选股引擎
 * 移植自 daily_stock_analysis/src/services/screening/（filter.py + scorer.py）
 * 手机端基于东财全市场快照实现可计算因子：估值/流动性/动量/活跃度/稳定性/反转
 */
object Screening {

    /** 策略预设（参数取自原项目 src/services/screening/strategies 目录下的 yaml 配置） */
    val STRATEGIES = listOf(
        // 短线·今日买入（v2.4.0）：预筛宽松 → Top100 拉日K做快照分析 → 只保留快照信号"买入/强烈买入"
        ScreenStrategy(
            "短线·今日买入", 0.0,
            priceMin = 3.0, priceMax = 200.0, mvMin = 30.0, peMax = 100.0,
            vrMin = 1.0, turnoverMin = 1.0,
            changeMin = 0.0, changeMax = 7.0, amountMin = 5e7
        ),
        // v2.12.6：热股榜前 500 + 短线因子（当天可买）：股票池来自多源热榜合并去重，
        // 叠加量比/换手/涨跌幅/成交额等短线因子，热度分与因子分加权排序
        ScreenStrategy(
            "热股榜·短线", 0.55,
            priceMin = 3.0, priceMax = 200.0, mvMin = 20.0, peMax = 400.0,
            vrMin = 1.2, turnoverMin = 1.5,
            changeMin = 0.0, changeMax = 8.5, amountMin = 5e7
        ),
        // 新闻利好选股（v2.5.0）：预筛+评分 Top60 → 逐只检索新闻 → 关键词情绪打分 → 只保留利好
        ScreenStrategy(
            "新闻利好选股", 0.5,
            priceMin = 3.0, priceMax = 220.0, mvMin = 30.0, peMax = 200.0,
            vrMin = 0.8, turnoverMin = 0.8,
            changeMin = -3.0, changeMax = 9.5, amountMin = 5e7
        ),
        // 双低选股 dual_low
        ScreenStrategy(
            "双低选股", 0.2,
            priceMin = 3.0, priceMax = 80.0, mvMin = 50.0, mvMax = 3000.0,
            peMax = 15.0, peMin = 0.0, pbMax = 2.0,
            changeMin = -4.5, changeMax = 4.5, amountMin = 5e7
        ),
        // 均衡多因子 balanced_alpha
        ScreenStrategy(
            "均衡多因子", 0.35,
            priceMin = 3.0, priceMax = 220.0, mvMin = 50.0,
            peMax = 80.0, pbMax = 8.0,
            changeMin = -4.0, changeMax = 8.5, amountMin = 1e8
        ),
        // 蓝筹收益质量 blue_chip_income
        ScreenStrategy(
            "蓝筹收益质量", 0.18,
            priceMin = 3.0, priceMax = 180.0, mvMin = 300.0,
            peMax = 22.0, pbMax = 3.2,
            turnoverMin = 0.4, vrMin = 0.6,
            changeMin = -3.0, changeMax = 4.0, amountMin = 1.2e8
        ),
        // 资金热度 capital_heat
        ScreenStrategy(
            "资金热度", 0.65,
            priceMin = 3.0, priceMax = 220.0,
            turnoverMin = 2.0, vrMin = 1.5,
            changeMin = 1.0, changeMax = 9.5, amountMin = 3e8
        ),
        // 低波质量 low_volatility_quality
        ScreenStrategy(
            "低波质量", 0.3,
            priceMin = 4.0, priceMax = 180.0, mvMin = 120.0,
            peMax = 45.0, pbMax = 5.0,
            changeMin = -3.0, changeMax = 5.0, amountMin = 1e8
        ),
        // 趋势质量 momentum_quality
        ScreenStrategy(
            "趋势质量", 0.45,
            priceMin = 4.0, priceMax = 220.0, mvMin = 80.0,
            peMax = 60.0, pbMax = 8.0,
            changeMin = -3.5, changeMax = 7.5, amountMin = 2e8
        ),
        // 超跌反转 oversold_reversal
        ScreenStrategy(
            "超跌反转", 0.45,
            priceMin = 3.0, priceMax = 180.0,
            peMax = 80.0, pbMax = 8.0,
            turnoverMin = 1.0, changeMin = -8.0, changeMax = -1.0, amountMin = 8e7
        ),
        // 稳健价值 quality_value
        ScreenStrategy(
            "稳健价值", 0.15,
            priceMin = 3.0, priceMax = 180.0, mvMin = 100.0, mvMax = 8000.0,
            peMax = 25.0, pbMax = 4.0,
            changeMin = -3.5, changeMax = 5.0, amountMin = 8e7
        ),
        // 缩量回踩 shrink_pullback
        ScreenStrategy(
            "缩量回踩", 0.5,
            changeMin = -8.0, changeMax = 8.0,
            turnoverMin = 1.0, amountMin = 8e7,
            vrMax = 1.5
        ),
        // 放量突破 volume_breakout
        ScreenStrategy(
            "放量突破", 0.6,
            priceMin = 3.0, priceMax = 220.0,
            turnoverMin = 3.0, vrMin = 2.0,
            changeMin = 2.0, changeMax = 9.9, amountMin = 1e8
        ),
        // 小市值精选（GitHub ashare-quant-strategies #048 小市值精选策略）
        // 原逻辑：按流通市值升序取前 N 只，过滤 ST/涨跌停/次新；手机端以总市值近似 + 低估值过滤
        ScreenStrategy(
            "小市值精选", 0.25,
            priceMin = 2.0, priceMax = 40.0, mvMin = 15.0, mvMax = 80.0,
            peMax = 40.0, peMin = 0.0, pbMax = 4.0,
            changeMin = -5.0, changeMax = 6.0, amountMin = 3e7
        ),
        // 涨停捕手（GitHub #112 涨停捕手：强势股捕捉）
        // 原逻辑：因子选股后取市值较小者 + 涨停股尾盘管理；手机端适配为"近涨停强势+量能确认+盈利过滤"
        ScreenStrategy(
            "涨停捕手", 0.7,
            priceMin = 4.0, priceMax = 100.0, peMax = 200.0, peMin = 0.0,
            turnoverMin = 4.0, vrMin = 2.0,
            changeMin = 5.0, changeMax = 9.5, amountMin = 1e8
        ),
        // 低价动量（GitHub #049 动量驱动的低价股票精选策略）
        // 原逻辑：价格较低（5元以上）+ 91日动量收益率排序取前N；手机端以 60 日涨跌幅为动量
        ScreenStrategy(
            "低价动量", 0.45,
            priceMin = 3.0, priceMax = 15.0, vrMin = 1.2,
            changeMin = 0.0, changeMax = 7.0, amountMin = 5e7
        ),
        // 多维量能（GitHub #034 多维量能与竞价策略）
        // 原逻辑：竞价低开(昨收的94.5%-98.5%) + 20日放量5倍过滤 + 非涨停即出；手机端适配为低开+量比放大+换手活跃
        ScreenStrategy(
            "多维量能", 0.6,
            priceMin = 3.0, priceMax = 200.0,
            turnoverMin = 2.0, vrMin = 1.5,
            changeMin = -5.0, changeMax = 1.5, amountMin = 1e8
        ),
        // v2.12.9：中小市值 + 当下热门题材 + 短线资金博弈
        // 股票池不是固定名单，而是运行时按东财板块涨幅榜动态取「当前热门题材」的成分股（见 ThemeHotApi），
        // 再叠加中小市值区间与短线资金博弈因子（量比放大 / 换手活跃 / 成交额足够）
        ScreenStrategy(
            "中小市值·热门题材", 0.6,
            priceMin = 3.0, priceMax = 80.0, mvMin = 15.0, mvMax = 200.0, peMax = 300.0,
            vrMin = 1.2, turnoverMin = 2.0, turnoverMax = 25.0,
            changeMin = -2.0, changeMax = 7.0, amountMin = 5e7
        ),
        // 自定义策略...（参数在 App 内编辑保存）
        ScreenStrategy("自定义策略...", 0.35)
    )

    /** 自定义策略名：始终固定在下拉列表最后一项，不参与排序 */
    const val CUSTOM_NAME = "自定义策略..."

    /**
     * v2.12.10：按用户保存的顺序重排策略列表。
     * order 为空 → 默认顺序；order 里没有的名称按默认顺序追加；
     * 「自定义策略...」永远固定在最末，保证下拉最后一项仍是自定义入口。
     */
    fun ordered(order: List<String>): List<ScreenStrategy> {
        if (order.isEmpty()) return STRATEGIES
        val out = mutableListOf<ScreenStrategy>()
        order.forEach { name ->
            val s = STRATEGIES.firstOrNull { it.name == name && it.name != CUSTOM_NAME }
            if (s != null && out.none { it.name == s.name }) out.add(s)
        }
        STRATEGIES.forEach { s ->
            if (s.name != CUSTOM_NAME && out.none { it.name == s.name }) out.add(s)
        }
        STRATEGIES.firstOrNull { it.name == CUSTOM_NAME }?.let { out.add(it) }
        return out
    }

    /** 评分参数（原 _DEFAULT_SCORING_PROFILE 口径） */
    private class Profile(
        var momentumChaseStart: Double = 5.0,
        var momentumChasePenalty: Double = 10.0,
        var momentumDownsideStart: Double = -2.0,
        var momentumOverheat: Double = 45.0,
        var reversalIdeal: Double = -3.0,
        var reversalCollapseStart: Double = -8.0,
        var reversalCollapsePenalty: Double = 10.0,
        var reversalChaseStart: Double = 1.0,
        var activityIdealVr: Double = 2.0,
        var activityHighVr: Double = 5.0,
        var activityIdealTurnover: Double = 4.0,
        var activityHighTurnover: Double = 12.0,
        var stabilityHotChange: Double = 7.0,
        var stabilityHighTurnover: Double = 10.0,
        var stabilityHighVr: Double = 5.0
    )

    private fun profileFor(strategy: ScreenStrategy): Profile {
        val p = Profile()
        when (strategy.name) {
            "双低选股" -> {
                p.momentumChaseStart = 2.5; p.momentumChasePenalty = 18.0; p.momentumDownsideStart = -2.5
                p.activityIdealVr = 1.2; p.activityIdealTurnover = 2.0; p.activityHighVr = 4.0; p.activityHighTurnover = 8.0
                p.stabilityHotChange = 4.0; p.stabilityHighTurnover = 8.0; p.stabilityHighVr = 4.0
            }
            "均衡多因子" -> {
                p.momentumChaseStart = 5.5; p.momentumChasePenalty = 12.0
                p.activityIdealVr = 2.0; p.activityIdealTurnover = 4.0
                p.stabilityHotChange = 7.0
            }
            "蓝筹收益质量" -> {
                p.momentumChaseStart = 2.8; p.momentumChasePenalty = 18.0; p.momentumDownsideStart = -3.0
                p.activityIdealVr = 1.1; p.activityIdealTurnover = 1.8; p.activityHighVr = 3.5; p.activityHighTurnover = 7.0
                p.stabilityHotChange = 4.0; p.stabilityHighTurnover = 7.0; p.stabilityHighVr = 3.5
            }
            "资金热度" -> {
                p.momentumChaseStart = 6.5; p.momentumChasePenalty = 12.0
                p.activityIdealVr = 2.8; p.activityIdealTurnover = 6.0; p.activityHighVr = 7.0; p.activityHighTurnover = 18.0
                p.stabilityHotChange = 8.5
            }
            "低波质量" -> {
                p.momentumOverheat = 32.0; p.momentumChaseStart = 4.0; p.momentumChasePenalty = 18.0
                p.activityIdealVr = 1.4; p.activityIdealTurnover = 2.5
                p.stabilityHotChange = 5.0
            }
            "趋势质量" -> {
                p.momentumChasePenalty = 14.0
                p.activityIdealVr = 2.2; p.activityIdealTurnover = 4.5
                p.stabilityHotChange = 6.5
            }
            "超跌反转" -> {
                p.reversalIdeal = -3.5; p.reversalCollapseStart = -7.0; p.reversalCollapsePenalty = 12.0
                p.reversalChaseStart = 0.0; p.momentumDownsideStart = -4.0
                p.activityIdealVr = 1.6; p.activityIdealTurnover = 3.0
            }
            "稳健价值" -> {
                p.momentumChaseStart = 3.0; p.momentumChasePenalty = 16.0
                p.activityIdealVr = 1.4; p.activityIdealTurnover = 2.5
                p.stabilityHotChange = 4.5; p.stabilityHighTurnover = 8.0; p.stabilityHighVr = 4.0
            }
            "缩量回踩" -> {
                p.momentumOverheat = 35.0; p.momentumChasePenalty = 14.0
                p.activityIdealVr = 1.0; p.activityIdealTurnover = 2.5; p.activityHighVr = 4.5
                p.stabilityHotChange = 5.5
            }
            "放量突破" -> {
                p.momentumChaseStart = 7.0; p.momentumChasePenalty = 11.0
                p.activityIdealVr = 3.0; p.activityIdealTurnover = 6.0; p.activityHighVr = 9.0; p.activityHighTurnover = 20.0
                p.stabilityHotChange = 8.8
            }
            "小市值精选" -> {
                // 小盘不追高：涨幅过大强惩罚；量能温和（换手高说明游资化，小盘警惕过热）
                p.momentumChaseStart = 3.0; p.momentumChasePenalty = 20.0; p.momentumDownsideStart = -4.0
                p.momentumOverheat = 38.0
                p.activityIdealVr = 1.3; p.activityIdealTurnover = 3.0; p.activityHighVr = 6.0; p.activityHighTurnover = 15.0
                p.stabilityHotChange = 6.0; p.stabilityHighTurnover = 12.0; p.stabilityHighVr = 5.0
            }
            "涨停捕手" -> {
                // 追强：容忍大涨，动量高分优先；量能极端不罚过重
                p.momentumChaseStart = 6.5; p.momentumChasePenalty = 10.0
                p.momentumOverheat = 55.0
                p.activityIdealVr = 3.2; p.activityIdealTurnover = 7.0; p.activityHighVr = 10.0; p.activityHighTurnover = 25.0
                p.stabilityHotChange = 9.5; p.stabilityHighTurnover = 20.0; p.stabilityHighVr = 8.0
            }
            "低价动量" -> {
                // 低价动量：60日涨幅为主要动量，容忍较大涨幅
                p.momentumChaseStart = 6.0; p.momentumChasePenalty = 12.0
                p.momentumOverheat = 55.0
                p.activityIdealVr = 2.0; p.activityIdealTurnover = 4.5; p.activityHighVr = 8.0; p.activityHighTurnover = 18.0
                p.stabilityHotChange = 8.0
            }
            "多维量能" -> {
                // 量能驱动：理想量比/换手更高，涨幅惩罚（原策略竞价低开买入，非涨停即出）
                p.momentumChaseStart = 1.5; p.momentumChasePenalty = 16.0; p.momentumDownsideStart = -5.0
                p.activityIdealVr = 2.6; p.activityIdealTurnover = 5.0; p.activityHighVr = 8.0; p.activityHighTurnover = 16.0
                p.stabilityHotChange = 7.5
            }
            "中小市值·热门题材" -> {
                // 短线资金博弈：量比/换手活跃度给高分区间；中小盘波动大，涨幅与过热做适度惩罚
                p.momentumChaseStart = 5.0; p.momentumChasePenalty = 14.0; p.momentumDownsideStart = -3.0
                p.momentumOverheat = 40.0
                p.activityIdealVr = 2.4; p.activityIdealTurnover = 5.5
                p.activityHighVr = 8.0; p.activityHighTurnover = 18.0
                p.stabilityHotChange = 7.0; p.stabilityHighTurnover = 15.0; p.stabilityHighVr = 6.0
            }
        }
        return p
    }

    /** 各策略的因子权重（在原策略权重基础上，对手机端可计算因子归一化） */
    private fun weightsFor(strategy: ScreenStrategy): Map<String, Double> {
        // 原 yaml factor_weights 中可计算因子：value/liquidity/momentum/activity/stability/reversal
        val raw = when (strategy.name) {
            "双低选股" -> mapOf(
                "value" to 0.34, "stability" to 0.20, "liquidity" to 0.14,
                "momentum" to 0.10, "activity" to 0.10, "reversal" to 0.06
            )
            "均衡多因子" -> mapOf(
                "value" to 0.22, "liquidity" to 0.18, "momentum" to 0.20,
                "activity" to 0.15, "stability" to 0.12, "reversal" to 0.05
            )
            "蓝筹收益质量" -> mapOf(
                "value" to 0.30, "stability" to 0.26, "liquidity" to 0.18,
                "activity" to 0.06, "momentum" to 0.05, "reversal" to 0.03
            )
            "资金热度" -> mapOf(
                "momentum" to 0.32, "activity" to 0.28, "liquidity" to 0.16,
                "stability" to 0.10, "reversal" to 0.04
            )
            "低波质量" -> mapOf(
                "stability" to 0.30, "value" to 0.20, "liquidity" to 0.15,
                "momentum" to 0.12, "activity" to 0.08, "reversal" to 0.02
            )
            "趋势质量" -> mapOf(
                "momentum" to 0.28, "value" to 0.15, "liquidity" to 0.18,
                "activity" to 0.15, "stability" to 0.12, "reversal" to 0.04
            )
            "超跌反转" -> mapOf(
                "reversal" to 0.40, "stability" to 0.20, "liquidity" to 0.18,
                "value" to 0.16, "activity" to 0.06
            )
            "稳健价值" -> mapOf(
                "value" to 0.32, "stability" to 0.24, "liquidity" to 0.18,
                "momentum" to 0.08, "activity" to 0.08, "reversal" to 0.04
            )
            "缩量回踩" -> mapOf(
                "momentum" to 0.32, "stability" to 0.22, "activity" to 0.18,
                "liquidity" to 0.15, "value" to 0.08, "reversal" to 0.05
            )
            "放量突破" -> mapOf(
                "momentum" to 0.32, "activity" to 0.28, "liquidity" to 0.22,
                "stability" to 0.10
            )
            "小市值精选" -> mapOf(
                "value" to 0.34, "liquidity" to 0.20, "stability" to 0.16,
                "momentum" to 0.14, "activity" to 0.10, "reversal" to 0.06
            )
            "涨停捕手" -> mapOf(
                "momentum" to 0.40, "activity" to 0.32, "liquidity" to 0.14,
                "stability" to 0.08, "reversal" to 0.06
            )
            "低价动量" -> mapOf(
                "momentum" to 0.42, "activity" to 0.20, "value" to 0.14,
                "liquidity" to 0.14, "stability" to 0.08, "reversal" to 0.02
            )
            "多维量能" -> mapOf(
                "activity" to 0.42, "momentum" to 0.22, "liquidity" to 0.20,
                "stability" to 0.10, "reversal" to 0.06
            )
            // v2.12.6 热股榜·短线：当日动量 + 活跃度为主，流动性把关，稳定性与反转做减分项
            "热股榜·短线" -> mapOf(
                "momentum" to 0.30, "activity" to 0.28, "liquidity" to 0.22,
                "stability" to 0.12, "reversal" to 0.08
            )
            // v2.12.9 中小市值·热门题材：短线资金博弈为主（活跃度 + 当日动量），流动性把关
            "中小市值·热门题材" -> mapOf(
                "activity" to 0.34, "momentum" to 0.28, "liquidity" to 0.20,
                "stability" to 0.10, "reversal" to 0.06, "value" to 0.02
            )
            else -> mapOf(
                "value" to 0.22, "liquidity" to 0.18, "momentum" to 0.20,
                "activity" to 0.15, "stability" to 0.12, "reversal" to 0.05
            )
        }
        val total = raw.values.sum()
        return raw.mapValues { it.value / total }
    }

    /** 硬过滤 */
    fun applyFilters(list: List<ScreenStock>, s: ScreenStrategy): List<ScreenStock> {
        return list.filter { st ->
            if (st.name.contains("ST") || st.name.contains("退")) return@filter false
            s.priceMin?.let { if (st.price < it) return@filter false }
            s.priceMax?.let { if (st.price > it) return@filter false }
            s.mvMin?.let { if (st.totalMv < it) return@filter false }
            s.mvMax?.let { if (st.totalMv > it) return@filter false }
            s.peMax?.let { if (st.pe > it) return@filter false }
            s.peMin?.let { if (st.pe < it) return@filter false }
            s.pbMax?.let { if (st.pb > it) return@filter false }
            s.vrMin?.let { if (st.volumeRatio < it) return@filter false }
            s.vrMax?.let { if (st.volumeRatio > it) return@filter false }
            s.turnoverMin?.let { if (st.turnoverRate < it) return@filter false }
            s.turnoverMax?.let { if (st.turnoverRate > it) return@filter false }
            s.changeMin?.let { if (st.changePct < it) return@filter false }
            s.changeMax?.let { if (st.changePct > it) return@filter false }
            s.amountMin?.let { if (st.amount < it) return@filter false }
            true
        }
    }

    /** 计算选股综合得分（0-100），返回 (股票, 得分, 分项) */
    fun score(list: List<ScreenStock>, strategy: ScreenStrategy): List<Triple<ScreenStock, Double, Map<String, Double>>> {
        if (list.isEmpty()) return emptyList()
        val p = profileFor(strategy)
        val w = weightsFor(strategy)

        // ---- 分项因子 ----
        // 估值（PE/PB 分位，越低越好）
        val peRank = rankPct(list.map { if (it.pe > 0 && it.pe < 500) it.pe else null }, lowerIsBetter = true, na = 25.0)
        val pbRank = rankPct(list.map { if (it.pb > 0 && it.pb < 50) it.pb else null }, lowerIsBetter = true, na = 25.0)
        val value = DoubleArray(list.size) { i ->
            var s = 50.0
            s = s * 0.35 + peRank[i] * 0.65
            s = s * 0.55 + pbRank[i] * 0.45
            s.coerceIn(0.0, 100.0)
        }

        // 流动性（成交额对数的分位，越高越好）
        val liqRank = rankPct(
            list.map { if (it.amount > 0) log10(max(it.amount, 1.0)) else null },
            lowerIsBetter = false, na = 20.0
        )
        val liquidity = DoubleArray(list.size) { liqRank[it] }

        // 动量（当日涨跌 + 60日涨跌）
        val momentum = DoubleArray(list.size) { i ->
            val change = list[i].changePct
            var intraday = 60.0 + change * 5.0
                - max(change - p.momentumChaseStart, 0.0) * p.momentumChasePenalty
                - max(-change + p.momentumDownsideStart, 0.0) * 3.0
            intraday = intraday.coerceIn(5.0, 100.0)
            var s = 50.0 * 0.35 + intraday * 0.65
            val c60 = list[i].change60d
            var trend = 55.0 + c60 * 0.9
                - max(c60 - p.momentumOverheat, 0.0) * 0.8
                - max(-c60 - 20.0, 0.0) * 0.7
            trend = trend.coerceIn(5.0, 100.0)
            s = s * 0.6 + trend * 0.4
            s.coerceIn(5.0, 100.0)
        }

        // 活跃度（量比 + 换手率）
        val activity = DoubleArray(list.size) { i ->
            val vr = if (list[i].volumeRatio > 0) list[i].volumeRatio else 1.0
            var vrScore = 100.0 - abs(vr - p.activityIdealVr) * 15.0
                - max(vr - p.activityHighVr, 0.0) * 8.0
            vrScore = vrScore.coerceIn(5.0, 100.0)
            var s = 50.0 * 0.45 + vrScore * 0.55
            val turn = list[i].turnoverRate
            var tScore = 100.0 - abs(turn - p.activityIdealTurnover) * 8.0
                - max(turn - p.activityHighTurnover, 0.0) * 5.0
            if (turn <= 0) tScore = 40.0
            tScore = tScore.coerceIn(5.0, 100.0)
            s = s * 0.55 + tScore * 0.45
            s.coerceIn(0.0, 100.0)
        }

        // 稳定性（涨跌/换手/量比 惩罚 + 负PE惩罚）
        val stability = DoubleArray(list.size) { i ->
            val st = list[i]
            var s = 78.0
            s -= min(abs(st.changePct), 10.0) * 3.0
            s -= max(st.changePct - p.stabilityHotChange, 0.0) * 5.0
            s -= max(st.turnoverRate - p.stabilityHighTurnover, 0.0) * 2.0
            s -= max(st.volumeRatio - p.stabilityHighVr, 0.0) * 4.0
            if (st.pe <= 0) s -= 18.0
            s.coerceIn(0.0, 100.0)
        }

        // 反转（跌幅可控优先）
        val reversal = DoubleArray(list.size) { i ->
            val change = list[i].changePct
            var s = 100.0 - abs(change - p.reversalIdeal) * 13.0
                - max(-change + p.reversalCollapseStart, 0.0) * p.reversalCollapsePenalty
                - max(change - p.reversalChaseStart, 0.0) * 8.0
            val c60 = list[i].change60d
            s -= max(c60 - 35.0, 0.0) * 0.5
            s -= max(-c60 - 35.0, 0.0) * 0.8
            s.coerceIn(5.0, 100.0)
        }

        val factorMap = mapOf(
            "value" to value, "liquidity" to liquidity, "momentum" to momentum,
            "activity" to activity, "stability" to stability, "reversal" to reversal
        )

        val out = mutableListOf<Triple<ScreenStock, Double, Map<String, Double>>>()
        for (i in list.indices) {
            var total = 0.0
            val parts = HashMap<String, Double>()
            for ((k, weight) in w) {
                val f = factorMap[k] ?: continue
                parts[k] = (f[i] * 100).toInt() / 100.0
                total += f[i] * weight
            }
            out.add(Triple(list[i], total.coerceIn(0.0, 100.0), parts))
        }
        return out.sortedByDescending { it.second }
    }

    /** 百分位排名（平均并列，与 pandas rank(pct=True, method='average') 一致） */
    private fun rankPct(values: List<Double?>, lowerIsBetter: Boolean, na: Double): List<Double> {
        val valid = values.filterNotNull()
        val n = valid.size
        if (n == 0) return values.map { na }
        return values.map { v ->
            if (v == null) na
            else {
                val better = if (lowerIsBetter) valid.count { it > v } else valid.count { it < v }
                val ties = valid.count { it == v }
                ((better + (ties + 1) / 2.0) / n * 100.0).coerceIn(0.0, 100.0)
            }
        }
    }
}
