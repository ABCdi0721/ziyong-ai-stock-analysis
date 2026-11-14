package com.dsa.mobile

import java.net.URLEncoder

/**
 * v2.11.0 股票搜索联想（腾讯 smartbox，免费无需 Key）
 * GET https://smartbox.gtimg.cn/s3/?v=2&q=<关键词>&t=all
 * 返回 v_hint="市场~代码~\uXXXX名称~拼音~类别^..."
 * 类别 GP-A = A 股；基金/债券/指数（KJ 等）一律过滤。
 * 支持按中文名、数字代码、拼音首字母（如 wly）搜索。
 */
object StockSearchApi {

    data class Hit(
        val code: String,   // 纯数字代码，如 000858
        val name: String    // 已解码中文名称
    )

    /** 后台线程调用：按名称/代码/拼音搜索 A 股，失败返回空列表 */
    fun search(query: String): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        val url = "https://smartbox.gtimg.cn/s3/?v=2&q=$enc&t=all"
        val raw = try {
            Http.get(url, mapOf("Referer" to "https://stockapp.finance.qq.com/"))
        } catch (e: Exception) {
            return emptyList()
        }
        val m = Regex("v_hint=\"([^\"]*)\"").find(raw) ?: return emptyList()
        val out = mutableListOf<Hit>()
        m.groupValues[1].split("^").forEach { entry ->
            val f = entry.split("~")
            if (f.size < 5) return@forEach
            val code = f[1]
            val name = unescape(f[2])
            val kind = f[4]
            // 只收 A 股（沪深 bj 北交所均 GP-A），排除基金 KJ / 债券 / 指数
            if (kind != "GP-A") return@forEach
            if (code.isBlank() || name.isBlank()) return@forEach
            out += Hit(code = code, name = name)
        }
        return out
    }

    /** 响应体里的名称是 \uXXXX 字面转义，手动还原为真实字符 */
    private fun unescape(s: String): String {
        if (!s.contains("\\u")) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (i + 6 <= s.length && s[i] == '\\' && s[i + 1] == 'u') {
                try {
                    val cp = s.substring(i + 2, i + 6).toInt(16)
                    sb.append(cp.toChar())
                    i += 6
                    continue
                } catch (_: Exception) {
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }
}
