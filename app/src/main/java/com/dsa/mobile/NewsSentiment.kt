package com.dsa.mobile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 新闻情绪打分（v2.12.35 升级）
 *
 * 规则（端侧纯规则，无模型依赖）：
 * 1. 词表分强 / 弱两档权重：强词 ±2，弱词 ±1；
 * 2. 否定前缀反转：命中词前 3 字内出现否定词（不/未/无/非/否认/暂停/暂无…）时符号取反；
 * 3. 时效衰减：当天权重 1.0，7 日内 0.75，30 日内 0.5，更早 0.3；日期缺失按 0.8；
 * 4. 同源同题去重：标题（或摘要前 20 字）规范化后只计一次，避免重复刷屏放大情绪；
 * 5. 情绪仅作为辅助腿，不单独决定入选。
 */
object NewsSentiment {

    /** 强正面词（+2）：幅度大、确定性高的事件 */
    private val STRONG_POSITIVE = listOf(
        "涨停", "大涨", "预增", "扭亏", "中标", "回购", "增持", "创新高", "超预期",
        "获批", "获准", "举牌", "净买入", "并购", "重组", "上调", "扩产", "投产"
    )

    /** 弱正面词（+1）：偏利好但幅度 / 确定性一般 */
    private val WEAK_POSITIVE = listOf(
        "利好", "增长", "盈利", "签约", "突破", "合作", "订单", "涨价", "提价",
        "放量", "主力", "机构买入", "增资", "交付", "景气", "补贴", "政策支持",
        "分红", "派息", "股权激励", "龙虎榜", "北向", "外资", "加仓", "看好"
    )

    /** 强负面词（-2）：幅度大、确定性高的风险事件 */
    private val STRONG_NEGATIVE = listOf(
        "跌停", "爆仓", "诉讼", "处罚", "违规", "立案", "退市", "商誉减值", "破产",
        "债务违约", "违约", "冻结", "失信", "调查", "责令", "罚款", "停产", "减持", "质押"
    )

    /** 弱负面词（-1）：偏利空但幅度 / 确定性一般 */
    private val WEAK_NEGATIVE = listOf(
        "利空", "大跌", "亏损", "预亏", "下调", "警示", "问询", "风险提示", "整改",
        "减产", "裁员", "高管离职", "内幕", "做空", "评级下调", "终止", "终止重组",
        "解禁", "流拍", "降级"
    )

    /** 否定前缀：命中词紧邻前文出现这些词时，情绪符号反转 */
    private val NEGATORS = listOf("否认", "暂停", "暂无", "未能", "难以", "不", "未", "无", "非")

    data class SentimentScore(
        val score: Int,               // 总分（正=利好，负=利空，0=中性）
        val positive: List<String>,  // 命中的正面关键词（最多3）
        val negative: List<String>,  // 命中的负面关键词（最多3）
        val count: Int = 0            // 参与打分的去重后新闻条数
    )

    fun score(items: List<NewsApi.NewsItem>): SentimentScore {
        var raw = 0.0
        val pos = mutableListOf<String>()
        val neg = mutableListOf<String>()
        val seen = HashSet<String>()
        var counted = 0

        items.forEach { n ->
            val titleKey = n.title.trim().lowercase().ifBlank { n.snippet.take(20).lowercase() }
            if (titleKey.isBlank() || !seen.add(titleKey)) return@forEach
            counted++

            val text = "${n.title} ${n.snippet}"
            val decay = freshnessDecay(n.date)

            STRONG_POSITIVE.forEach { w ->
                val sign = if (negated(text, w)) -1 else 1
                raw += sign * 2.0 * decay
                if (sign > 0) addHit(pos, w) else addHit(neg, w)
            }
            WEAK_POSITIVE.forEach { w ->
                val sign = if (negated(text, w)) -1 else 1
                raw += sign * 1.0 * decay
                if (sign > 0) addHit(pos, w) else addHit(neg, w)
            }
            STRONG_NEGATIVE.forEach { w ->
                val sign = if (negated(text, w)) 1 else -1
                raw += sign * 2.0 * decay
                if (sign < 0) addHit(neg, w) else addHit(pos, w)
            }
            WEAK_NEGATIVE.forEach { w ->
                val sign = if (negated(text, w)) 1 else -1
                raw += sign * 1.0 * decay
                if (sign < 0) addHit(neg, w) else addHit(pos, w)
            }
        }

        return SentimentScore(raw.roundToInt(), pos, neg, counted)
    }

    private fun addHit(list: MutableList<String>, w: String) {
        if (list.size < 3 && w !in list) list.add(w)
    }

    /** 命中词前 3 个字符内是否出现否定前缀 */
    private fun negated(text: String, word: String): Boolean {
        val idx = text.indexOf(word)
        if (idx <= 0) return false
        val prefix = text.substring(0, idx).takeLast(3)
        return NEGATORS.any { prefix.contains(it) }
    }

    /** 时效衰减系数：当天 1.0，7 日内 0.75，30 日内 0.5，更早 0.3；日期缺失 0.8 */
    private fun freshnessDecay(date: String): Double {
        val d = date.trim().take(10)
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d)) return 0.8
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val pub = fmt.parse(d) ?: return 0.8
            val days = (Date().time - pub.time) / 86_400_000L
            when {
                days <= 0 -> 1.0
                days <= 7 -> 0.75
                days <= 30 -> 0.5
                else -> 0.3
            }
        } catch (e: Exception) {
            0.8
        }
    }

    fun label(s: Int): String = when {
        s >= 3 -> "强利好"
        s >= 1 -> "利好"
        s == 0 -> "中性"
        s > -3 -> "利空"
        else -> "强利空"
    }
}
