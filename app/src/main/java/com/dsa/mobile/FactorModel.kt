package com.dsa.mobile

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 因子建模层（端侧纯 Kotlin，无训练框架、无网络依赖）
 *
 * 对照移植思路：
 * 1. qlib「因子 → 标准化 → 模型打分 → 排序」
 *    - winsorize / zscore / madRobust 对应 qlib 数据预处理（去极值 + 标准化）；
 *    - 端侧不跑监督训练，模型头采用加权线性评分（[rewardScore]），后续可替换为导出权重的轻量前向。
 * 2. FinRL 状态 / 奖励设计思路
 *    - [StockState]：把可计算因子整理成归一化观测向量（对应 RL agent 的 state）；
 *    - 奖励头：把因子期望分映射为风险调整后得分，
 *      reward = 期望收益分 − 风险惩罚 − 成本惩罚（对应 RL 里 reward = Δwealth − λ·risk − cost）；
 *      端侧不做策略梯度反传，仅把奖励结构用于选股排序打分。
 */
object FactorModel {

    // ============ 截面统计 ============

    /** 均值；空序列返回 0 */
    fun mean(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        return x.sum() / x.size
    }

    /** 总体标准差（ddof=0）；空或单元素返回 0 */
    fun std(x: DoubleArray): Double {
        if (x.size <= 1) return 0.0
        val m = mean(x)
        var s = 0.0
        for (v in x) s += (v - m) * (v - m)
        return sqrt(s / x.size)
    }

    /** 中位数；空序列返回 0 */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** 分位数（线性插值，q∈0..1）；空序列返回 0 */
    fun quantile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val pos = q * (s.size - 1)
        val lo = pos.toInt(); val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    /** 下四分位数 P25；空序列返回 0 */
    fun q25(values: List<Double>): Double = quantile(values, 0.25)

    /** 上四分位数 P75；空序列返回 0 */
    fun q75(values: List<Double>): Double = quantile(values, 0.75)

    /** 最大值；空序列返回 0 */
    fun maxVal(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.maxOrNull() ?: 0.0

    // ============ 标准化（qlib 预处理思路）============

    /** 去极值：把超出 mean ± k·std 的值截断到边界；缺失值按 0 参与统计，输出 0 */
    fun winsorize(values: List<Double?>, k: Double = 3.0): DoubleArray {
        val raw = values.map { it ?: 0.0 }.toDoubleArray()
        val m = mean(raw)
        val sd = std(raw)
        if (sd < 1e-9) return raw
        val lo = m - k * sd
        val hi = m + k * sd
        return DoubleArray(raw.size) { raw[it].coerceIn(lo, hi) }
    }

    /** z-score 标准化：(x − mean) / std；std=0 时全 0；缺失值输出 0 */
    fun zscore(values: List<Double?>): DoubleArray {
        val raw = values.map { it ?: 0.0 }.toDoubleArray()
        val m = mean(raw)
        val sd = std(raw)
        if (sd < 1e-9) return DoubleArray(raw.size)
        return DoubleArray(raw.size) { (raw[it] - m) / sd }
    }

    /**
     * MAD 稳健标准化：(x − median) / MAD，截断到 [-5, 5]；缺失值输出 0。
     * 对极端值比 z-score 更稳健（中位数 / 绝对中位差）。
     */
    fun madRobust(values: List<Double?>): DoubleArray {
        val raw = values.map { it ?: 0.0 }
        if (raw.isEmpty()) return DoubleArray(0)
        val med = median(raw)
        val mad = median(raw.map { abs(it - med) })
        if (mad < 1e-9) return DoubleArray(raw.size) { 0.0 }
        return DoubleArray(raw.size) {
            ((raw[it] - med) / mad).coerceIn(-5.0, 5.0)
        }
    }

    // ============ 长尾压缩与分层归一 ============

    /**
     * 正值长尾压缩：ln(1+x)。用于 PE/PB/资金流等跨市值尺度差几个数量级的比值型因子，
     * 先压缩量级再去极值/标准化，避免极端值拉爆均值与标准差；非正、缺失或 NaN 返回 null。
     */
    fun log1pSafe(x: Double?): Double? {
        if (x == null || x.isNaN() || x <= 0.0) return null
        return ln(x + 1.0)
    }

    /** 单组截面分位（平均并列，对齐 pandas rank(pct=True, method='average')），返回 0..100 */
    private fun rankPctCore(valid: List<Double>, v: Double, lowerIsBetter: Boolean): Double {
        val n = valid.size
        if (n == 0) return 50.0
        val better = if (lowerIsBetter) valid.count { it > v } else valid.count { it < v }
        val ties = valid.count { it == v }
        return ((better + (ties + 1) / 2.0) / n * 100.0).coerceIn(0.0, 100.0)
    }

    /**
     * 分组截面分位：同板块内取分位，避免主板 / 创业 / 科创 / 北交所的流动性与估值尺度混排，
     * 把小盘微弱信号放大到与主板同尺度。组内有效样本不足 [minGroup] 时回退全市场分位；缺失值返回 [na]。
     */
    fun rankPctGrouped(
        values: List<Double?>,
        groups: List<String>,
        lowerIsBetter: Boolean,
        na: Double,
        minGroup: Int = 5
    ): List<Double> {
        val globalValid = values.filterNotNull()
        if (globalValid.isEmpty()) return values.map { na }
        val out = values.map { v -> if (v == null) na else rankPctCore(globalValid, v, lowerIsBetter) }.toMutableList()
        val byGroup = HashMap<String, MutableList<Int>>()
        values.indices.forEach { i ->
            if (values[i] != null) byGroup.getOrPut(groups[i]) { mutableListOf() }.add(i)
        }
        byGroup.forEach { _, idxs ->
            val gSize = idxs.size
            if (minGroup > gSize) return@forEach
            val gValid = idxs.map { values[it]!! }
            idxs.forEach { i -> out[i] = rankPctCore(gValid, values[i]!!, lowerIsBetter) }
        }
        return out
    }

    // ============ 事件强度衰减编码 ============

    /**
     * 事件强度指数衰减：exp(−max(distance, 0)/tau)，返回 0..1。
     * 距离阈值越远，衰减后权重越低。tau 为衰减常数（交易日口径）。
     * 说明：端侧快照只有当日截面，tau 先作用于「当日超出阈值的幅度差」；
     * 跨日事件序列（近 N 日龙虎榜/涨停/北向）接入历史数据后，可在此基础上做逐日衰减累计。
     */
    fun eventDecay(distance: Double, tau: Double): Double {
        if (tau <= 1e-9) return 0.0
        return exp(-max(distance, 0.0) / tau).coerceIn(0.0, 1.0)
    }

    // ============ 状态向量（FinRL state 思路）============

    /** 归一化因子观测向量，各分量取值 0..1（中性 0.5） */
    data class StockState(
        val momentum: Double,
        val activity: Double,
        val value: Double,
        val liquidity: Double,
        val stability: Double,
        val reversal: Double
    ) {
        /** 6 维观测向量，顺序固定，便于后续扩展轻量模型前向 */
        fun toVector(): DoubleArray =
            doubleArrayOf(momentum, activity, value, liquidity, stability, reversal)
    }

    /** 由因子分项分（0..100）构造状态向量，归一到 0..1 */
    fun stateOf(factors: Map<String, Double>): StockState = StockState(
        momentum = (factors["momentum"] ?: 50.0) / 100.0,
        activity = (factors["activity"] ?: 50.0) / 100.0,
        value = (factors["value"] ?: 50.0) / 100.0,
        liquidity = (factors["liquidity"] ?: 50.0) / 100.0,
        stability = (factors["stability"] ?: 50.0) / 100.0,
        reversal = (factors["reversal"] ?: 50.0) / 100.0
    )

    // ============ 奖励型评分头（FinRL reward 思路转译）============

    /**
     * 风险调整后得分。
     *
     * 对应 RL 奖励结构：reward = 期望收益 − 风险惩罚 − 成本惩罚。
     * 端侧不做反传训练，仅把该结构用于选股排序：
     *
     * @param factorScore 因子加权期望分（0..100）
     * @param riskPenalty 风险惩罚（0..100）：高波动 / 过热 / 空头趋势 / 负估值等
     * @param costPenalty 成本惩罚（0..100）：极端量比 / 高换手近似交易摩擦成本
     * @param riskWeight 风险权重
     * @param costWeight 成本权重
     */
    fun rewardScore(
        factorScore: Double,
        riskPenalty: Double,
        costPenalty: Double,
        riskWeight: Double = 0.35,
        costWeight: Double = 0.15
    ): Double {
        val reward = factorScore - riskWeight * riskPenalty - costWeight * costPenalty
        return reward.coerceIn(0.0, 100.0)
    }
}
