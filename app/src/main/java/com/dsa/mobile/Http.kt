package com.dsa.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** HTTP 封装 */
object Http {
    /** 通用浏览器 UA，降低免费接口对非浏览器请求的限流概率 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // 仅用 HTTP/1.1：部分行情 CDN（如东财 push2）对 HTTP/2 帧处理异常，
        // 会导致 "unexpected end of stream" 流提前中断
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
        b.header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            val body = resp.body ?: throw RuntimeException("空响应 HTTP ${resp.code}")
            // 腾讯行情接口返回 GBK，按字节读再转码
            val bytes = body.bytes()
            val text = detectAndDecode(bytes, resp)
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $text")
            return text
        }
    }

    fun postJson(
        url: String, json: String, headers: Map<String, String> = emptyMap(),
        httpClient: OkHttpClient = client
    ): String {
        val b = Request.Builder().url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        httpClient.newCall(b.build()).execute().use { resp ->
            val body = resp.body ?: throw RuntimeException("空响应 HTTP ${resp.code}")
            val text = body.string()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $text")
            return text
        }
    }

    private fun detectAndDecode(bytes: ByteArray, resp: okhttp3.Response): String {
        // 有 charset 头直接用；否则尝试 UTF-8 严格解码，失败回退 GBK
        val charset = resp.header("Content-Type")?.let { ct ->
            Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1)
        }
        if (charset != null && charset.equals("gbk", true)) {
            return String(bytes, Charset.forName("GBK"))
        }
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            String(bytes, Charset.forName("GBK"))
        }
    }
}
