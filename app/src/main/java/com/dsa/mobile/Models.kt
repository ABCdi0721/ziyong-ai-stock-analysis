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
    val change60d: Double   // 60日涨跌幅%
)

/** v2.5.1 板块标识：创业板→-创，科创板→-科，其他→空（用于股票名称后缀） */
fun boardSuffix(code: String): String = when {
    code.startsWith("300") || code.startsWith("301") -> "-创"
    code.startsWith("688") -> "-科"
    else -> ""
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
    val riskFactors: List<String>
)

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
    val favorite: Boolean = false
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
