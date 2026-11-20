package com.dsa.mobile

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TradingAgents 多智能体分析流水线（Kotlin 移植版）
 * 角色链路：四分析师（基本面/情绪/新闻/技术）→ 多空研究员辩论 → 交易员 → 风控 → 组合经理
 * 与原版 TradingAgents 对齐，数据源换为国内可访问的腾讯/Tushare + 新闻检索。
 *
 * v2.12.0 容错策略（三层）：
 *  1) 单次调用内部：限流(429)/5xx/超时/断流 退避重试；
 *  2) 单套配置失败：自动轮换到用户已保存的其他 AI 配置（跨供应商兜底）；
 *  3) 全部不可用：用本地规则依据真实行情/技术指标生成该环节结论，
 *     报告里不会出现「失败」「报错」字样，也不会中断整个流程。
 */
object MultiAgent {

    data class Result(
        val code: String,
        val name: String,
        val date: String,
        val fundamentals: String,
        /** v2.12.25：该股所属行业与概念板块（本地取数，不调用模型，仅列名不分析） */
        val sectors: String,
        val sentiment: String,
        val newsImpact: String,
        val technical: String,
        val bull: String,
        val bear: String,
        /** v2.12.43 研究经理汇总：多空辩论后裁决更强一方（对齐 TradingAgents Research Manager） */
        val research: String,
        val trader: String,
        val risk: String,
        val portfolio: String,
        /** 由本地规则补全的环节数量（0 表示全部由模型生成） */
        val degraded: Int = 0
    ) {
        /** 从组合经理终审 → 交易员决策 → 全文兜底，逐级提取最终信号；解析不出记「未判定」 */
        val finalSignal: String
            get() {
                // 1) 组合经理「最终决策」行（终审优先）
                lineValue(portfolio, "最终决策")?.let { signalFromText(it).ifBlank { null } }?.let { return it }
                // 2) 回退交易员「方向」行
                lineValue(trader, "方向")?.let { signalFromText(it).ifBlank { null } }?.let { return it }
                // 3) 全文兜底；仍解析不出方向 → 未判定（区别于有依据的观望，不静默归为 Hold）
                return signalFromText(portfolio.ifBlank { trader }).ifBlank { "未判定" }
            }

        /** v2.12.43：是否未能解析出明确方向（哨兵，不可交易信号） */
        val undecided: Boolean get() = finalSignal == "未判定"

        /** 从「信心评分」行提取 0-100 分；避免误抓正文价格或位数 */
        val finalScore: Int
            get() {
                lineValue(portfolio, "信心评分", "评分", "置信")?.let { v ->
                    Regex("""(\d{1,3})""").find(v)?.groupValues?.get(1)?.toIntOrNull()
                        ?.let { return it.coerceIn(0, 100) }
                }
                val m = Regex("""(?:评分|信心|置信)[：:\s]*(\d{1,3})""").find(portfolio)
                val v = m?.groupValues?.get(1)?.toIntOrNull() ?: 50
                return v.coerceIn(0, 100)
            }

        // ===== v2.12.43 结构化决策字段（从各角色固定行抽取；抽不到为空串，不编造） =====
        /** 仓位建议（交易员「仓位建议」优先，回退经理「仓位上限」） */
        val position: String
            get() = (lineValue(trader, "仓位建议") ?: lineValue(portfolio, "仓位上限")).orEmpty().trim()
        /** 理想买入/加仓价位 */
        val entryPrice: String
            get() = lineValue(trader, "理想买入点", "买入价位", "加仓价位").orEmpty().trim()
        /** 止损价位（交易员优先，回退风控建议） */
        val stopPrice: String
            get() = (lineValue(trader, "止损价位", "止损位") ?: lineValue(risk, "止损")).orEmpty().trim()
        /** 止盈目标价位 */
        val targetPrice: String
            get() = lineValue(trader, "止盈目标", "目标价位", "止盈位").orEmpty().trim()
        /** 风险等级：低/中/高 */
        val riskLevel: String
            get() = lineValue(risk, "风险等级").orEmpty().trim()
        /** 建议持有期：短线/波段/中线 */
        val horizon: String
            get() = (lineValue(trader, "持有期") ?: lineValue(portfolio, "持有期")).orEmpty().trim()

        /** 拼接为完整报告（供报告页渲染） */
        fun toReport(): String = buildString {
            appendLine("## 多智能体分析（对齐 TradingAgents 全链路）")
            appendLine()
            appendLine("> 本报告由 4 位分析师 + 多空研究员辩论 + 交易员 + 风控 + 组合经理 协作生成，仅供参考，不构成投资建议。")
            if (degraded > 0) {
                appendLine(">")
                appendLine("> 说明：本次有 $degraded 个环节由本地规则依据同一份真实行情与技术指标补全，结论口径与数据来源保持一致。")
            }
            appendLine()
            appendLine("## 基本面分析师")
            appendLine(fundamentals.ifBlank { "数据不足，本节未生成。" })
            appendLine()
            appendLine("## 所属板块")
            appendLine(sectors.ifBlank { "板块归属数据暂未取得。" })
            appendLine()
            appendLine("## 情绪分析师")
            appendLine(sentiment.ifBlank { "数据不足，本节未生成。" })
            appendLine()
            appendLine("## 新闻分析师")
            appendLine(newsImpact.ifBlank { "数据不足，本节未生成。" })
            appendLine()
            appendLine("## 技术分析师")
            appendLine(technical.ifBlank { "数据不足，本节未生成。" })
            appendLine()
            appendLine("## 多头研究员")
            appendLine(bull.ifBlank { "数据不足，本节未生成。" })
            appendLine()
            appendLine("## 空头研究员")
            appendLine(bear.ifBlank { "数据不足，本节未生成。" })
            appendLine()
            appendLine("## 研究经理汇总")
            appendLine(research.ifBlank { "数据不足，本节未生成。" })
            appendLine()
            appendLine("## 交易员决策")
            appendLine(trader.ifBlank { "数据不足，本节未生成。" })
            appendLine()
            appendLine("## 风险管理评估")
            appendLine(risk.ifBlank { "数据不足，本节未生成。" })
            appendLine()
            appendLine("## 组合经理最终决策")
            appendLine(portfolio.ifBlank { "数据不足，本节未生成。" })
        }
    }

    // ===================== 角色提示词 =====================
    // v2.9.5 省 token 关键：所有角色共用同一段 system（逐字相同）+ 同一段数据块，
    // 供应商（DeepSeek/OpenAI）的前缀缓存才能命中，重复数据只按缓存价计费。

    private const val SYSTEM_BASE =
        "你是A股投研团队的一员，正在参与多智能体协作分析：四位分析师（基本面/情绪/新闻/技术）→ 多空研究员辩论 → 交易员决策 → 风控评估 → 组合经理终审。\n" +
            "硬性要求：\n" +
            "1) 只能基于下面给出的数据与新闻做判断，数据不足时明确说明缺什么，绝不编造任何数字、新闻标题或日期；\n" +
            "2) 输出中文，直接给结论，不写「好的」「以下是」这类解释性前言；\n" +
            "3) 结构用「## 标题」分点，末尾给一个明确倾向；\n" +
            "4) 严格遵守本次任务要求的字数上限，宁短不长；\n" +
            "5) 不要使用 ** 加粗、* 斜体等 Markdown 装饰符号，只用纯文本。"

    private const val TASK_ANALYSTS =
        "【本次任务】你是投研团队的四位分析师，请一次性依次完成四段分析。每段 150-220 字，段落标题必须逐字使用下面四个：\n" +
            "## 基本面分析\n（从估值与基本面数据评估内在价值、估值高低、安全边际）\n" +
            "## 情绪分析\n（从近期新闻判断市场情绪倾向，及是否可能反转）\n" +
            "## 新闻分析\n（解读新闻事件对公司与股价的影响，利好/利空/中性）\n" +
            "## 技术分析\n（从技术指标判断形态、买卖时机与支撑压力）\n" +
            "每段末尾给明确倾向（看多/看空/中性）。不要输出这四段以外的任何内容，也不要解释你在做什么。"

    private const val TASK_BULL =
        "【本次任务】你是多头研究员。审视上面的四位分析师报告，找出最强的看多逻辑，组织成结构化多头论点（每点给出依据），为交易员提供买入理由。" +
            "200-320字，分点列出，客观不夸张。"

    private const val TASK_BEAR =
        "【本次任务】你是空头研究员。审视上面的四位分析师报告，找出最强的看空逻辑与风险点，组织成结构化空头论点（每点给出依据），为交易员提供风险警示。" +
            "200-320字，分点列出，客观不夸张。"

    private const val TASK_RESEARCH_MGR =
        "【本次任务】你是研究经理。权衡上面的多头与空头观点，裁决哪一方论据更强，给出研究层面的倾向。输出格式（严格按此格式）：\n" +
            "## 研究经理汇总\n倾向：偏多/偏空/均衡\n关键依据：...（80字内）\n主要风险：...（60字内）\n" +
            "不要重复罗列多空原文，只给裁决结论。"

    private const val TASK_TRADER =
        "【本次任务】你是交易员。综合上面的研究经理汇总、多空辩论与技术面要点，做出交易决策。输出格式（严格按此格式）：\n" +
            "## 交易决策\n方向：买入/卖出/加仓/减仓/持有/清仓/观望\n" +
            "仓位建议：x成仓（有持仓则为现有仓位下加/减到几成；无持仓则为新建仓位几成）\n" +
            "理想买入点：xxx元（或区间）\n止损价位：xxx元\n止盈目标：xxx元\n持有期：短线(数日)/波段(数周)/中线(数月)\n理由：150字内\n" +
            "要求决策果断、给出具体价位，基于上述证据，不模棱两可。"

    private const val TASK_RISK =
        "【本次任务】你是风险管理专员。评估上面交易员决策的风险：市场波动风险、流动性风险、下行空间、止损是否合理；" +
            "若提供了现有持仓，需结合持仓成本与仓位占比评估集中度、浮亏承受与加减仓风险。" +
            "给出风险评估等级（低/中/高）与改进建议。输出格式（严格按此格式）：\n" +
            "## 风险评估\n风险等级：低/中/高\n主要风险：...\n止损是否合理：是/否，建议...\n仓位建议：...\n150字内。"

    private const val TASK_PORTFOLIO =
        "【本次任务】你是投资组合经理。综合上面的交易员决策与风控评估，做出最终决定：批准、否决或调整该交易。输出格式（严格按此格式）：\n" +
            "## 组合经理最终决策\n最终决策：批准买入/批准卖出/观望/否决\n仓位上限：x成\n持有期：短线/波段/中线\n信心评分：0-100整数\n理由：150字内\n" +
            "必须给出最终决策与信心评分，作为整个分析流程的结论。"

    // ===================== 文本工具 =====================

    /** 决策文本 → 方向标签；强烈信号优先；加仓归买入、减仓/清仓归卖出；无法判定返回空串 */
    private fun signalFromText(text: String): String = when {
        text.contains("强烈买入") -> "强烈买入"
        text.contains("强烈卖出") -> "强烈卖出"
        text.contains("加仓") || text.contains("买入") -> "买入"
        text.contains("减仓") || text.contains("清仓") || text.contains("卖出") -> "卖出"
        text.contains("观望") || text.contains("持有") || text.contains("否决") -> "观望"
        else -> ""
    }

    /** 抽取指定标签行冒号后的内容，如「最终决策：批准买入」→「批准买入」；找不到返回 null */
    private fun lineValue(text: String, vararg labels: String): String? {
        text.lineSequence().forEach { line ->
            labels.forEach { label ->
                if (line.contains(label)) {
                    val after = line.substringAfter('：').let { if (it == line) line.substringAfter(':') else it }
                    if (after.isNotBlank() && after != line) return after.trim()
                }
            }
        }
        return null
    }

    /** 去掉模型自带的首行 ## 标题，避免与报告页自动添加的角色标题重复 */
    private fun stripHeading(text: String): String {
        val t = text.trim()
        if (!t.startsWith("##")) return t
        return t.substringAfter('\n', "").trim().ifBlank { t }
    }

    /** 把一次调用里输出的四段分析师内容拆开；某段标题缺失时返回空串 */
    private fun splitAnalystSections(text: String): List<String> {
        val titles = listOf("基本面分析", "情绪分析", "新闻分析", "技术分析")
        val marks = titles.map { t ->
            Regex("(?m)^#{2,3}\\s*" + Regex.escape(t) + "[^\\n]*$").find(text)?.range?.first ?: -1
        }
        return titles.indices.map { i ->
            val start = marks[i]
            if (start < 0) "" else {
                val headerEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it + 1 }
                val nextStart = marks.filter { it > start }.minOrNull() ?: text.length
                text.substring(headerEnd, maxOf(headerEnd, nextStart)).trim()
            }
        }
    }

    private fun f2(v: Double) = String.format("%.2f", v)

    /** 股数显示：整数股去掉小数位 */
    private fun formatShares(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else f2(v)

    /** 从 Analyzer.buildAnalysisSummary 的文本里提取可用的关键数据，供本地兜底结论使用 */
    private data class Snap(
        val price: Double, val priceText: String, val pct: Double,
        val pe: Double, val pb: Double, val mv: Double,
        val turnover: Double, val vr: Double,
        val trend: String, val ma5: Double, val ma10: Double, val ma20: Double,
        val macd: String, val rsi: String, val score: Int, val signal: String,
        val reasons: String, val risks: String
    )

    private fun parseSnap(summary: String): Snap {
        fun num(pattern: String, def: Double = 0.0): Double =
            Regex(pattern).find(summary)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: def

        val priceText = Regex("最新价：([\\d.]+)\\s*元").find(summary)?.groupValues?.get(1) ?: ""
        val score = Regex("综合评分：(\\d{1,3})/100").find(summary)?.groupValues?.get(1)?.toIntOrNull() ?: 50
        val signal = Regex("综合评分:\\s*\\d{1,3}/100，信号：([^\\n]+)").find(summary)?.groupValues?.get(1)?.trim()
            ?: Regex("信号：([^\\n]+)").find(summary)?.groupValues?.get(1)?.trim() ?: "观望"
        return Snap(
            price = num("最新价：([\\d.]+)\\s*元"),
            priceText = priceText,
            pct = num("涨跌幅：([-\\d.]+)%"),
            pe = num("市盈率\\(TTM\\)：([-\\d.]+)"),
            pb = num("市净率：([\\d.]+)"),
            mv = num("总市值：([\\d.]+)"),
            turnover = num("换手率：([\\d.]+)%"),
            vr = num("量比：([\\d.]+)"),
            trend = Regex("趋势状态：([^（]+)").find(summary)?.groupValues?.get(1)?.trim() ?: "",
            ma5 = num("MA5=([\\d.]+)"),
            ma10 = num("MA10=([\\d.]+)"),
            ma20 = num("MA20=([\\d.]+)"),
            macd = Regex("MACD：[^\\n]*").find(summary)?.value?.removePrefix("MACD：")?.trim() ?: "",
            rsi = Regex("RSI：[^\\n]*").find(summary)?.value?.removePrefix("RSI：")?.trim() ?: "",
            score = score,
            signal = signal,
            reasons = Regex("看多理由：([^\\n]+)").find(summary)?.groupValues?.get(1)?.trim() ?: "",
            risks = Regex("风险因素：([^\\n]+)").find(summary)?.groupValues?.get(1)?.trim() ?: ""
        )
    }

    private fun moodWord(pct: Double): String = when {
        pct >= 3.0 -> "放量或跳空上行，短线情绪偏乐观"
        pct >= 0.0 -> "小幅波动，情绪中性偏暖"
        pct >= -3.0 -> "小幅回落，情绪中性偏谨慎"
        else -> "跌幅较大，短线情绪偏悲观"
    }

    private fun valuationWord(pe: Double): String = when {
        pe <= 0 -> "市盈率为负或缺失（亏损或数据缺失），暂不做估值高低判断"
        pe < 15 -> "市盈率处于偏低区间，估值上有一定安全边际"
        pe < 35 -> "市盈率处于合理区间"
        else -> "市盈率偏高，需警惕估值回调压力"
    }

    // ===================== 本地兜底结论（不调用模型） =====================

    private fun localFundamentals(s: Snap): String = buildString {
        if (s.price <= 0) {
            append("行情与估值数据本次未能取得，基本面判断暂以公开财报口径为准，未做主观推测。倾向：中性。")
            return@buildString
        }
        append("当前价 ${f2(s.price)} 元，当日涨跌 ${f2(s.pct)}%。")
        if (s.mv > 0) append("市盈率(TTM) ${f2(s.pe)}、市净率 ${f2(s.pb)}、总市值 ${f2(s.mv)} 亿元，")
        append("换手率 ${f2(s.turnover)}%、量比 ${f2(s.vr)}。")
        append(valuationWord(s.pe)).append("。")
        append("综合评分 ${s.score}/100，信号为「${s.signal}」。倾向：")
        append(
            when {
                s.score >= 70 -> "看多"
                s.score >= 45 -> "中性"
                else -> "看空"
            }
        )
        append("。")
    }

    private fun localSentiment(s: Snap): String = buildString {
        if (s.price <= 0) {
            append("行情数据本次未能取得，情绪面无法量化。倾向：中性。")
            return@buildString
        }
        append("当日涨跌 ${f2(s.pct)}%，换手率 ${f2(s.turnover)}%，量比 ${f2(s.vr)}，")
        append(moodWord(s.pct)).append("。")
        append("技术面综合评分 ${s.score}/100（${s.signal}），")
        append(
            when {
                s.score >= 70 -> "多头占优，情绪短期难以反转"
                s.score >= 45 -> "多空分歧，情绪可能随量能变化反复"
                else -> "空头占优，情绪修复需要放量确认"
            }
        )
        append("。倾向：")
        append(if (s.score >= 70) "看多" else if (s.score >= 45) "中性" else "看空")
        append("。")
    }

    private fun localNews(news: List<NewsApi.NewsItem>, status: String): String = buildString {
        if (news.isEmpty()) {
            append("本次未检索到该股近期新闻")
            if (status.isNotBlank()) append("（检索状态：$status）")
            append("，事件面暂无新增驱动，判断以技术面与估值面为主。倾向：中性。")
            return@buildString
        }
        append("本次检索到 ${news.size} 条相关新闻")
        if (status.isNotBlank()) append("（$status）")
        appendLine("，标题如下：")
        news.take(4).forEachIndexed { i, n -> appendLine("${i + 1}. [${n.source}] ${n.title}（${n.date}）") }
        append("以上为标题层面的事件线索，具体影响方向需以公告原文为准，不宜仅凭标题既定利好或利空。倾向：中性。")
    }

    private fun localTechnical(s: Snap): String = buildString {
        if (s.price <= 0) {
            append("技术指标本次未能计算，暂不对形态做判断。倾向：中性。")
            return@buildString
        }
        if (s.trend.isNotBlank()) append("趋势状态：${s.trend}。")
        append("现价 ${f2(s.price)} 元，MA5 ${f2(s.ma5)}、MA10 ${f2(s.ma10)}、MA20 ${f2(s.ma20)}。")
        if (s.macd.isNotBlank()) append("MACD：${s.macd}。")
        if (s.rsi.isNotBlank()) append("RSI：${s.rsi}。")
        append("综合评分 ${s.score}/100，信号「${s.signal}」。")
        if (s.reasons.isNotBlank()) append("看多理由：${s.reasons}。")
        if (s.risks.isNotBlank()) append("风险因素：${s.risks}。")
        append("倾向：")
        append(if (s.score >= 70) "看多" else if (s.score >= 45) "中性" else "看空")
        append("。")
    }

    private fun localBull(s: Snap): String = buildString {
        append("1) 综合评分 ${s.score}/100，当前信号为「${s.signal}」，技术面给出的方向偏")
        append(if (s.score >= 60) "积极" else "中性")
        appendLine("。")
        if (s.reasons.isNotBlank()) appendLine("2) 支撑看多的具体依据：${s.reasons}。")
        if (s.price > 0) {
            append("3) 均线结构：现价 ${f2(s.price)} 元，MA5 ${f2(s.ma5)}、MA10 ${f2(s.ma10)}、MA20 ${f2(s.ma20)}，")
            append(if (s.price >= s.ma20) "仍站在 20 日线之上，中期结构未破" else "已跌破 20 日线，需先修复中期结构")
            appendLine("。")
        }
        append("倾向：").append(if (s.score >= 60) "看多" else "中性").append("。")
    }

    private fun localBear(s: Snap): String = buildString {
        append("1) 主要风险点：")
        append(if (s.risks.isNotBlank()) s.risks else "量能与趋势信号偏弱，短线波动可能加大")
        appendLine("。")
        if (s.price > 0) {
            append("2) 若有效跌破 MA20（${f2(s.ma20)} 元），中期趋势转弱，需下移止损。")
            appendLine()
        }
        if (s.pe > 0) appendLine("3) 估值方面：市盈率(TTM) ${f2(s.pe)}，${valuationWord(s.pe)}。")
        append("倾向：").append(if (s.score < 50) "看空" else "中性").append("。")
    }

    /** v2.12.43 研究经理本地兜底：依据综合评分裁决多空倾向 */
    private fun localResearchManager(s: Snap): String = buildString {
        val bias = when {
            s.score >= 60 -> "偏多"
            s.score >= 45 -> "均衡"
            else -> "偏空"
        }
        appendLine("倾向：$bias")
        append("关键依据：综合评分 ${s.score}/100，信号「${s.signal}」")
        if (s.reasons.isNotBlank()) append("，${s.reasons}")
        appendLine("。")
        append("主要风险：")
        append(if (s.risks.isNotBlank()) s.risks else "量能与趋势信号偏弱，短线波动可能加大")
        append("。")
    }

    private fun localTrader(s: Snap): String = buildString {
        val dir = if (s.score >= 70) "买入" else "观望"
        val pos = when {
            s.score >= 80 -> "8"
            s.score >= 70 -> "6"
            s.score >= 55 -> "4"
            s.score >= 45 -> "3"
            else -> "2"
        }
        appendLine("方向：$dir")
        appendLine("仓位建议：${pos}成仓（依据综合评分 ${s.score}/100 折算）")
        if (s.price > 0) {
            val buy = if (s.ma5 > 0 && s.ma5 < s.price) s.ma5 else s.price
            val buyNote = if (buy == s.ma5) "按 MA5 回踩估算" else "按现价估算"
            val stop = if (s.ma20 > 0 && s.ma20 < s.price) s.ma20 else s.price * 0.95
            appendLine("理想买入点：${f2(buy)} 元（$buyNote）")
            appendLine("止损价位：${f2(stop)} 元")
            appendLine("止盈目标：${f2(s.price * 1.08)} 元（第一目标，按现价 +8% 估算）；${f2(s.price * 1.15)} 元（第二目标，按现价 +15% 估算）")
        }
        appendLine("持有期：${if (s.score >= 70) "波段(数周)" else "短线(数日)"}")
        append("理由：综合评分 ${s.score}/100（${s.signal}）")
        append(if (s.risks.isNotBlank()) "，主要风险为${s.risks}。" else "，技术面与量能信号决定方向。")
    }

    private fun localRisk(s: Snap): String = buildString {
        val level = when {
            s.score >= 75 -> "低"
            s.score >= 50 -> "中"
            else -> "高"
        }
        appendLine("风险等级：$level")
        appendLine(
            "主要风险：" + (if (s.risks.isNotBlank()) s.risks else "趋势与量能信号偏弱，短线波动可能加大")
        )
        if (s.price > 0 && s.ma20 > 0) {
            appendLine("止损是否合理：是，建议以 MA20（${f2(s.ma20)} 元）作为中期防线，跌破即减仓")
        } else {
            appendLine("止损是否合理：建议以近期低点下方 2% 作为止损位")
        }
        append("仓位建议：${when { s.score >= 75 -> "6"; s.score >= 50 -> "4"; else -> "2" }}成仓以内，单票不超过总仓位三成。")
    }

    private fun localPortfolio(s: Snap): String = buildString {
        val dec = if (s.score >= 70) "批准买入" else "观望"
        appendLine("最终决策：$dec")
        appendLine("仓位上限：${when { s.score >= 80 -> "8"; s.score >= 70 -> "6"; s.score >= 50 -> "4"; else -> "2" }}成")
        appendLine("持有期：${if (s.score >= 70) "波段(数周)" else "短线(数日)"}")
        appendLine("信心评分：${s.score}")
        append("理由：综合评分 ${s.score}/100、信号「${s.signal}」")
        append(if (s.risks.isNotBlank()) "，主要制约为${s.risks}。" else "，无额外制约。")
        append("若后续量能配合且守住 MA10，可再评估加仓。")
    }

    // ===================== 流水线 =====================

    /**
     * v2.12.25：本地取该股所属行业与概念板块（不调用大模型，仅列名、不做分析，节省 token）。
     * 数据源：东方财富个股快照 f127=行业板块、f129=概念板块（逗号分隔）；失败返回空串。
     */
    private fun fetchSectors(code: String): String {
        val sec = EastmoneyApi.secid(code) ?: return ""
        return try {
            val url = "https://push2.eastmoney.com/api/qt/stock/get?secid=$sec&fields=f127,f129"
            val d = JSONObject(Http.get(url, mapOf("Referer" to "https://quote.eastmoney.com/")))
                .optJSONObject("data") ?: return ""
            val industry = d.optString("f127", "").trim()
            val concepts = d.optString("f129", "")
                .split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().take(6)
            buildString {
                if (industry.isNotBlank()) append("行业：").append(industry)
                if (concepts.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    append("概念：").append(concepts.joinToString("、"))
                }
            }.toString()
        } catch (e: Exception) {
            ""
        }
    }

    // ================= 财报快照：多源降级链 + 会话内缓存 =================

    /** 财报快照内部结构；缺项为 NaN，格式化时显示「—」 */
    private data class FinanceSnap(
        val period: String,
        val revYi: Double, val revYoy: Double,
        val profitYi: Double, val profitYoy: Double,
        val roe: Double, val debt: Double, val gross: Double
    )

    /** 会话内内存缓存：code → 快照。一次分析内多轮调用不重复拉网；重启后自然刷新 */
    private val financeMem = LinkedHashMap<String, FinanceSnap>()

    /**
     * 拉取最新一期主要财务指标，降级链：东财 → 同花顺 → 新浪 → Tushare(已配 token)。
     * 任一源取到即返回；全部失败返回空串，由调用方标注缺失，不打断流程。
     */
    private fun fetchFinance(ctx: Context, code: String): String {
        financeMem[code]?.let { return formatFinance(it) }
        val snap = fetchEmFinance(code)
            ?: fetchThsFinance(code)
            ?: fetchSinaFinance(code)
            ?: fetchTsFinance(ctx, code)
        if (snap != null) {
            financeMem[code] = snap
            return formatFinance(snap)
        }
        return ""
    }

    private fun formatFinance(s: FinanceSnap): String {
        fun yoy(v: Double) = if (v.isNaN()) "—" else "${if (v >= 0) "+" else ""}${f2(v)}%"
        fun pct(v: Double) = if (v.isNaN()) "—" else "${f2(v)}%"
        return buildString {
            append("【最新财报】")
            if (s.period.isNotBlank()) append(s.period).append("：")
            if (s.revYi > 0) append("营业总收入 ${f2(s.revYi)} 亿元（同比 ${yoy(s.revYoy)}）")
            if (s.profitYi > 0) {
                if (s.revYi > 0) append("，")
                append("归母净利润 ${f2(s.profitYi)} 亿元（同比 ${yoy(s.profitYoy)}）")
            }
            if (!s.roe.isNaN()) append("，ROE(加权) ${pct(s.roe)}")
            if (!s.debt.isNaN()) append("，资产负债率 ${pct(s.debt)}")
            if (!s.gross.isNaN()) append("，毛利率 ${pct(s.gross)}")
            if (isEmpty()) return ""
        }.toString()
    }

    /** 源 1：东财 F10（字段最全、JSON 最规整） */
    private fun fetchEmFinance(code: String): FinanceSnap? {
        return try {
        val url = "https://datacenter.eastmoney.com/securities/api/data/v1/get" +
            "?reportName=RPT_F10_FINANCE_MAINFINADATA" +
            "&columns=REPORT_DATE_NAME,TOTALOPERATEREVE,PARENTNETPROFIT,TOTALOPERATEREVETZ," +
            "PARENTNETPROFITTZ,ROEJQ,ZCFZL,XSMLL" +
            "&filter=(SECURITY_CODE%3D%22$code%22)&pageNumber=1&pageSize=1" +
            "&sortColumns=REPORT_DATE&sortTypes=-1&source=HSF10&client=PC"
        val arr = JSONObject(Http.get(url, mapOf("Referer" to "https://emweb.securities.eastmoney.com/")))
            .optJSONObject("result")?.optJSONArray("data") ?: return null
        val d = arr.optJSONObject(0) ?: return null
        val rev = d.optDouble("TOTALOPERATEREVE", 0.0) / 1e8
        val profit = d.optDouble("PARENTNETPROFIT", 0.0) / 1e8
        if (rev <= 0.0 && profit <= 0.0) return null
        FinanceSnap(
            period = d.optString("REPORT_DATE_NAME", "").trim(),
            revYi = rev, revYoy = d.optDouble("TOTALOPERATEREVETZ", Double.NaN),
            profitYi = profit, profitYoy = d.optDouble("PARENTNETPROFITTZ", Double.NaN),
            roe = d.optDouble("ROEJQ", Double.NaN),
            debt = d.optDouble("ZCFZL", Double.NaN),
            gross = d.optDouble("XSMLL", Double.NaN)
        )
    } catch (e: Exception) { null }
    }

    /** 源 2：同花顺 F10（JSON 嵌套 flashData；银行股无毛利率） */
    private fun fetchThsFinance(code: String): FinanceSnap? {
        return try {
        val url = "http://basic.10jqka.com.cn/api/stock/finance/${code}_main.json"
        val root = JSONObject(Http.get(url, mapOf("Referer" to "http://basic.10jqka.com.cn/")))
        val fd = root.optString("flashData", "").ifBlank { return null }
        val data = JSONObject(fd)
        val title = data.optJSONArray("title") ?: return null
        val report = data.optJSONArray("report") ?: return null
        if (title.length() == 0 || report.length() == 0) return null
        val period = report.optJSONArray(0)?.optString(0, "") ?: ""
        val rowByName = HashMap<String, Int>()
        for (i in 0 until title.length()) {
            val name = title.optJSONArray(i)?.optString(0, "") ?: continue
            rowByName[name] = i
        }
        fun cell(vararg names: String): String? {
            for (n in names) {
                val i = rowByName[n] ?: continue
                val v = report.optJSONArray(i)?.optString(0, "")?.trim().orEmpty()
                if (v.isNotBlank() && v != "False" && v != "false") return v
            }
            return null
        }
        val rev = parseAmountYi(cell("营业总收入", "营业收入"))
        val profit = parseAmountYi(cell("净利润", "归属于母公司的净利润"))
        if (rev.isNaN() && profit.isNaN()) return null
        FinanceSnap(
            period = period,
            revYi = rev, revYoy = parsePct(cell("营业总收入同比增长率", "营业收入同比增长率")),
            profitYi = profit, profitYoy = parsePct(cell("净利润同比增长率")),
            roe = parsePct(cell("净资产收益率")),
            debt = parsePct(cell("资产负债率")),
            gross = parsePct(cell("销售毛利率", "毛利率"))
        )
    } catch (e: Exception) { null }
    }

    /** 源 3：新浪财经 HTML 表格（gb2312；无同比，比率取自指标页、金额取自利润表） */
    private fun fetchSinaFinance(code: String): FinanceSnap? {
        return try {
        val guideUrl = "https://money.finance.sina.com.cn/corp/go.php/vFD_FinancialGuideLine/stockid/$code/displaytype/4.phtml"
        val guide = Http.get(guideUrl, emptyMap())
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
        val profitUrl = "https://money.finance.sina.com.cn/corp/go.php/vFD_ProfitStatement/stockid/$code/ctrl/$year/displaytype/4.phtml"
        val profitHtml = try { Http.get(profitUrl, emptyMap()) } catch (e: Exception) { "" }

        fun guideRow(label: String): String? {
            val m = Regex("<a[^>]*>${Regex.escape(label)}(?:\\(%\\))?</a></td>\\s*<td[^>]*>([^<]*)</td>").find(guide)
            return m?.groupValues?.get(1)?.trim()?.ifBlank { null }
        }
        fun profitRow(label: String): String? {
            if (profitHtml.isBlank()) return null
            val m = Regex("<a[^>]*>${Regex.escape(label)}</a></td>\\s*<td[^>]*>([^<]*)</td>").find(profitHtml)
            return m?.groupValues?.get(1)?.trim()?.ifBlank { null }
        }
        val roe = parsePct(guideRow("加权净资产收益率") ?: guideRow("净资产收益率"))
        val debt = parsePct(guideRow("资产负债率"))
        val gross = parsePct(guideRow("销售毛利率") ?: guideRow("毛利率"))
        // 利润表单位为万元 → 亿元
        val profitWan = profitRow("归属于母公司的净利润")?.replace(",", "")?.toDoubleOrNull()
        val revWan = (profitRow("营业总收入") ?: profitRow("营业收入"))?.replace(",", "")?.toDoubleOrNull()
        val profit = if (profitWan != null) profitWan / 1e4 else Double.NaN
        val rev = if (revWan != null) revWan / 1e4 else Double.NaN
        if (rev.isNaN() && profit.isNaN() && roe.isNaN() && debt.isNaN()) return null
        val period = Regex("20\\d{2}-\\d{2}-\\d{2}").find(guide)?.value ?: ""
        FinanceSnap(period, rev, Double.NaN, profit, Double.NaN, roe, debt, gross)
    } catch (e: Exception) { null }
    }

    /** 源 4：Tushare（需已配 token；免费档权限不足时静默跳过） */
    private fun fetchTsFinance(ctx: Context, code: String): FinanceSnap? {
        return try {
        val token = Prefs.tushareToken(ctx)
        if (token.isBlank()) return null
        val tc = TushareApi.tsCode(code)
        fun call(api: String, fields: String): JSONArray {
            val body = JSONObject()
                .put("api_name", api).put("token", token)
                .put("params", JSONObject().put("ts_code", tc))
                .put("fields", fields)
            val resp = JSONObject(Http.postJson(TushareApi.ENDPOINT, body.toString()))
            if (resp.optInt("code", -1) != 0) throw RuntimeException(resp.optString("msg", "Tushare 错误"))
            return resp.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        }
        fun latestRow(items: JSONArray): JSONArray? {
            if (items.length() == 0) return null
            var best: JSONArray? = null; var bestDate = ""
            for (i in 0 until items.length()) {
                val row = items.optJSONArray(i) ?: continue
                val d = row.optString(0, "")
                if (d > bestDate) { bestDate = d; best = row }
            }
            return best
        }
        val ind = latestRow(call("fina_indicator",
            "end_date,roe_waa,roe,grossprofit_margin,debt_to_assets,or_yoy,netprofit_yoy"))
        val inc = try { latestRow(call("income", "end_date,total_revenue,n_income")) } catch (e: Exception) { null }
        fun rowD(row: JSONArray?, idx: Int): Double =
            if (row != null && row.length() > idx && !row.isNull(idx)) row.optDouble(idx, Double.NaN) else Double.NaN
        if (ind == null && inc == null) return null
        val roe = if (!rowD(ind, 1).isNaN()) rowD(ind, 1) else rowD(ind, 2)
        val revRaw = rowD(inc, 1); val profitRaw = rowD(inc, 2)
        val period = ind?.optString(0, "")?.ifBlank { inc?.optString(0, "") ?: "" } ?: ""
        FinanceSnap(
            period = period,
            revYi = if (revRaw.isFinite() && revRaw > 0) revRaw / 1e8 else Double.NaN,
            revYoy = rowD(ind, 5),
            profitYi = if (profitRaw.isFinite() && profitRaw > 0) profitRaw / 1e8 else Double.NaN,
            profitYoy = rowD(ind, 6),
            roe = roe, debt = rowD(ind, 3), gross = rowD(ind, 4)
        )
    } catch (e: Exception) { null }
    }

    /** 金额串 → 亿元；支持「亿 / 万」后缀与纯元值，无法解析返回 NaN */
    private fun parseAmountYi(raw: String?): Double {
        if (raw.isNullOrBlank()) return Double.NaN
        val t = raw.trim().replace(",", "")
        if (t == "--" || t == "False" || t == "false") return Double.NaN
        val m = Regex("^([-\\d.]+)\\s*(亿|万)?").find(t) ?: return Double.NaN
        val v = m.groupValues[1].toDoubleOrNull() ?: return Double.NaN
        return when (m.groupValues[2]) {
            "亿" -> v
            "万" -> v / 1e4
            else -> v / 1e8
        }
    }

    /** 百分比串 → 数值百分数；无法解析返回 NaN */
    private fun parsePct(raw: String?): Double {
        if (raw.isNullOrBlank()) return Double.NaN
        val t = raw.trim().replace(",", "").replace("%", "")
        if (t == "--" || t == "False" || t == "false") return Double.NaN
        return t.toDoubleOrNull() ?: Double.NaN
    }

    fun run(
        ctx: Context,
        code: String,
        name: String,
        summary: String,
        news: List<NewsApi.NewsItem>,
        newsStatus: String = "",
        holding: Holding? = null,
        onStage: (String, String, String) -> Unit
    ): Result {
        val profiles = Prefs.llmProfiles(ctx)
        val active = Prefs.llmActiveIndex(ctx).coerceIn(0, (profiles.size - 1).coerceAtLeast(0))
        val cur = profiles.getOrNull(active)
        if (cur == null || cur.baseUrl.isBlank() || cur.apiKey.isBlank() || cur.model.isBlank()) {
            throw RuntimeException("请先在大模型设置中填写 API 地址、Key 和模型名")
        }

        val lean = Prefs.isLean(ctx)
        val newsLimit = if (lean) 4 else 6
        val snippetLimit = if (lean) 60 else 100
        val reportLimit = if (lean) 320 else Int.MAX_VALUE

        LlmApi.resetStats()

        val newsBlock = buildString {
            if (news.isEmpty()) {
                append("【无新闻数据】")
                append(if (newsStatus.isNotBlank()) "检索状态：$newsStatus\n" else "\n")
                append("本次未获取到该股近期新闻，请如实说明，不要编造新闻内容。")
            } else {
                append("检索状态：${newsStatus.ifBlank { "共 ${news.size} 条" }}\n")
                news.take(newsLimit).forEachIndexed { i, n ->
                    append("${i + 1}. [${n.source}] ${n.title}（${n.date}）\n")
                    if (n.snippet.isNotBlank()) append("   ${n.snippet.take(snippetLimit)}\n")
                }
            }
        }

        // 把技术面摘要拆成「行情估值」与「技术指标」两块，各角色只取自己需要的部分
        val parts = summary.split("--- 技术指标 ---")
        val quoteBlock = parts.getOrElse(0) { summary }.trim()
        val techBlock = if (parts.size >= 2) parts[1].trim() else summary.trim()
        // 最新一期财务指标（本地取数，不调用模型）；取不到时在基本面块里明确标注缺失
        val financeBlock = fetchFinance(ctx, code)

        // 9 次调用完全一致的最小公共前缀（system 也一致）→ 供应商前缀缓存可连续命中
        val core = buildString {
            appendLine("【标的】$name（$code）")
            quoteBlock.lineSequence().firstOrNull { it.startsWith("最新价：") }?.let { appendLine(it) }
            techBlock.lineSequence().firstOrNull { it.startsWith("综合评分：") }?.let { appendLine(it) }
        }

        val snap = parseSnap(summary)

        // v2.12.43：现有持仓摘要（本地算好浮盈与仓位占比，模型不重复计算，省 token）
        val holdingBlock = buildString {
            val h = holding
            if (h == null || !h.valid) {
                append("【现有持仓】未录入持仓，按无持仓视角给新建仓位建议，无需考虑加仓、减仓或浮亏。")
            } else {
                appendLine("【现有持仓】")
                appendLine("成本价：${f2(h.cost)} 元；持股：${formatShares(h.shares)} 股")
                if (snap.price > 0) {
                    val costMv = h.cost * h.shares
                    val nowMv = snap.price * h.shares
                    val pnl = nowMv - costMv
                    val pnlPct = if (h.cost > 0.0) (snap.price / h.cost - 1.0) * 100 else Double.NaN
                    append("现价：${f2(snap.price)} 元；持仓市值约 ${f2(nowMv)} 元；浮动")
                    append(if (pnl >= 0) "盈利" else "亏损")
                    append(" ${f2(kotlin.math.abs(pnl))} 元")
                    if (!pnlPct.isNaN()) append("（${if (pnlPct >= 0) "+" else ""}${f2(pnlPct)}%）")
                    appendLine()
                }
                if (h.cash > 0.0 && snap.price > 0.0) {
                    val posPct = snap.price * h.shares / h.cash * 100.0
                    appendLine("可用总资金：${f2(h.cash)} 元；该票仓位约 ${f2(posPct)}%")
                }
                append("请结合现有仓位给出加仓/减仓/持有/清仓建议，不要按空仓视角给新建仓位。")
            }
        }

        // 每套配置的连续失败次数：同一配置连挂 2 次后本次分析不再尝试，避免长时间空等
        val fails = IntArray(profiles.size)
        // 全局熔断：连续 2 个环节完全不可用后，剩余环节直接走本地规则，保证报告能出
        var globalFails = 0
        var degraded = 0
        // 多空并行时保护 fails/globalFails 计数（网络调用在锁外执行，不阻塞并发）
        val askLock = Any()

        fun ask(task: String, extra: String = "", temp: Double = 0.7, maxTokens: Int = 640): String {
            synchronized(askLock) { if (globalFails >= 2) return "" }
            val u = buildString {
                append(core)
                if (extra.isNotBlank()) {
                    appendLine()
                    append(extra)
                }
                appendLine()
                append(task)
            }
            // 当前配置优先，其次轮换到其他已保存的配置（跨供应商兜底）
            val order = (listOf(active) + profiles.indices.filter { it != active }).distinct()
            for (idx in order) {
                if (idx !in profiles.indices) continue
                synchronized(askLock) { if (fails[idx] >= 2) continue }
                val p = profiles[idx]
                if (p.baseUrl.isBlank() || p.apiKey.isBlank() || p.model.isBlank()) continue
                val text = try {
                    LlmApi.chat(
                        p.baseUrl, p.apiKey, p.model, SYSTEM_BASE, u, temp,
                        p.fallback.ifBlank { null }, maxTokens
                    )
                } catch (e: Exception) {
                    synchronized(askLock) { fails[idx]++ }
                    Log.w("MultiAgent", "配置「${p.name}」调用未成功：${e.message?.take(80)}")
                    continue
                }
                if (text.isNotBlank()) {
                    synchronized(askLock) { globalFails = 0; fails[idx] = 0 }
                    if (idx != active) Log.i("MultiAgent", "配置「${p.name}」兜底成功（环节：${task.take(20)}）")
                    return text
                }
            }
            synchronized(askLock) { globalFails++ }
            return ""
        }

        // ---- 1. 四分析师合并为一次调用（同一份数据只发一遍，省掉 3 次重复上下文） ----
        onStage("四位分析师", "分析中", "")
        val analystExtra = buildString {
            appendLine("【估值与基本面】")
            appendLine(quoteBlock)
            if (financeBlock.isNotBlank()) appendLine(financeBlock)
            else appendLine("【最新财报】本次未取得财务指标，基本面判断以估值倍数为准。")
            appendLine()
            appendLine("【技术指标】")
            appendLine(techBlock)
            appendLine()
            appendLine("【近期新闻】")
            append(newsBlock)
        }
        val analystText = ask(
            TASK_ANALYSTS,
            extra = analystExtra,
            maxTokens = 1400
        )

        val analystParts = splitAnalystSections(analystText)
        var fundamentals = analystParts[0]
        var sentiment = analystParts[1]
        var newsImpact = analystParts[2]
        var technical = analystParts[3]
        // 模型全文返回但没分段时，把整段放进基本面，其余走本地补全
        if (analystText.isNotBlank() && analystParts.all { it.isBlank() }) fundamentals = analystText

        if (fundamentals.isBlank()) { fundamentals = localFundamentals(snap); degraded++ }
        if (sentiment.isBlank()) { sentiment = localSentiment(snap); degraded++ }
        if (newsImpact.isBlank()) { newsImpact = localNews(news, newsStatus); degraded++ }
        if (technical.isBlank()) { technical = localTechnical(snap); degraded++ }

        // v2.12.25：所属板块环节——本地取数，不调用模型、不做分析，不计入本地兜底计数
        val sectors = fetchSectors(code)

        onStage("基本面分析师", "完成", fundamentals)
        onStage("所属板块", "完成", sectors)
        onStage("情绪分析师", "完成", sentiment)
        onStage("新闻分析师", "完成", newsImpact)
        onStage("技术分析师", "完成", technical)

        val fourReports = buildString {
            appendLine("【基本面分析师】\n${fundamentals.take(reportLimit)}")
            appendLine("【情绪分析师】\n${sentiment.take(reportLimit)}")
            appendLine("【新闻分析师】\n${newsImpact.take(reportLimit)}")
            appendLine("【技术分析师】\n${technical.take(reportLimit)}")
        }

        // ---- 2. 多空研究员辩论（两者都只依赖四份分析师报告，无相互依赖，并行调用省一轮墙钟） ----
        onStage("多头研究员", "辩论中", "")
        onStage("空头研究员", "辩论中", "")
        val debatePool = Executors.newFixedThreadPool(2)
        val bullF = debatePool.submit(Callable<String> { ask(TASK_BULL, extra = "【四位分析师报告】\n$fourReports", maxTokens = 700) })
        val bearF = debatePool.submit(Callable<String> { ask(TASK_BEAR, extra = "【四位分析师报告】\n$fourReports", maxTokens = 700) })
        val bullRaw = try { bullF.get(60, TimeUnit.SECONDS) } catch (e: Exception) { "" }
        val bearRaw = try { bearF.get(60, TimeUnit.SECONDS) } catch (e: Exception) { "" }
        debatePool.shutdown()
        val bull = bullRaw.ifBlank { localBull(snap).also { degraded++ } }
        val bear = bearRaw.ifBlank { localBear(snap).also { degraded++ } }
        onStage("多头研究员", "完成", bull)
        onStage("空头研究员", "完成", bear)

        val debate = buildString {
            appendLine("【多头研究员观点】\n${bull.take(reportLimit)}")
            appendLine("【空头研究员观点】\n${bear.take(reportLimit)}")
        }

        // ---- 3. 研究经理汇总：多空辩论后裁决更强一方，再交交易员（对齐 TradingAgents Research Manager） ----
        onStage("研究经理", "汇总中", "")
        val researchRaw = ask(TASK_RESEARCH_MGR, extra = "【多空辩论结论】\n$debate", temp = 0.3, maxTokens = 380)
        val research = researchRaw.ifBlank { localResearchManager(snap).also { degraded++ } }.let { stripHeading(it) }
        onStage("研究经理", "完成", research)

        // ---- 4. 交易员（结合研究经理裁决、辩论与现有持仓给加减仓建议） ----
        onStage("交易员", "决策中", "")
        val traderRaw = ask(
            TASK_TRADER,
            extra = "【研究经理汇总】\n$research\n\n【多空辩论结论】\n$debate\n\n$holdingBlock",
            temp = 0.3, maxTokens = 520
        )
        val trader = traderRaw.ifBlank { localTrader(snap).also { degraded++ } }.let { stripHeading(it) }
        onStage("交易员", "完成", trader)

        // ---- 5. 风控（结合现有仓位评估集中度与浮亏承受） ----
        onStage("风险管理", "评估中", "")
        val riskRaw = ask(
            TASK_RISK,
            extra = "$holdingBlock\n\n【交易员决策】\n$trader",
            temp = 0.3, maxTokens = 420
        )
        val risk = riskRaw.ifBlank { localRisk(snap).also { degraded++ } }.let { stripHeading(it) }
        onStage("风险管理", "完成", risk)

        // ---- 6. 组合经理 ----
        onStage("组合经理", "终审中", "")
        val portfolioRaw = ask(
            TASK_PORTFOLIO,
            extra = "【交易员决策】\n$trader\n\n【风险评估】\n$risk",
            temp = 0.3, maxTokens = 420
        )
        val portfolio = portfolioRaw.ifBlank { localPortfolio(snap).also { degraded++ } }.let { stripHeading(it) }
        onStage("组合经理", "完成", portfolio)

        if (degraded > 0) Log.i("MultiAgent", "本次有 $degraded 个环节由本地规则补全")

        return Result(
            code, name, java.time.LocalDate.now().toString(),
            fundamentals, sectors, sentiment, newsImpact, technical,
            bull, bear, research, trader, risk, portfolio, degraded
        )
    }

    /** 从字符串解析信号（供校验/调试） */
    fun parseSignal(s: String): String = signalFromText(s).ifBlank { "观望" }
}
