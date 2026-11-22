package com.dsa.mobile

import android.content.Context
import java.io.File

/**
 * v2.12.46 无用缓存清理。
 *
 * 只清理可自动重建的临时数据：
 * - filesDir 下白名单：快照缓存 snapshot_cache.json、运行日志 run_log.txt；
 * - cacheDir 系统缓存目录内的全部内容；
 * - 进程内日K LRU 内存缓存。
 *
 * 明确保留（不在清理范围）：
 * - SharedPreferences 内的历史报告、收藏、自选股、API 配置 / Token / 模型、持仓与各项设置；
 * - filesDir/theme_bg.png 用户自定义背景图。
 */
object CacheCleaner {

    /** filesDir 中允许删除的临时文件名白名单 */
    private val filesWhitelist = setOf("snapshot_cache.json", "run_log.txt")

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach { f ->
            size += if (f.isDirectory) dirSize(f) else f.length()
        }
        return size
    }

    /** 统计当前可清理的缓存字节数 */
    fun cacheSize(ctx: Context): Long {
        var size = 0L
        filesWhitelist.forEach { name ->
            val f = File(ctx.filesDir, name)
            if (f.exists()) size += f.length()
        }
        size += dirSize(ctx.cacheDir)
        return size
    }

    /** 执行清理，返回实际释放的字节数 */
    fun clear(ctx: Context): Long {
        val before = cacheSize(ctx)
        filesWhitelist.forEach { name ->
            val f = File(ctx.filesDir, name)
            if (f.exists()) f.delete()
        }
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        SnapshotCache.clearMemory()
        val after = cacheSize(ctx)
        return (before - after).coerceAtLeast(0L)
    }

    /** 容量人性化格式化 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
