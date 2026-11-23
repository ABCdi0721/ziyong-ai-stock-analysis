package com.dsa.mobile

// ============ 数据模型 ============

/** 单根K线 */
data class KLineBar(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

/** 实时行情 */
data class Quote(
    val code: String,
    val name: String,
    val price: Double,
    val prevClose: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val change: Double,
    val changePct: Double,
    val volume: Long,        // 手
    val amount: Double,      // 元
    val turnover: Double,    // 换手率%
    val pe: Double,          // 市盈率(TTM)
    val pb: Double,          // 市净率
    val totalMv: Double,     // 总市值(亿)
    val volumeRatio: Double  // 量比
)

/** 选股快照（东财全市场列表） */
data class ScreenStock(
    val code: String,
    val name: String,
    val price: Double,
    val changePct: Double,
    val volumeRatio: Double,
    val turnoverRate: Double,
    val pe: Double,
    val pb: Double,
    val totalMv: Double,    // 亿
    val amount: Double,     // 元
    val change60d: Double,  // 60日涨跌幅%
    // v2.12.35：可成交性复核字段（无该字段的数据源保持默认 0，按缺失处理）
    val high: Double = 0.0,     // 当日最高价
    val low: Double = 0.0,       // 当日最低价
    val volume: Long = 0L        // 成交量（手/股，0 辅助判断停牌）
)

/** v2.5.1 板块标识：创业板→-创，科创板→-科，其他→空（用于股票名称后缀） */
fun boardSuffix(code: String): String = when {
    code.startsWith("300") || code.startsWith("301") -> "-创"
    code.startsWith("688") -> "-科"
    else -> ""
}

/**
 * 板块分组（用于分层截面归一）。
 * 主板：600/601/603/605 + 000/001/002/003；创业板 300/301；科创板 688/689；北交所 43/83/87/88/920；
 * 900 开头为沪市 B 股，归入 OTHER 不参与 A 股分层。
 */
fun boardGroup(code: String): String = when {
    code.startsWith("688") || code.startsWith("689") -> "STAR"
    code.startsWith("300") || code.startsWith("301") -> "CHINEXT"
    code.startsWith("920") || code.startsWith("4") || code.startsWith("8") -> "BSE"
    code.startsWith("60") || code.startsWith("00") -> "MAIN"
    else -> "OTHER"
}

/** 单股技术分析结果（移植自 daily_stock_analysis/stock_analyzer.py） */
data class AnalysisResult(
    val code: String,
    val trendStatus: String,
    val maAlignment: String,
    val trendStrength: Double,
    val ma5: Double,
    val ma10: Double,
    val ma20: Double,
    val ma60: Double,
    val currentPrice: Double,
    val biasMa5: Double,
    val biasMa10: Double,
    val biasMa20: Double,
    val volumeStatus: String,
    val volumeRatio5d: Double,
    val volumeTrend: String,
    val supportMa5: Boolean,
    val supportMa10: Boolean,
    val macdDif: Double,
    val macdDea: Double,
    val macdBar: Double,
    val macdStatus: String,
    val macdSignal: String,
    val rsi6: Double,
    val rsi12: Double,
    val rsi24: Double,
    val rsiStatus: String,
    val rsiSignal: String,
    val buySignal: String,
    val signalScore: Int,
    val signalReasons: List<String>,
    val riskFactors: List<String>,
    /** v2.12.30 强势突破复核：ATR(14) 波动幅度（元） */
    val atr14: Double = 0.0,
    /** 20 日唐奇安上轨（近20日最高） */
    val donchianHigh20: Double = 0.0,
    /** 20 日唐奇安下轨（近20日最低） */
    val donchianLow20: Double = 0.0,
    /** 近 4 日振幅（%）：(4日最高 − 4日最低)/4日最低×100 */
    val range4d: Double = 0.0,
    /** 当日振幅是否为近 7 日最低（NR7 窄幅） */
    val nr7: Boolean = false,
    /** 现价距 20 日高点 ≤5%（临近突破） */
    val nearBreakout: Boolean = false,
    /** 近 4 日振幅 ≤12%（整理充分） */
    val tightRange: Boolean = false,
    /** 止损参考位：MA10 与近 4 日低点取较低者 */
    val stopRef: Double = 0.0,

    // ===== v2.12.33 移植自 OpenBB technical / quantitative =====
    /** ADX(14) 趋势强度 0..100（0 表示数据不足） */
    val adx14: Double = 0.0,
    /** +DI 方向指标 */
    val plusDi: Double = 0.0,
    /** −DI 方向指标 */
    val minusDi: Double = 0.0,
    /** Choppiness(14) 震荡指标（>61.8 震荡，<38.2 趋势） */
    val chop14: Double = 0.0,
    /** 市场状态：strong_trend/weak_trend/ranging/transition（空串表示数据不足） */
    val regime: String = "",
    /** Aroon 上行（0..100） */
    val aroonUp: Double = 0.0,
    /** Aroon 下行（0..100） */
    val aroonDown: Double = 0.0,
    /** OBV 近 5 日斜率（归一化，正=资金流入） */
    val obvSlope: Double = 0.0,
    /** Chaikin A/D 振荡器(3,10) */
    val adosc: Double = 0.0,
    /** 日锚 VWAP 均价 */
    val vwap: Double = 0.0,
    /** 年化实现波动率（%，close-to-close） */
    val annVol: Double = 0.0,
    /** Parkinson 区间年化波动率（%） */
    val parkinsonVol: Double = 0.0,
    /** Clenow 波动调整动量因子（年化斜率×R²） */
    val clenowFactor: Double = 0.0,
    /** Clenow 拟合度 R² */
    val clenowR2: Double = 0.0,
    /** Hurst 指数（R/S） */
    val hurst: Double = 0.0,
    /** 最近通道突破方向：upside/downside/none */
    val breakoutDir: String = "",
    /** 突破幅度（收盘价−前轨） */
    val breakoutMag: Double = 0.0,
    /** 距上次突破的 K 线根数 */
    val breakoutBars: Int = 0,
    /** 快慢均线交叉方向：bullish/bearish/none */
    val maCrossDir: String = "",
    /** 快慢均线价差（fast−slow） */
    val maCrossDist: Double = 0.0,
    /** 年化夏普比率 */
    val sharpe: Double = 0.0,
    /** 年化索诺比率 */
    val sortino: Double = 0.0,
    /** 卡玛比率 */
    val calmar: Double = 0.0,
    /** 窗口内最大回撤（负值，%） */
    val maxDrawdown: Double = 0.0,
    /** 历史 Var95（5% 分位日收益，%） */
    val var95: Double = 0.0
)

/** v2.12.43 用户录入的现有持仓（可选；成本/股数 >0 才视为有持仓） */
data class Holding(
    val cost: Double,    // 成本价（元）
    val shares: Double,  // 持股数量（股）
    val cash: Double,     // 可用总资金（元，0 表示未填）
    val horizon: String = HORIZON_SHORT,  // 持仓周期：短线/中长线/长线
    val holdDaysBase: Int? = null,       // 手填基准天数；null 用加入自选起算天数
    val holdDaysBaseAt: Long? = null     // 基准填写当天时间戳（累计起点 millis）
) {
    /** 成本、持股、总资金三项都填写才视为有持仓；任一缺失按无持仓视角 */
    val valid: Boolean get() = cost > 0 && shares > 0 && cash > 0

    companion object {
        const val HORIZON_SHORT = "短线"
        const val HORIZON_MID = "中长线"
        const val HORIZON_LONG = "长线"
        val HORIZONS = listOf(HORIZON_SHORT, HORIZON_MID, HORIZON_LONG)

        /** 非法/空周期回落短线 */
        fun normalizeHorizon(raw: String?): String =
            if (raw in HORIZONS) raw!! else HORIZON_SHORT
    }
}

/** 历史报告 */
data class HistoryReport(
    val code: String,
    val name: String,
    val date: String,
    val score: Int,
    val signal: String,
    val report: String,
    /** v2.9.6 具体时间 HH:mm:ss（旧记录为空） */
    val time: String = "",
    /** v2.9.6 唯一标识，删除/去重都按它，避免同股同日互相覆盖 */
    val id: Long = 0L,
    /** v2.12.1 是否已收藏（收藏页据此筛选，不额外占用存储） */
    val favorite: Boolean = false,
    // ===== v2.12.43 结构化决策字段（对齐 TradingAgents schema；旧记录为空，不影响展示） =====
    /** 方向：买入/卖出/观望/未判定/加仓/减仓/持有/清仓 */
    val direction: String = "",
    /** 仓位建议（成仓文本，原样落库） */
    val position: String = "",
    /** 理想买入/加仓价位 */
    val entryPrice: String = "",
    /** 止损价位 */
    val stopPrice: String = "",
    /** 止盈目标价位 */
    val targetPrice: String = "",
    /** 风险等级：低/中/高 */
    val riskLevel: String = "",
    /** 建议持有期：短线/波段/中线 */
    val horizon: String = "",
    /** 本次建议所基于的持仓快照（无持仓时为空） */
    val holdCost: String = "",
    val holdShares: String = "",
    val holdCash: String = ""
)

/** 选股策略 */
data class ScreenStrategy(
    val name: String,
    val techWeight: Double,               // 技术权重
    val priceMin: Double? = null, val priceMax: Double? = null,
    val mvMin: Double? = null, val mvMax: Double? = null,   // 总市值上下限(亿)
    val peMax: Double? = null, val peMin: Double? = null,
    val pbMax: Double? = null,
    val vrMin: Double? = null, val vrMax: Double? = null,   // 量比上下限
    val turnoverMin: Double? = null, val turnoverMax: Double? = null,
    val changeMin: Double? = null, val changeMax: Double? = null,
    val amountMin: Double? = null         // 成交额下限(元)
)

/** 历史选股单只股票（精简） */
data class ScreenHistoryStock(
    val code: String,
    val name: String,
    val score: Double
)

/** 历史选股记录 */
data class ScreenHistory(
    val time: String,          // 完成时间 "2026-10-27 10:30"
    val strategy: String,      // 策略名
    val source: String,        // 数据源：东方财富 / Tushare
    val total: Int,            // 全市场数量
    val matched: Int,          // 符合条件数量
    val stocks: List<ScreenHistoryStock>
)
