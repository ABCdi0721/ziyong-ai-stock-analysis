package com.dsa.mobile

import kotlin.math.abs

/**
 * v2.12.35 可成交性 / 风控状态判定（端侧纯规则，无网络依赖）。
 *
 * 口径对照 A 股交易规则：
 * - 主板（60/00 开头）涨跌幅 ±10%；创业板（300/301）、科创板（688/689）±20%；北交所（43/83/87/88/920）±30%。
 * - 停牌 / 当日无成交：成交额为 0 或成交量为 0。
 * - 一字涨停：最高≈最低且封住涨停价，排队也未必成交，买入侧置灰排后；跌停一字同理，卖出侧不可成交。
 * - 字段缺失（high/low/volume 为 0 的数据源）时退化为仅按涨跌幅阈值近似判断，不做一字板强判。
 */
object MarketGuard {

    /** 可成交性状态 */
    enum class Tradability {
        /** 正常可成交 */
        OK,
        /** 停牌 / 当日无成交 */
        SUSPENDED,
        /** 已封涨停（买不进）；一字板时更严格 */
        LIMIT_UP,
        /** 已封跌停（不接 / 卖不出） */
        LIMIT_DOWN
    }

    /** 板块涨跌幅限制（%），主板 10、创业/科创 20、北交 30 */
    fun limitPct(code: String): Double = when {
        code.startsWith("688") || code.startsWith("689") -> 20.0
        code.startsWith("300") || code.startsWith("301") -> 20.0
        code.startsWith("920") || code.startsWith("4") || code.startsWith("8") -> 30.0
        else -> 10.0
    }

    /**
     * 停牌 / 当日无成交判定。
     * 注意：字段缺失 ≠ 停牌。部分快照源（如腾讯 rank）只返回成交额，成交量/最高/最低价保持默认 0，
     * 此时不能把整组缺失字段当成零成交。判定顺序：成交额 > 0 即确认有成交；其次成交量 > 0；
     * 成交字段整组缺失（volume/high/low 全 0）视为源未提供，保守不否决；仅数据齐全且确为零成交才判停牌。
     */
    fun isSuspended(st: ScreenStock): Boolean {
        if (st.amount > 0.0) return false
        if (st.volume > 0L) return false
        // volume/high/low 全为 0 属于字段缺失，不据此判停牌
        if (st.high <= 0.0 && st.low <= 0.0) return false
        return true
    }

    /** 是否一字板（最高≈最低且价格贴着板）；高低价缺失时返回 false，交给涨跌幅阈值近似 */
    fun isOneWord(st: ScreenStock): Boolean {
        if (st.high <= 0.0 || st.low <= 0.0 || st.price <= 0.0) return false
        val rangePct = (st.high - st.low) / st.price * 100.0
        return rangePct <= 0.5
    }

    /** 综合可成交性判定 */
    fun tradability(st: ScreenStock): Tradability {
        if (isSuspended(st)) return Tradability.SUSPENDED
        val limit = limitPct(st.code)
        // 留 0.3pp 余量，避免浮点与四舍五入漏判
        if (st.changePct >= limit - 0.3) return Tradability.LIMIT_UP
        if (st.changePct <= -(limit - 0.3)) return Tradability.LIMIT_DOWN
        return Tradability.OK
    }

    /** 选股买入侧是否应剔除：停牌、封涨停（买不进）、封跌停（不接）一律不入选 */
    fun shouldExcludeForBuy(st: ScreenStock): Boolean =
        tradability(st) != Tradability.OK

    /** 状态中文标签（供结果行 / 明细展示） */
    fun label(st: ScreenStock): String = when (tradability(st)) {
        Tradability.OK -> ""
        Tradability.SUSPENDED -> "停牌/无成交"
        Tradability.LIMIT_UP -> if (isOneWord(st)) "一字涨停·买不进" else "封涨停·买不进"
        Tradability.LIMIT_DOWN -> if (isOneWord(st)) "一字跌停" else "封跌停"
    }

    /** 当日振幅（%），高低价缺失返回 0 */
    fun amplitudePct(st: ScreenStock): Double {
        if (st.high <= 0.0 || st.low <= 0.0 || st.price <= 0.0) return 0.0
        return abs(st.high - st.low) / st.price * 100.0
    }
}
