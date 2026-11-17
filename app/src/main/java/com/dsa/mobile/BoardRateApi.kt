package com.dsa.mobile

import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ============ v2.10.0 封板率预测（次日涨停概率）============
// 数据来源：东方财富 push2ex 打板层接口（涨停池 / 炸板池），零鉴权直连
//   - getTopicZTPool     涨停池：连板数、封板资金、炸板次数、首末封板时间、所属行业、几天几板
//   - getTopicZBPool     炸板池：曾涨停后开板数量与振幅
// 因子权重移植自公开 A 股打板回测结论（5005 只全 A 股 12 年样本）：
//   - 缩量首板晋级率高于放量首板（约 20.3% vs 15.1%）
//   - 「近 5 日涨停次数」是预测次日涨停最强的单因子（约 40% 次日涨停率）
//   - 全市场涨停家数低于 43 只时属情绪冰点，打板胜率显著下降
// 说明：安卓端无法内置 XGBoost / pytdx / 全市场日线库，因此这里用可解释的加权因子
//       模型做近似，而不是直接搬运某个 Python 训练脚本。

/** 涨停池个股 */
data class LimitUpStock(
    val code: String,
    val name: String,
    val price: Double,
    val changePct: Double,
    val amount: Double,       // 成交额（元）
    val turnover: Double,     // 换手率 %
    val boards: Int,          // 连板数 lbc
    val firstSeal: Int,       // 首次封板时间 HHMMSS
    val lastSeal: Int,        // 最后封板时间 HHMMSS
    val brokenCount: Int,     // 炸板次数
    val sealFund: Double,     // 封板资金（元）
    val industry: String,
    val statDays: Int,        // 几天
    val statBoards: Int       // 几板
)

/** 打板市场快照（当日全市场） */
data class BoardMarket(
    val date: String,
    val limitUpCount: Int,      // 涨停家数
    val brokenCount: Int,       // 炸板家数
    val maxBoards: Int,         // 最高连板
    val brokenRate: Double,     // 炸板率 %
    val sentiment: Double,      // 情绪 0~1
    val limitUp: List<LimitUpStock>
) {
    fun sentimentLabel(): String = when {
        sentiment >= 0.75 -> "火热"
        sentiment >= 0.55 -> "偏暖"
        sentiment >= 0.40 -> "中性"
        sentiment >= 0.25 -> "偏冷"
        else -> "冰点"
    }
}

/** 单个因子对概率的贡献 */
data class BoardFactor(
    val name: String,
    val value: String,
    val delta: Double,
    val note: String = ""
)

/** 预测结果 */
data class BoardPrediction(
    val code: String,
    val name: String,
    val probability: Double,        // 次日封板（涨停）概率 %
    val sealProbability: Double,    // 若明日涨停，能封住不开板的概率 %
    val level: String,              // 档位文案
    val summary: String,            // 一句话结论
    val factors: List<BoardFactor>,
    val market: BoardMarket,
    val todayLimitUp: Boolean,
    val todayBoards: Int,
    val recentLimitUps: Int,        // 近 20 日涨停次数
    val dataNote: String
)

object BoardRateApi {

    private const val UT = "7eea3edcaed734bea9cbfc24409ed989"
    private const val HOST = "https://push2ex.eastmoney.com/"
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun headers(): Map<String, String> = mapOf(
        "Referer" to "https://quote.eastmoney.com/",
        "Accept" to "application/json, text/plain, */*"
    )

    /** 取最近几个“可能的交易日”，逐个尝试直到拿到非空涨停池（兼容周末/节假日） */
    private fun dateCandidates(): List<String> {
        val today = LocalDate.now()
        return (0L..3L).map { today.minusDays(it).format(DATE_FMT) }
    }

    private fun fetchPool(api: String, date: String, pageSize: Int = 400): JSONObject? {
        val url = "$HOST$api?ut=$UT&dpt=wz.ztzt&Pageindex=0&pagesize=$pageSize&sort=fbt%3Aasc&date=$date"
        return try {
            val json = JSONObject(Http.get(url, headers()))
            if (json.optInt("rc", -1) != 0) null else json.optJSONObject("data")
        } catch (e: Exception) {
            null
        }
    }

    private fun parseZtPool(data: JSONObject): Pair<String, List<LimitUpStock>> {
        val qdate = data.optInt("qdate", 0).toString()
        val pool = data.optJSONArray("pool") ?: return qdate to emptyList()
        val out = ArrayList<LimitUpStock>(pool.length())
        for (i in 0 until pool.length()) {
            val o = pool.optJSONObject(i) ?: continue
            val code = o.optString("c", "")
            if (code.isBlank()) continue
            val stat = o.optJSONObject("zttj")
            out.add(
                LimitUpStock(
                    code = code,
                    name = o.optString("n", "").replace(" ", ""),
                    price = o.optDouble("p", 0.0) / 1000.0,
                    changePct = o.optDouble("zdp", 0.0),
                    amount = o.optDouble("amount", 0.0),
                    turnover = o.optDouble("hs", 0.0),
                    boards = o.optInt("lbc", 1),
                    firstSeal = o.optInt("fbt", 0),
                    lastSeal = o.optInt("lbt", 0),
                    brokenCount = o.optInt("zbc", 0),
                    sealFund = o.optDouble("fund", 0.0),
                    industry = o.optString("hybk", ""),
                    statDays = stat?.optInt("days", 0) ?: 0,
                    statBoards = stat?.optInt("ct", 0) ?: 0
                )
            )
        }
        // 同一代码可能重复出现，按代码去重
        return qdate to out.distinctBy { it.code }
    }

    /** 情绪模型：涨停家数（43 家为空仓线）+ 炸板率 + 连板高度 */
    private fun sentimentOf(nZt: Int, brokenRate: Double, maxBoards: Int): Double {
        var s = when {
            nZt < 43 -> 0.18 + nZt / 43.0 * 0.14
            nZt < 80 -> 0.32 + (nZt - 43) / 37.0 * 0.30
            nZt < 120 -> 0.62 + (nZt - 80) / 40.0 * 0.20
            else -> 0.82 + min(1.0, (nZt - 120) / 40.0) * 0.14
        }
        // 炸板率以 30% 为中性：越高越差，越低越好
        s -= (brokenRate - 30.0) / 100.0 * 0.55
        if (maxBoards >= 6) s += 0.08 else if (maxBoards >= 4) s += 0.04
        return s.coerceIn(0.05, 0.95)
    }

    /** 拉取当日打板市场快照（涨停池 + 炸板池） */
    fun fetchMarket(): BoardMarket {
        var ztData: JSONObject? = null
        var usedDate = ""
        for (d in dateCandidates()) {
            val data = fetchPool("getTopicZTPool", d) ?: continue
            val pool = data.optJSONArray("pool")
            if (pool != null && pool.length() > 0) {
                ztData = data
                usedDate = d
                break
            }
        }
        val ztJson = ztData ?: throw RuntimeException("涨停池接口无数据（可能非交易日或接口限流）")
        val (qdate, ztList) = parseZtPool(ztJson)

        // 炸板池（失败不阻断主流程）
        var zbTotal = 0
        try {
            val zbData = fetchPool("getTopicZBPool", usedDate)
            zbTotal = zbData?.optInt("tc", 0) ?: 0
            if (zbTotal == 0) {
                val arr = zbData?.optJSONArray("pool")
                zbTotal = arr?.length() ?: 0
            }
        } catch (e: Exception) {
            zbTotal = 0
        }

        val nZt = max(ztList.size, ztJson.optInt("tc", ztList.size))
        val brokenRate = if (nZt + zbTotal <= 0) 0.0
        else zbTotal.toDouble() / (nZt + zbTotal).toDouble() * 100.0
        val maxBoards = ztList.maxOfOrNull { it.boards } ?: 0
        val sent = sentimentOf(nZt, brokenRate, maxBoards)
        val shownDate = if (qdate.length == 8)
            "${qdate.substring(0, 4)}-${qdate.substring(4, 6)}-${qdate.substring(6, 8)}"
        else qdate

        return BoardMarket(
            date = shownDate,
            limitUpCount = nZt,
            brokenCount = zbTotal,
            maxBoards = maxBoards,
            brokenRate = brokenRate,
            sentiment = sent,
            limitUp = ztList
        )
    }

    /** 该股票所属板块的涨停幅度（%） */
    fun limitPctOf(code: String): Double {
        val c = code.trim()
        return when {
            c.startsWith("300") || c.startsWith("301") -> 20.0
            c.startsWith("688") || c.startsWith("689") -> 20.0
            c.startsWith("8") || c.startsWith("4") || c.startsWith("920") -> 30.0
            else -> 10.0
        }
    }

    /** 统计近 N 根日K里的涨停次数（用前复权收盘价环比近似） */
    fun countLimitUps(bars: List<KLineBar>, code: String, lookback: Int): Int {
        if (bars.size < 2) return 0
        val lp = limitPctOf(code)
        val start = max(1, bars.size - lookback)
        var n = 0
        for (i in start until bars.size) {
            val prev = bars[i - 1].close
            if (prev <= 0) continue
            val pct = (bars[i].close / prev - 1.0) * 100.0
            if (pct >= lp - 1.2) n++
        }
        return n
    }

    /** 均线 */
    private fun ma(bars: List<KLineBar>, n: Int): Double {
        if (bars.size < n) return 0.0
        val s = bars.takeLast(n).sumOf { it.close }
        return s / n
    }

    private fun hhmm(t: Int): String {
        if (t <= 0) return "-"
        val h = t / 10000
        val m = (t / 100) % 100
        return "%02d:%02d".format(h, m)
    }

    /**
     * 核心预测：输出次日封板概率与因子明细
     * @param bars 该股日K（至少 25 根，前复权）
     */
    fun predict(
        code: String,
        name: String,
        bars: List<KLineBar>,
        quote: Quote?,
        market: BoardMarket
    ): BoardPrediction {
        val lp = limitPctOf(code)
        val factors = ArrayList<BoardFactor>()

        // ---- 今日是否已涨停 ----
        val ztToday = market.limitUp.firstOrNull { it.code == code }
        val lastBar = bars.lastOrNull()
        val prevBar = bars.getOrNull(bars.size - 2)
        val todayPct = quote?.changePct
            ?: if (lastBar != null && prevBar != null && prevBar.close > 0)
                (lastBar.close / prevBar.close - 1.0) * 100.0 else 0.0
        val isLimitUp = ztToday != null || todayPct >= lp - 0.6

        // ---- 基础概率 ----
        var p: Double
        var baseNote: String
        if (isLimitUp) {
            val boards = ztToday?.boards ?: 1
            p = when {
                boards <= 1 -> 22.0
                boards == 2 -> 26.0
                boards == 3 -> 24.0
                boards == 4 -> 21.0
                else -> 18.0
            }
            baseNote = if (boards <= 1) "今日首板封板" else "今日 ${boards} 连板封板"
        } else {
            p = 6.0
            baseNote = "今日未涨停"
        }
        factors.add(BoardFactor("今日状态", baseNote, p, "打板模型基准分"))

        // ---- 涨停基因：近 5 / 20 日涨停次数（最强单因子）----
        val n5 = countLimitUps(bars, code, 5)
        val n20 = countLimitUps(bars, code, 20)
        val gene = min(14.0, n5 * 4.0 + n20 * 1.2)
        if (gene > 0) {
            p += gene
            factors.add(BoardFactor("涨停基因", "近5日 $n5 次 / 近20日 $n20 次", gene, "历史涨停越密集，次日再封概率越高"))
        } else {
            factors.add(BoardFactor("涨停基因", "近 20 日无涨停", 0.0, "无打板历史，属冷启动"))
        }

        // ---- 量能：缩量优于放量 ----
        val volRatio: Double
        if (bars.size >= 6 && lastBar != null) {
            val avg5 = bars.dropLast(1).takeLast(5).map { it.volume.toDouble() }.average()
            volRatio = if (avg5 > 0) lastBar.volume / avg5 else 1.0
        } else volRatio = 1.0
        val volDelta = when {
            volRatio < 0.8 -> 6.0
            volRatio < 1.2 -> 2.0
            volRatio < 2.0 -> 0.0
            volRatio < 3.0 -> -3.0
            else -> -6.0
        }
        p += volDelta
        val volWord = when {
            volRatio < 0.8 -> "缩量"
            volRatio < 1.2 -> "平量"
            volRatio < 2.0 -> "温和放量"
            else -> "巨量"
        }
        factors.add(
            BoardFactor("量能结构", "$volWord（量比 ${"%.2f".format(volRatio)}）", volDelta,
                "缩量首板晋级率高于放量（20.3% vs 15.1%）")
        )

        // ---- 封板质量：炸板次数 / 首封时间 / 封板资金 ----
        if (ztToday != null) {
            val zbc = ztToday.brokenCount
            val zbcDelta = when {
                zbc == 0 -> 4.0
                zbc == 1 -> -3.0
                zbc == 2 -> -7.0
                else -> -11.0
            }
            p += zbcDelta
            factors.add(BoardFactor("炸板次数", "$zbc 次", zbcDelta, "炸板越多说明抛压越重"))

            val fbt = ztToday.firstSeal
            val fbtDelta = when {
                fbt in 1..100000 -> 6.0     // 10:00 前首封
                fbt in 100001..113000 -> 2.0
                fbt in 113001..140000 -> -1.0
                fbt > 140000 -> -6.0
                else -> 0.0
            }
            p += fbtDelta
            factors.add(BoardFactor("首次封板", hhmm(fbt), fbtDelta, "越早封板，资金决心越强"))

            // 封板资金 / 成交额
            val fundRatio = if (ztToday.amount > 0) ztToday.sealFund / ztToday.amount else 0.0
            val fundDelta = when {
                fundRatio >= 1.5 -> 5.0
                fundRatio >= 0.8 -> 2.5
                fundRatio >= 0.35 -> 0.0
                else -> -3.0
            }
            p += fundDelta
            factors.add(BoardFactor("封单强度", "封单/成交额 ${"%.2f".format(fundRatio)}", fundDelta,
                "封单越厚，次日承接越稳"))

            // 换手率
            val hs = ztToday.turnover
            val hsDelta = when {
                hs in 3.0..15.0 -> 3.0
                hs < 3.0 -> 0.0
                hs < 25.0 -> -2.0
                else -> -5.0
            }
            p += hsDelta
            factors.add(BoardFactor("换手率", "${"%.1f".format(hs)}%", hsDelta, "换手过低缺乏人气，过高易见顶"))

            // 高位风险：几天几板
            if (ztToday.statDays >= 5 && ztToday.statBoards >= 4) {
                p -= 5.0
                factors.add(BoardFactor("高位风险", "${ztToday.statDays} 天 ${ztToday.statBoards} 板", -5.0,
                    "连续拉升后接力风险上升"))
            }
        }

        // ---- 位置：均线结构 ----
        val ma20 = ma(bars, 20)
        val ma5 = ma(bars, 5)
        val close = quote?.price ?: lastBar?.close ?: 0.0
        if (ma20 > 0 && close > 0) {
            val posDelta = when {
                close > ma20 * 1.02 && ma5 > ma20 -> 3.0
                close > ma20 -> 1.0
                close < ma20 * 0.95 -> -4.0
                else -> -1.0
            }
            p += posDelta
            val posWord = if (close > ma20) "站上 MA20" else "跌破 MA20"
            factors.add(BoardFactor("均线位置", posWord, posDelta, "趋势向上更利于资金接力"))
        }

        // ---- 市场情绪 ----
        val sentDelta = (market.sentiment - 0.5) * 24.0   // -12 ~ +12
        p += sentDelta
        factors.add(
            BoardFactor("市场情绪",
                "${market.sentimentLabel()}（涨停 ${market.limitUpCount} 家 / 炸板率 ${"%.0f".format(market.brokenRate)}%）",
                sentDelta,
                "全市场涨停家数低于 43 只属冰点，打板胜率显著下降")
        )

        // ---- 连板梯队（同行业涨停家数）----
        val industry = ztToday?.industry ?: ""
        if (industry.isNotBlank()) {
            val sameInd = market.limitUp.count { it.industry == industry }
            if (sameInd >= 3) {
                p += 3.0
                factors.add(BoardFactor("板块效应", "「$industry」今日 $sameInd 家涨停", 3.0, "板块联动明显"))
            } else {
                factors.add(BoardFactor("板块效应", "「$industry」今日 $sameInd 家涨停", 0.0, "板块效应一般"))
            }
        }

        val prob = p.coerceIn(2.0, 68.0).let { (it * 10).roundToInt() / 10.0 }

        // ---- 若明日涨停，封住不开板的概率 ----
        var seal = 56.0
        seal += (market.sentiment - 0.5) * 22.0
        seal -= (market.brokenRate - 30.0) * 0.35
        if (ztToday != null) {
            seal += when {
                ztToday.brokenCount == 0 -> 12.0
                ztToday.brokenCount <= 2 -> 2.0
                else -> -8.0
            }
            if (ztToday.sealFund > 0 && ztToday.amount > 0) {
                seal += min(10.0, ztToday.sealFund / ztToday.amount * 6.0)
            }
            if (ztToday.firstSeal in 1..100000) seal += 4.0
        }
        val sealP = seal.coerceIn(18.0, 88.0).let { (it * 10).roundToInt() / 10.0 }

        // ---- 档位与结论 ----
        val level = when {
            prob >= 40 -> "很高"
            prob >= 28 -> "较高"
            prob >= 16 -> "一般"
            prob >= 7 -> "较低"
            else -> "很低"
        }
        val summary = buildString {
            append("明日封板概率 $prob%（$level）")
            if (isLimitUp) {
                append("。今日已涨停")
                if (ztToday != null && ztToday.boards > 1) append("（${ztToday.boards} 连板）")
                if (ztToday != null && ztToday.brokenCount > 0) append("，盘中炸板 ${ztToday.brokenCount} 次")
                append("。")
            } else {
                append("。今日未涨停，属首板潜伏，需更强题材或消息催化。")
            }
            append("若明日涨停，封住不开板的概率约 $sealP%。")
            append("当日市场情绪「${market.sentimentLabel()}」，涨停 ${market.limitUpCount} 家、炸板率 ${"%.0f".format(market.brokenRate)}%。")
        }

        val note = "数据来源：东方财富打板接口（${market.date} 涨停池 / 炸板池）+ 腾讯日K。\n" +
                "模型：移植公开 A 股打板回测结论的加权因子近似（缩量优于放量、近5日涨停次数为最强因子、涨停家数 43 家为空仓线），非深度学习模型；安卓端无法内置 XGBoost/pytdx 与全市场日线库。\n" +
                "概率为统计参考，不构成投资建议。"

        return BoardPrediction(
            code = code,
            name = name,
            probability = prob,
            sealProbability = sealP,
            level = level,
            summary = summary,
            factors = factors,
            market = market,
            todayLimitUp = isLimitUp,
            todayBoards = ztToday?.boards ?: if (isLimitUp) 1 else 0,
            recentLimitUps = n20,
            dataNote = note
        )
    }
}
