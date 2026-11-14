package com.dsa.mobile

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 新闻检索聚合（供选股 / AI 报告参考）
 * 优先级：Bocha(最高) → Tavily → SerpAPI → Brave → SearXNG(兜底)
 * MiniMax 为对话式联网搜索（需调模型触发），不在此处独立检索。
 * 各源 Key 支持逗号分隔多个，逐个尝试。
 */
object NewsApi {

    data class NewsItem(
        val title: String,
        val snippet: String,
        val date: String,
        val source: String
    )

    /** 是否配置了任一新闻源 */
    fun hasAnySource(ctx: Context): Boolean =
        listOf(
            Prefs.bochaKeys(ctx), Prefs.tavilyKeys(ctx), Prefs.serpapiKeys(ctx),
            Prefs.braveKeys(ctx), Prefs.searxngUrls(ctx)
        ).any { it.isNotBlank() }

    /**
     * v2.9.4：带诊断信息的检索结果。
     * v2.9.4 之前检索失败会被静默吞掉，导致「新闻分析师」只拿到空列表并回复“找不到相关新闻”，
     * 用户看不到到底是没配 Key、Key 失效还是网络问题。这里把真实原因带回 UI 与 Prompt。
     */
    data class Outcome(
        val items: List<NewsItem> = emptyList(),
        val provider: String = "",
        val error: String = "",
        val attempted: List<String> = emptyList()
    ) {
        val ok: Boolean get() = items.isNotEmpty()

        /** 供 UI / Prompt 展示的一句话状态 */
        fun statusText(): String = when {
            ok -> "新闻检索成功：$provider · ${items.size} 条"
            error.isNotBlank() -> "新闻检索失败：$error"
            else -> "新闻检索失败：未返回任何结果"
        }
    }

    private class Source(
        val name: String,
        val keys: List<String>,
        val call: (String, String, Int) -> List<NewsItem>
    )

    /** 按设置里的优先源排序；其余源作为兜底（避免选了坏源就完全没新闻） */
    private fun orderedSources(ctx: Context, prefer: String): List<Source> {
        val all = listOf(
            Source("Bocha", splitKeys(Prefs.bochaKeys(ctx))) { k, q, d -> bocha(k, q, d) },
            Source("Tavily", splitKeys(Prefs.tavilyKeys(ctx))) { k, q, d -> tavily(k, q, d) },
            Source("SerpAPI", splitKeys(Prefs.serpapiKeys(ctx))) { k, q, d -> serpapi(k, q, d) },
            Source("Brave", splitKeys(Prefs.braveKeys(ctx))) { k, q, d -> brave(k, q, d) },
            Source("SearXNG", splitKeys(Prefs.searxngUrls(ctx))) { k, q, d -> searxng(k, q, d) }
        )
        if (prefer == "auto") return all
        val head = all.filter { it.name.equals(prefer, true) }
        return head + all.filterNot { it.name.equals(prefer, true) }
    }

    /**
     * v2.9.4：多次查询递进检索（先 "名称 代码"，全空再试 "名称 最新消息"），
     * 避免单一查询词过窄导致所有源都返回 0 条。
     */
    /** 去掉汉字之间的空格（数据源返回的股票名形如「五 粮 液」，直接检索会大幅降低命中率） */
    private fun normalizeQuery(q: String): String =
        q.replace(Regex("(?<=[\\u4e00-\\u9fa5])[ \\u3000]+(?=[\\u4e00-\\u9fa5])"), "").trim()

    fun searchDetailed(ctx: Context, queries: List<String>): Outcome {
        val days = Prefs.newsDays(ctx)
        val sources = orderedSources(ctx, Prefs.newsProvider(ctx))
        val configured = sources.filter { it.keys.isNotEmpty() }
        if (configured.isEmpty()) {
            return Outcome(
                error = "未配置任何新闻检索 API（请到 设置 → 新闻检索 API 填写 Key）"
            )
        }
        val attempted = mutableListOf<String>()
        var lastError = ""
        for (raw in queries.filter { it.isNotBlank() }) {
            val q = normalizeQuery(raw)
            if (q.isBlank()) continue
            for (s in configured) {
                if (s.name !in attempted) attempted.add(s.name)
                for (key in s.keys) {
                    try {
                        val r = s.call(key, q, days)
                        if (r.isNotEmpty()) {
                            return Outcome(r, s.name, "", attempted.toList())
                        }
                    } catch (e: Exception) {
                        // 只保留最后一个真实错误，便于用户判断是 Key 失效还是网络超时
                        lastError = "${s.name}：${e.message?.take(120) ?: "请求失败"}"
                    }
                }
            }
        }
        return Outcome(
            error = lastError.ifBlank { "所有新闻源均返回 0 条（查询词可能过窄，或 Key 无可用额度）" },
            attempted = attempted.toList()
        )
    }

    /** 检索个股相关新闻，失败自动尝试下一源；全部失败返回空列表 */
    fun search(ctx: Context, query: String): List<NewsItem> =
        searchDetailed(ctx, listOf(query)).items

    private fun splitKeys(s: String): List<String> =
        s.split(',').map { it.trim() }.filter { it.isNotBlank() }

    /**
     * v2.9.4：各新闻源在 Key 失效 / 额度耗尽 / 参数非法时会返回 200 + 错误体，
     * 旧实现直接 `optJSONArray ?: return emptyList()`，把「接口报错」误当成「0 条新闻」，
     * 于是新闻分析师永远拿不到新闻且没有任何提示。这里统一把错误体识别出来抛出。
     */
    private fun apiError(json: JSONObject): String? {
        val code = json.optInt("code", 200)
        if (code != 200) {
            val msg = json.optString("msg").ifBlank { json.optString("message") }
                .let { if (it == "null") "" else it }
            return "错误码 $code${if (msg.isNotBlank()) "，$msg" else ""}"
        }
        val errStr = json.optString("error")
        if (errStr.isNotBlank() && errStr != "null") return errStr
        json.optJSONObject("error")?.let { e ->
            val d = e.optString("detail").ifBlank { e.optString("message") }
            if (d.isNotBlank()) return d
        }
        json.optJSONObject("detail")?.let { d ->
            val e = d.optString("error")
            if (e.isNotBlank()) return e
        }
        return null
    }

    private fun logEmpty(provider: String, query: String, resp: String) {
        android.util.Log.w("NewsApi", "$provider 返回 0 条 q=$query resp=${resp.take(200)}")
    }

    private fun freshness(days: Int): String = when {
        days <= 1 -> "oneDay"
        days <= 7 -> "oneWeek"
        days <= 30 -> "oneMonth"
        else -> "noLimit"
    }

    private fun tbs(days: Int): String = when {
        days <= 1 -> "qdr:d"
        days <= 7 -> "qdr:w"
        else -> "qdr:m"
    }

    // ---- Bocha（最高优先级）----
    private fun bocha(key: String, query: String, days: Int): List<NewsItem> {
        val body = JSONObject()
            .put("query", query)
            .put("summary", true)
            .put("count", 5)
            .put("freshness", freshness(days))
        val resp = Http.postJson(
            "https://api.bochaai.com/v1/web-search", body.toString(),
            mapOf(
                "Authorization" to "Bearer $key",
                "Content-Type" to "application/json"
            )
        )
        val json = JSONObject(resp)
        apiError(json)?.let { throw RuntimeException(it) }
        // Bocha 实际返回 { data: { webPages: { value: [ {name, snippet, summary, siteName, datePublished} ] } } }
        // 旧实现读的是 data.webpages，与真实字段（驼峰 webPages + value 数组）不符，永远取不到新闻。
        val data = json.optJSONObject("data")
        val pages = data?.optJSONObject("webPages")?.optJSONArray("value")
            ?: data?.optJSONObject("webpages")?.optJSONArray("value")
            ?: data?.optJSONArray("webPages")
            ?: data?.optJSONArray("webpages")
        if (pages == null || pages.length() == 0) {
            logEmpty("Bocha", query, resp)
            return emptyList()
        }
        val out = mutableListOf<NewsItem>()
        for (i in 0 until pages.length()) {
            val o = pages.optJSONObject(i) ?: continue
            val title = o.optString("name").ifBlank { o.optString("title") }.trim()
            if (title.isBlank()) continue
            val snippet = o.optString("summary").ifBlank { o.optString("snippet") }.trim()
            val date = o.optString("datePublished")
                .ifBlank { o.optString("dateLastCrawled") }
                .ifBlank { o.optString("publish_time") }
                .take(10)
            val site = o.optString("siteName").ifBlank { o.optString("site_name") }.ifBlank { "Bocha" }
            out.add(NewsItem(title, snippet, date, site))
        }
        return out
    }

    // ---- Tavily ----
    private fun tavily(key: String, query: String, days: Int): List<NewsItem> {
        val body = JSONObject()
            .put("api_key", key)
            .put("query", query)
            .put("search_depth", "basic")
            .put("max_results", 5)
            .put("days", days)
        val resp = Http.postJson(
            "https://api.tavily.com/search", body.toString(),
            mapOf("Content-Type" to "application/json")
        )
        val json = JSONObject(resp)
        apiError(json)?.let { throw RuntimeException(it) }
        val results = json.optJSONArray("results")
        if (results == null || results.length() == 0) {
            logEmpty("Tavily", query, resp)
            return emptyList()
        }
        val out = mutableListOf<NewsItem>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            out.add(
                NewsItem(
                    o.optString("title", ""),
                    o.optString("content", ""),
                    o.optString("published_date", ""),
                    "Tavily"
                )
            )
        }
        return out
    }

    // ---- SerpAPI ----
    private fun serpapi(key: String, query: String, days: Int): List<NewsItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://serpapi.com/search.json?engine=google&q=$q" +
            "&api_key=$key&num=5&tbs=${tbs(days)}"
        val resp = Http.get(url)
        val json = JSONObject(resp)
        apiError(json)?.let { throw RuntimeException(it) }
        val results = json.optJSONArray("organic_results")
        if (results == null || results.length() == 0) {
            logEmpty("SerpAPI", query, resp)
            return emptyList()
        }
        val out = mutableListOf<NewsItem>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            out.add(
                NewsItem(
                    o.optString("title", ""),
                    o.optString("snippet", ""),
                    o.optString("date", ""),
                    "SerpAPI"
                )
            )
        }
        return out
    }

    // ---- Brave ----
    private fun brave(key: String, query: String, days: Int): List<NewsItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        val freshness = when {
            days <= 1 -> "pd"
            days <= 7 -> "pw"
            else -> "pm"
        }
        val url = "https://api.search.brave.com/res/v1/web/search?q=$q&count=5&freshness=$freshness"
        val resp = Http.get(url, mapOf("X-Subscription-Token" to key))
        val json = JSONObject(resp)
        apiError(json)?.let { throw RuntimeException(it) }
        val results = json.optJSONObject("web")?.optJSONArray("results")
        if (results == null || results.length() == 0) {
            logEmpty("Brave", query, resp)
            return emptyList()
        }
        val out = mutableListOf<NewsItem>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            out.add(
                NewsItem(
                    o.optString("title", ""),
                    o.optString("description", ""),
                    o.optString("page_age", ""),
                    "Brave"
                )
            )
        }
        return out
    }

    // ---- SearXNG 自建实例（兜底）----
    private fun searxng(url: String, query: String, days: Int): List<NewsItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        val base = url.trim().trimEnd('/')
        val timeRange = when {
            days <= 1 -> "day"
            days <= 7 -> "week"
            else -> "month"
        }
        val resp = Http.get("$base/search?q=$q&format=json&time_range=$timeRange")
        val results = JSONObject(resp).optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<NewsItem>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            out.add(
                NewsItem(
                    o.optString("title", ""),
                    o.optString("content", ""),
                    o.optString("publishedDate", ""),
                    "SearXNG"
                )
            )
        }
        return out
    }
}
