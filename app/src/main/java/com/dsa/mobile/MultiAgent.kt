package com.dsa.mobile

import android.content.Context
import android.util.Log
import org.json.JSONObject

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
        val trader: String,
        val risk: String,
        val portfolio: String,
        /** 由本地规则补全的环节数量（0 表示全部由模型生成） */
        val degraded: Int = 0
    ) {
        /** 从组合经理输出提取最终信号：买入/卖出/观望 */
        val finalSignal: String
            get() {
                val p = portfolio
                return when {
                    p.contains("强烈买入") -> "强烈买入"
                    p.contains("买入") -> "买入"
                    p.contains("强烈卖出") -> "强烈卖出"
                    p.contains("卖出") -> "卖出"
                    p.contains("观望") || p.contains("持有") -> "观望"
                    else -> "观望"
                }
            }

        /** 从组合经理输出提取信心评分 0-100 */
        val finalScore: Int
            get() {
                val m = Regex("""(?:评分|信心|置信)[：:\s]*(\d{1,3})""").find(portfolio)
                val v = m?.groupValues?.get(1)?.toIntOrNull() ?: 50
                return v.coerceIn(0, 100)
            }

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

    private const val TASK_TRADER =
        "【本次任务】你是交易员。综合上面的多空辩论与技术面要点，做出交易决策。输出格式（严格按此格式）：\n" +
            "## 交易决策\n方向：买入/卖出/观望\n仓位建议：x成仓（0-10成）\n理想买入点：xxx元（或区间）\n止损价位：xxx元\n止盈目标：xxx元\n理由：150字内\n" +
            "要求决策果断、给出具体价位，基于上述证据，不模棱两可。"

    private const val TASK_RISK =
        "【本次任务】你是风险管理专员。评估上面交易员决策的风险：市场波动风险、流动性风险、下行空间、止损是否合理，" +
            "给出风险评估等级（低/中/高）与改进建议。输出格式（严格按此格式）：\n" +
            "## 风险评估\n风险等级：低/中/高\n主要风险：...\n止损是否合理：是/否，建议...\n仓位建议：...\n150字内。"

    private const val TASK_PORTFOLIO =
        "【本次任务】你是投资组合经理。综合上面的交易员决策与风控评估，做出最终决定：批准、否决或调整该交易。输出格式（严格按此格式）：\n" +
            "## 组合经理最终决策\n最终决策：批准买入/批准卖出/观望/否决\n仓位上限：x成\n信心评分：0-100整数\n理由：150字内\n" +
            "必须给出最终决策与信心评分，作为整个分析流程的结论。"

    // ===================== 文本工具 =====================

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

    fun run(
        ctx: Context,
        code: String,
        name: String,
        summary: String,
        news: List<NewsApi.NewsItem>,
        newsStatus: String = "",
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

        // 9 次调用完全一致的最小公共前缀（system 也一致）→ 供应商前缀缓存可连续命中
        val core = buildString {
            appendLine("【标的】$name（$code）")
            quoteBlock.lineSequence().firstOrNull { it.startsWith("最新价：") }?.let { appendLine(it) }
            techBlock.lineSequence().firstOrNull { it.startsWith("综合评分：") }?.let { appendLine(it) }
        }

        val snap = parseSnap(summary)

        // 每套配置的连续失败次数：同一配置连挂 2 次后本次分析不再尝试，避免长时间空等
        val fails = IntArray(profiles.size)
        // 全局熔断：连续 2 个环节完全不可用后，剩余环节直接走本地规则，保证报告能出
        var globalFails = 0
        var degraded = 0

        fun ask(task: String, extra: String = "", temp: Double = 0.7, maxTokens: Int = 640): String {
            if (globalFails >= 2) return ""
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
                if (fails[idx] >= 2) continue
                val p = profiles[idx]
                if (p.baseUrl.isBlank() || p.apiKey.isBlank() || p.model.isBlank()) continue
                try {
                    val text = LlmApi.chat(
                        p.baseUrl, p.apiKey, p.model, SYSTEM_BASE, u, temp,
                        p.fallback.ifBlank { null }, maxTokens
                    )
                    if (text.isNotBlank()) {
                        globalFails = 0
                        fails[idx] = 0
                        if (idx != active) Log.i("MultiAgent", "配置「${p.name}」兜底成功（环节：${task.take(20)}）")
                        return text
                    }
                } catch (e: Exception) {
                    fails[idx]++
                    Log.w("MultiAgent", "配置「${p.name}」调用未成功：${e.message?.take(80)}")
                }
            }
            globalFails++
            return ""
        }

        // ---- 1. 四分析师合并为一次调用（同一份数据只发一遍，省掉 3 次重复上下文） ----
        onStage("四位分析师", "分析中", "")
        val analystText = ask(
            TASK_ANALYSTS,
            extra = "【估值与基本面】\n$quoteBlock\n\n【技术指标】\n$techBlock\n\n【近期新闻】\n$newsBlock",
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

        // ---- 2. 多空研究员辩论 ----
        onStage("多头研究员", "辩论中", "")
        val bullRaw = ask(TASK_BULL, extra = "【四位分析师报告】\n$fourReports", maxTokens = 700)
        val bull = bullRaw.ifBlank { localBull(snap).also { degraded++ } }
        onStage("多头研究员", "完成", bull)

        onStage("空头研究员", "辩论中", "")
        val bearRaw = ask(TASK_BEAR, extra = "【四位分析师报告】\n$fourReports", maxTokens = 700)
        val bear = bearRaw.ifBlank { localBear(snap).also { degraded++ } }
        onStage("空头研究员", "完成", bear)

        val debate = buildString {
            appendLine("【多头研究员观点】\n${bull.take(reportLimit)}")
            appendLine("【空头研究员观点】\n${bear.take(reportLimit)}")
        }

        // ---- 3. 交易员 ----
        onStage("交易员", "决策中", "")
        val traderRaw = ask(TASK_TRADER, extra = "【多空辩论结论】\n$debate", temp = 0.3, maxTokens = 520)
        val trader = traderRaw.ifBlank { localTrader(snap).also { degraded++ } }.let { stripHeading(it) }
        onStage("交易员", "完成", trader)

        // ---- 4. 风控 ----
        onStage("风险管理", "评估中", "")
        val riskRaw = ask(TASK_RISK, extra = "【交易员决策】\n$trader", temp = 0.3, maxTokens = 420)
        val risk = riskRaw.ifBlank { localRisk(snap).also { degraded++ } }.let { stripHeading(it) }
        onStage("风险管理", "完成", risk)

        // ---- 5. 组合经理 ----
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
            bull, bear, trader, risk, portfolio, degraded
        )
    }

    /** 从字符串解析信号（供校验/调试） */
    fun parseSignal(s: String): String = when {
        s.contains("强烈买入") -> "强烈买入"
        s.contains("买入") -> "买入"
        s.contains("强烈卖出") -> "强烈卖出"
        s.contains("卖出") -> "卖出"
        else -> "观望"
    }
}
