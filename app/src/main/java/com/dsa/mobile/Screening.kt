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

    // ---- 事件强度衰减常数（τ）：端侧快照只有当日截面，先作用于超阈值距离；跨日序列待接历史数据 ----
    private const val TAU_CHANGE = 5.0    // 涨跌幅（百分点）
    private const val TAU_VR = 1.5        // 量比（倍）
    private const val TAU_TURN = 8.0      // 换手率（百分点）

    /** 事件强度：1 − exp(−d/τ)；d 为超阈值距离，0→0，越极端越趋近 1（指数衰减编码的端侧退化口径） */
    private fun eventStrength(d: Double, tau: Double): Double =
        1.0 - FactorModel.eventDecay(max(d, 0.0), tau)

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
        // v2.12.30 短线·强势突破：强势趋势 + 临近突破 + 风控复核
        // 整合 GitHub #1 short-term-stock-picker（市值/均线多头/换手/量比）、#2 ashare-quant（盈利过滤/振幅/EMA）、
        // #4 ta_cn（MACD/RSI/BOLL 国内口径）、#5 ai_quant_trade（规则投票+风控优先）、#7 NSE-Stock-Scanner（唐奇安突破/窄幅整理/盈亏比）
        ScreenStrategy(
            "短线·强势突破", 0.55,
            priceMin = 3.0, priceMax = 100.0, mvMin = 20.0, mvMax = 150.0,
            peMin = 0.0, peMax = 200.0,
            vrMin = 1.3, turnoverMin = 1.5, turnoverMax = 12.0,
            changeMin = 1.5, changeMax = 9.5, amountMin = 8e7
        ),
        // #1 short-term-stock-picker 原貌：小中市值 + 均线多头 + 温和放量 + 换手稳定
        ScreenStrategy(
            "#1 均线多头强势", 0.55,
            priceMin = 3.0, priceMax = 100.0, mvMin = 20.0, mvMax = 150.0,
            peMin = 0.0, peMax = 200.0,
            vrMin = 1.0, turnoverMin = 0.5, turnoverMax = 10.0,
            changeMin = 0.0, changeMax = 7.0, amountMin = 5e7
        ),
        // #2 ashare-quant-strategies #020 回调买入原貌：低振幅回踩、排除涨跌停、不追高
        ScreenStrategy(
            "#2 回调低振幅买入", 0.40,
            priceMin = 3.0, priceMax = 120.0, peMin = 0.0, peMax = 120.0,
            vrMin = 0.6, turnoverMin = 0.5, turnoverMax = 8.0,
            changeMin = -3.0, changeMax = 3.0, amountMin = 5e7
        ),
        // #4 ta_cn 算子参考：MACD/RSI/BOLL/KDJ 多指标共振，均衡多因子口径
        ScreenStrategy(
            "#4 指标共振选股", 0.35,
            priceMin = 3.0, priceMax = 200.0, mvMin = 30.0, peMax = 100.0,
            vrMin = 0.8, turnoverMin = 1.0,
            changeMin = -2.0, changeMax = 6.0, amountMin = 8e7
        ),
        // #5 ai_quant_trade 本地双均线策略原貌：MA5 上穿 MA10 动量 + 量能确认
        ScreenStrategy(
            "#5 双均线动量", 0.50,
            priceMin = 3.0, priceMax = 200.0, mvMin = 20.0, peMax = 150.0,
            vrMin = 1.2, turnoverMin = 1.0,
            changeMin = 0.5, changeMax = 8.0, amountMin = 8e7
        ),
        // #7 NSE-Stock-Scanner 原貌：临近 20 日高点 + 窄幅整理待突破，波动可控
        ScreenStrategy(
            "#7 唐奇安窄幅突破", 0.55,
            priceMin = 3.0, priceMax = 100.0, mvMin = 20.0, mvMax = 150.0,
            peMin = 0.0, peMax = 200.0,
            vrMin = 1.0, turnoverMin = 1.0, turnoverMax = 12.0,
            changeMin = 0.0, changeMax = 7.0, amountMin = 8e7
        ),
        // 自定义策略...（参数在 App 内编辑保存）
        ScreenStrategy("自定义策略...", 0.35)
    )

    /** 自定义策略名：始终固定在下拉列表最后一项，不参与排序 */
    const val CUSTOM_NAME = "自定义策略..."

    /**
     * 界面展示名：去掉内部编号前缀（如 "#1 均线多头强势" → "均线多头强势"）。
     * 内部 key / 持久化顺序 / 策略匹配仍保留原名，策略本体与保存状态不变，仅展示层不显示 #序号。
     */
    fun displayName(raw: String): String = raw.replace(Regex("^#\\d+\\s*"), "")

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
            "短线·强势突破" -> {
                // 追强但防过热：容忍较大涨幅与活跃度，乖离/超买/极端量能仍扣分
                p.momentumChaseStart = 6.0; p.momentumChasePenalty = 12.0
                p.momentumOverheat = 50.0
                p.activityIdealVr = 2.4; p.activityIdealTurnover = 5.0
                p.activityHighVr = 8.0; p.activityHighTurnover = 18.0
                p.stabilityHotChange = 8.0; p.stabilityHighTurnover = 14.0; p.stabilityHighVr = 6.0
            }
            "#1 均线多头强势" -> {
                // 均线多头 + 温和放量：涨幅适度容忍，量比/换手落在稳定区间
                p.momentumChaseStart = 5.0; p.momentumChasePenalty = 14.0
                p.momentumOverheat = 45.0
                p.activityIdealVr = 1.8; p.activityIdealTurnover = 4.0
                p.activityHighVr = 6.0; p.activityHighTurnover = 14.0
                p.stabilityHotChange = 7.0; p.stabilityHighTurnover = 12.0; p.stabilityHighVr = 5.0
            }
            "#2 回调低振幅买入" -> {
                // 回踩低振幅：不追高，反转与稳定性优先，量能温和即可
                p.momentumChaseStart = 2.0; p.momentumChasePenalty = 18.0; p.momentumDownsideStart = -2.0
                p.reversalIdeal = -1.5; p.reversalCollapseStart = -6.0
                p.activityIdealVr = 1.2; p.activityIdealTurnover = 2.5
                p.activityHighVr = 4.0; p.activityHighTurnover = 9.0
                p.stabilityHotChange = 4.0; p.stabilityHighTurnover = 8.0; p.stabilityHighVr = 3.5
            }
            "#5 双均线动量" -> {
                // 双均线动量：动量主导，容忍较大涨幅，量能确认不罚过重
                p.momentumChaseStart = 6.0; p.momentumChasePenalty = 12.0
                p.momentumOverheat = 52.0
                p.activityIdealVr = 2.0; p.activityIdealTurnover = 4.5
                p.activityHighVr = 7.0; p.activityHighTurnover = 16.0
                p.stabilityHotChange = 8.0
            }
            "#7 唐奇安窄幅突破" -> {
                // 窄幅整理待突破：追强但防过热，波动过大扣分，活跃度适中
                p.momentumChaseStart = 5.5; p.momentumChasePenalty = 13.0
                p.momentumOverheat = 45.0
                p.activityIdealVr = 1.8; p.activityIdealTurnover = 4.0
                p.activityHighVr = 7.0; p.activityHighTurnover = 15.0
                p.stabilityHotChange = 7.0; p.stabilityHighTurnover = 13.0; p.stabilityHighVr = 5.0
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
            // v2.12.30 短线·强势突破：动量 + 活跃度主导，流动性把关，稳定性/反转/估值做辅助与减分
            "短线·强势突破" -> mapOf(
                "momentum" to 0.34, "activity" to 0.30, "liquidity" to 0.16,
                "stability" to 0.10, "reversal" to 0.06, "value" to 0.04
            )
            "#1 均线多头强势" -> mapOf(
                "momentum" to 0.34, "activity" to 0.26, "liquidity" to 0.18,
                "stability" to 0.12, "reversal" to 0.06, "value" to 0.04
            )
            "#2 回调低振幅买入" -> mapOf(
                "reversal" to 0.28, "stability" to 0.24, "momentum" to 0.20,
                "activity" to 0.14, "liquidity" to 0.10, "value" to 0.04
            )
            "#4 指标共振选股" -> mapOf(
                "momentum" to 0.20, "value" to 0.18, "liquidity" to 0.18,
                "stability" to 0.18, "activity" to 0.16, "reversal" to 0.10
            )
            "#5 双均线动量" -> mapOf(
                "momentum" to 0.40, "activity" to 0.24, "liquidity" to 0.16,
                "stability" to 0.10, "reversal" to 0.06, "value" to 0.04
            )
            "#7 唐奇安窄幅突破" -> mapOf(
                "momentum" to 0.32, "activity" to 0.28, "liquidity" to 0.16,
                "stability" to 0.12, "reversal" to 0.06, "value" to 0.06
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
            // v2.12.35：可成交性复核——停牌/无成交、封涨停（买不进）、封跌停（不接）一律剔除
            if (MarketGuard.shouldExcludeForBuy(st)) return@filter false
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
    fun score(
        list: List<ScreenStock>,
        strategy: ScreenStrategy,
        riskW: Double = 0.35,
        costW: Double = 0.15
    ): List<Triple<ScreenStock, Double, Map<String, Double>>> {
        if (list.isEmpty()) return emptyList()
        val p = profileFor(strategy)
        val w = weightsFor(strategy)
        val groups = list.map { boardGroup(it.code) }

        // ---- 分项因子 ----
        // qlib 预处理：截面去极值（winsorize 3σ），降低极端值对分位映射的拉偏；缺失值保持 null。
        // PE/PB 为比值型重尾因子，先 ln(1+x) 压缩量级再去极值，避免极端值拉爆 mean/std。
        val peRaw = winsorizeCrossSection(list.map {
            FactorModel.log1pSafe(if (it.pe > 0 && it.pe < 500) it.pe else null)
        })
        val pbRaw = winsorizeCrossSection(list.map {
            FactorModel.log1pSafe(if (it.pb > 0 && it.pb < 50) it.pb else null)
        })
        val amtRaw = winsorizeCrossSection(list.map { if (it.amount > 0) log10(max(it.amount, 1.0)) else null })

        // 估值（PE/PB 分位，越低越好）；分位在同板块内取，避免跨流动性层级混排
        val peRank = FactorModel.rankPctGrouped(peRaw, groups, lowerIsBetter = true, na = 25.0)
        val pbRank = FactorModel.rankPctGrouped(pbRaw, groups, lowerIsBetter = true, na = 25.0)
        val value = DoubleArray(list.size) { i ->
            var s = 50.0
            s = s * 0.35 + peRank[i] * 0.65
            s = s * 0.55 + pbRank[i] * 0.45
            s.coerceIn(0.0, 100.0)
        }

        // 流动性（成交额对数的分位，越高越好）；同板块内取分位
        val liqRank = FactorModel.rankPctGrouped(amtRaw, groups, lowerIsBetter = false, na = 20.0)
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
            // FinRL 思路奖励头：期望收益分 − 风险惩罚 − 成本惩罚（端侧不做反传训练，仅用于排序）
            val riskPenalty = riskPenalty(list[i], stability[i], p)
            val costPenalty = costPenalty(list[i], p)
            val adjusted = FactorModel.rewardScore(total, riskPenalty, costPenalty, riskW, costW)
            // v2.12.35：风险/成本惩罚明细回传，供结果行展开打分依据（0..100，越高越不利）
            parts["risk"] = (riskPenalty * 100).toInt() / 100.0
            parts["cost"] = (costPenalty * 100).toInt() / 100.0
            out.add(Triple(list[i], adjusted, parts))
        }
        return out.sortedByDescending { it.second }
    }

    /** qlib 预处理：对截面有效值做 3σ 去极值，缺失值保持 null */
    private fun winsorizeCrossSection(values: List<Double?>, k: Double = 3.0): List<Double?> {
        val validIdx = values.indices.filter { values[it] != null }
        if (validIdx.isEmpty()) return values
        val raw = validIdx.map { values[it]!! }
        val w = FactorModel.winsorize(raw, k)
        val out = values.toMutableList()
        validIdx.forEachIndexed { j, idx -> out[idx] = w[j] }
        return out
    }

    /** FinRL 思路风险惩罚（0..100，越高风险越大）：稳定性差 + 负估值 + 当日过热/急跌（事件强度经指数衰减平滑） */
    private fun riskPenalty(st: ScreenStock, stabilityScore: Double, p: Profile): Double {
        var pen = 100.0 - stabilityScore
        if (st.pe <= 0) pen += 12.0
        val hotD = max(st.changePct - p.stabilityHotChange, 0.0)
        if (hotD > 0) pen += 40.0 * eventStrength(hotD, TAU_CHANGE)
        val downD = max(p.momentumDownsideStart - st.changePct, 0.0)
        if (downD > 0) pen += 30.0 * eventStrength(downD, TAU_CHANGE)
        return pen.coerceIn(0.0, 100.0)
    }

    /** FinRL 思路成本惩罚（0..100）：极端量比 / 高换手近似交易摩擦成本（事件强度经指数衰减平滑） */
    private fun costPenalty(st: ScreenStock, p: Profile): Double {
        var pen = 0.0
        val vrD = max(st.volumeRatio - p.activityHighVr, 0.0)
        if (vrD > 0) pen += 60.0 * eventStrength(vrD, TAU_VR)
        val turnD = max(st.turnoverRate - p.activityHighTurnover, 0.0)
        if (turnD > 0) pen += 30.0 * eventStrength(turnD, TAU_TURN)
        return pen.coerceIn(0.0, 100.0)
    }
}
