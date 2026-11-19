package com.dsa.mobile

import android.content.Context
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.12.36 本地轻量运行日志（F2）。
 *
 * - 记录时间、数据源、事件、明细（总数 / 命中 / 失败源等），落 filesDir 文本文件；
 * - 上限 [MAX_LINES] 行，超出保留尾部，避免长期占用存储；
 * - 仅本地排查用，不上报、不含账号口令。
 */
object AppLog {

    private const val FILE = "run_log.txt"
    private const val MAX_LINES = 500
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 记录一条运行日志；写失败静默忽略，不影响主流程 */
    fun record(ctx: Context, source: String, event: String, detail: String = "") {
        try {
            val line = buildString {
                append(tsFmt.format(Date()))
                append(" | ").append(if (source.isBlank()) "-" else source)
                append(" | ").append(event)
                if (detail.isNotBlank()) append(" | ").append(detail)
            }
            val f = java.io.File(ctx.filesDir, FILE)
            val lines = if (f.exists()) f.readLines(Charsets.UTF_8).toMutableList() else mutableListOf()
            lines.add(line)
            while (lines.size > MAX_LINES) lines.removeAt(0)
            f.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        } catch (e: IOException) {
            // 日志写失败不阻塞业务
        }
    }

    /** 读取最近 n 条日志（新的在后）；无记录返回空列表 */
    fun recent(ctx: Context, n: Int = 50): List<String> {
        return try {
            val f = java.io.File(ctx.filesDir, FILE)
            if (!f.exists()) emptyList()
            else f.readLines(Charsets.UTF_8).takeLast(n)
        } catch (e: IOException) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        try {
            java.io.File(ctx.filesDir, FILE).delete()
        } catch (e: Exception) {
            // ignore
        }
    }
}
