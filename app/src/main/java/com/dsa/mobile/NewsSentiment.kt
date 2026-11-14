package com.dsa.mobile

/**
 * 新闻情绪打分（v2.5.0）
 * 标题+摘要关键词规则打分，用于「新闻利好选股」与详情页「新闻分析」。
 * 命中正面词 +1 / 负面词 -1，聚合多篇新闻得到总分。
 */
object NewsSentiment {

    private val POSITIVE = listOf(
        "利好", "涨停", "大涨", "增长", "预增", "扭亏", "盈利", "中标", "签约", "回购",
        "增持", "突破", "创新高", "超预期", "获批", "获准", "合作", "订单", "涨价", "提价",
        "放量", "主力", "机构买入", "增资", "扩产", "投产", "交付", "景气", "补贴",
        "政策支持", "分红", "派息", "股权激励", "并购", "重组", "举牌", "龙虎榜", "净买入",
        "北向", "外资", "加仓", "看好", "上调"
    )

    private val NEGATIVE = listOf(
        "利空", "跌停", "大跌", "亏损", "预亏", "减持", "质押", "爆仓", "诉讼", "处罚",
        "违规", "立案", "退市", "ST", "商誉减值", "下调", "警示", "问询", "风险提示",
        "破产", "债务违约", "违约", "冻结", "失信", "调查", "责令", "罚款", "整改",
        "停产", "减产", "裁员", "高管离职", "内幕", "做空", "评级下调", "终止", "终止重组",
        "解禁", "流拍", "降级"
    )

    data class SentimentScore(
        val score: Int,               // 总分（正=利好，负=利空，0=中性）
        val positive: List<String>,   // 命中的正面关键词（最多3）
        val negative: List<String>    // 命中的负面关键词（最多3）
    )

    fun score(items: List<NewsApi.NewsItem>): SentimentScore {
        var s = 0
        val pos = mutableListOf<String>()
        val neg = mutableListOf<String>()
        items.forEach { n ->
            val text = "${n.title} ${n.snippet}"
            POSITIVE.forEach { w ->
                if (text.contains(w)) {
                    s += 1
                    if (pos.size < 3 && w !in pos) pos.add(w)
                }
            }
            NEGATIVE.forEach { w ->
                if (text.contains(w)) {
                    s -= 1
                    if (neg.size < 3 && w !in neg) neg.add(w)
                }
            }
        }
        return SentimentScore(s, pos, neg)
    }

    fun label(s: Int): String = when {
        s >= 3 -> "强利好"
        s >= 1 -> "利好"
        s == 0 -> "中性"
        s > -3 -> "利空"
        else -> "强利空"
    }
}
