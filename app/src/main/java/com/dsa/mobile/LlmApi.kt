package com.dsa.mobile

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** OpenAI 兼容大模型接口 */
object LlmApi {

    /** 单次调用的 token 用量（缓存命中量仅部分供应商返回，如 DeepSeek 的 prompt_cache_hit_tokens） */
    data class Usage(val prompt: Int, val completion: Int, val cached: Int) {
        val total: Int get() = prompt + completion
    }

    /** 最近一次调用的用量 */
    @Volatile
    var lastUsage: Usage? = null
        private set

    private var accPrompt = 0
    private var accCompletion = 0
    private var accCached = 0
    private var accCalls = 0

    /** LLM 专用客户端：长报告输出可能超过 45 秒，通用 client 的 readTimeout 会把正常请求掐断 */
    private val llmClient: OkHttpClient by lazy {
        Http.client.newBuilder()
            .readTimeout(150, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }

    /** 清空累计用量（每次分析开始时调用） */
    @Synchronized
    fun resetStats() {
        accPrompt = 0; accCompletion = 0; accCached = 0; accCalls = 0
    }

    /** 当前累计用量 */
    @Synchronized
    fun stats(): Usage = Usage(accPrompt, accCompletion, accCached)

    /** 调用次数 */
    @Synchronized
    fun callCount(): Int = accCalls

    private fun recordUsage(u: Usage) {
        lastUsage = u
        synchronized(this) {
            accPrompt += u.prompt
            accCompletion += u.completion
            accCached += u.cached
            accCalls += 1
        }
        android.util.Log.i(
            "LlmToken",
            "prompt=${u.prompt} cached=${u.cached} completion=${u.completion} total=${u.total} | 累计 calls=$accCalls prompt=$accPrompt cached=$accCached completion=$accCompletion"
        )
    }

    /** 连通性测试结果（单模型） */
    data class PingItem(
        val model: String,
        val ok: Boolean,
        val latencyMs: Long,
        val tokens: Int,
        val message: String
    )

    /**
     * 调用 chat/completions
     * 模型名支持逗号分隔（如 "deepseek-v4-flash,deepseek-flash"），
     * 按顺序逐个尝试；全部失败后尝试备选模型。
     * 单个模型内部对限流 / 5xx / 超时做退避重试（最多 2 次）。
     * @return 模型回复文本
     */
    fun chat(
        baseUrl: String, apiKey: String, model: String, system: String, user: String,
        temperature: Double = 0.7, fallbackModel: String? = null, maxTokens: Int = 2048,
        onRetry: ((String) -> Unit)? = null
    ): String {
        val primary = model.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val fallback = fallbackModel?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val all = (primary + fallback).distinct()
        if (all.isEmpty()) throw RuntimeException("未配置模型")
        var lastErr: Exception? = null
        for (m in all) {
            try {
                return attempt(baseUrl, apiKey, m, system, user, temperature, maxTokens, onRetry)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: RuntimeException("LLM 调用失败")
    }

    /** 尝试单个模型名：/v1/chat/completions 404 时回退 /chat/completions */
    private fun attempt(
        baseUrl: String, apiKey: String, model: String, system: String, user: String,
        temperature: Double, maxTokens: Int, onRetry: ((String) -> Unit)?
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        var lastErr: Exception? = null
        // 优先 /v1/chat/completions，404 时回退 /chat/completions
        for (path in listOf("/v1/chat/completions", "/chat/completions")) {
            var retry = 0
            while (true) {
                try {
                    val url = normalize(baseUrl) + path
                    val resp = Http.postJson(
                        url, body.toString(),
                        mapOf("Authorization" to "Bearer $apiKey"), llmClient
                    )
                    val json = JSONObject(resp)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val msg = choices.optJSONObject(0)?.optJSONObject("message")
                        val content = extractContent(msg)
                        if (content.isNotBlank()) {
                            parseUsage(json)?.let { recordUsage(it) }
                            return content.trim()
                        }
                    }
                    val err = json.optJSONObject("error")?.optString("message")
                    throw RuntimeException("LLM 返回异常: ${err ?: resp.take(200)}")
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    // 404 说明该路径不存在，换另一条路径再试，不做退避
                    if (msg.contains("HTTP 404")) {
                        lastErr = e
                        break
                    }
                    // 限流 / 5xx / 超时 / 断流：退避后重试
                    if (isRetryable(msg) && retry < retryDelays.size) {
                        val delay = if (msg.contains("HTTP 429")) {
                            if (retry == 0) 6000L else 20000L
                        } else {
                            retryDelays[retry]
                        }
                        retry++
                        android.util.Log.w("LlmApi", "调用失败，${delay}ms 后重试（第 $retry 次）：$msg")
                        onRetry?.invoke("网络繁忙，正在重试（第 $retry 次）")
                        try { Thread.sleep(delay) } catch (ie: InterruptedException) { throw ie }
                        continue
                    }
                    throw e
                }
            }
        }
        throw lastErr ?: RuntimeException("LLM 调用失败")
    }

    /** 普通失败的退避间隔（毫秒） */
    private val retryDelays = longArrayOf(4_000L, 12_000L)

    /**
     * 判断异常是否值得重试。
     * 鉴权错误、路径错误、模型返回内容为空一律不重试（重试也不会变好）。
     */
    private fun isRetryable(msg: String): Boolean {
        val m = msg.lowercase()
        if (m.contains("llm 返回异常")) return false
        if (m.contains("http 400") || m.contains("http 401") || m.contains("http 403") || m.contains("http 404")) {
            return false
        }
        if (m.contains("http 429")) return true
        if (Regex("http 5\\d\\d").containsMatchIn(m)) return true
        return m.contains("timeout") || m.contains("timed out") ||
            m.contains("unexpected end of stream") || m.contains("stream was reset") ||
            m.contains("connection reset") || m.contains("connection refused") ||
            m.contains("broken pipe") || m.contains("eof") ||
            m.contains("socket") || m.contains("failed to connect") ||
            m.contains("network is unreachable") || m.contains("ssl")
    }

    /**
     * 最少 token 的连通性测试：max_tokens=8，单轮 ping，不产生长输出。
     * 模型名支持逗号分隔，逐个测试并返回各自结果。
     */
    fun pingAll(
        baseUrl: String, apiKey: String, models: String, temperature: Double = 0.0
    ): List<PingItem> {
        val list = models.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return emptyList()
        return list.map { pingOne(baseUrl, apiKey, it, temperature) }
    }

    private fun pingOne(
        baseUrl: String, apiKey: String, model: String, temperature: Double
    ): PingItem {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_tokens", 8)
            .put("stream", false)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "Reply with ok only."))
                    .put(JSONObject().put("role", "user").put("content", "ping"))
            )
        var lastMsg = "未知错误"
        for (path in listOf("/v1/chat/completions", "/chat/completions")) {
            val t0 = System.currentTimeMillis()
            try {
                val url = normalize(baseUrl) + path
                val resp = Http.postJson(
                    url, body.toString(), mapOf("Authorization" to "Bearer $apiKey"), llmClient
                )
                val json = JSONObject(resp)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val u = parseUsage(json)
                    return PingItem(
                        model, true, System.currentTimeMillis() - t0,
                        u?.total ?: -1, "接口已连通"
                    )
                }
                val err = json.optJSONObject("error")?.optString("message")
                return PingItem(
                    model, false, System.currentTimeMillis() - t0, -1,
                    (err ?: "接口未返回 choices，可能模型名不正确或接口格式不兼容").take(160)
                )
            } catch (e: Exception) {
                lastMsg = (e.message ?: e.javaClass.simpleName).take(180)
                // 只有 404 说明该路径不存在，换另一条路径继续；其余错误直接返回
                if (!lastMsg.contains("HTTP 404")) {
                    return PingItem(model, false, System.currentTimeMillis() - t0, -1, lastMsg)
                }
            }
        }
        return PingItem(model, false, 0, -1, lastMsg)
    }

    /** 解析 usage：DeepSeek 用 prompt_cache_hit_tokens，OpenAI 用 prompt_tokens_details.cached_tokens */
    private fun parseUsage(json: JSONObject): Usage? {
        val u = json.optJSONObject("usage") ?: return null
        val prompt = u.optInt("prompt_tokens", 0)
        val completion = u.optInt("completion_tokens", 0)
        if (prompt == 0 && completion == 0) return null
        var cached = u.optInt("prompt_cache_hit_tokens", 0)
        if (cached == 0) {
            cached = u.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0) ?: 0
        }
        return Usage(prompt, completion, cached)
    }

    /** 兼容 content 字符串与数组两种格式（部分供应商返回 [{"type":"text","text":"..."}]） */
    private fun extractContent(msg: JSONObject?): String {
        if (msg == null) return ""
        if (!msg.isNull("content")) {
            val raw = msg.opt("content")
            if (raw is String && raw.isNotBlank()) return raw
            val arr = msg.optJSONArray("content")
            if (arr != null) {
                val t = buildString {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i)
                        append(item?.optString("text") ?: "")
                    }
                }.trim()
                if (t.isNotBlank()) return t
            }
        }
        // 推理类模型（如 deepseek-reasoner）正文可能在 reasoning_content
        val reasoning = msg.optString("reasoning_content", "")
        return reasoning
    }

    private fun normalize(baseUrl: String): String {
        var b = baseUrl.trim().trimEnd('/')
        if (b.endsWith("/v1")) return b
        return b
    }
}
