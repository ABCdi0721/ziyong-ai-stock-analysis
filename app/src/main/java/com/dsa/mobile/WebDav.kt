package com.dsa.mobile

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.12.15：WebDAV 备份与恢复客户端（适配坚果云等标准 WebDAV 服务）。
 *
 * 认证方式为 HTTP Basic Auth：账号 + 第三方应用授权码（坚果云在「安全选项 → 第三方应用授权」生成）。
 * 备份文件存放在配置的子文件夹下，文件名 `dsa_backup_yyyyMMdd_HHmmss.json`。
 * 所有方法均为同步阻塞调用，须在子线程执行。
 */
object WebDav {

    data class Result(val success: Boolean, val message: String)

    private const val BACKUP_PREFIX = "dsa_backup_"
    private const val BACKUP_SUFFIX = ".json"

    /** 备份文件名（按时间戳排序，越新越大） */
    fun backupFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "$BACKUP_PREFIX$ts$BACKUP_SUFFIX"
    }

    private fun encodeSeg(seg: String): String =
        URLEncoder.encode(seg.trim(), "UTF-8").replace("+", "%20")

    /** 拼接 根地址 + 子文件夹 的远端目录 URL（末尾带 /） */
    private fun dirUrl(base: String, folder: String): String {
        val root = base.trim().trimEnd('/') + "/"
        val parts = folder.trim().split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) return root
        return root + parts.joinToString("/") { encodeSeg(it) } + "/"
    }

    private fun authHeader(user: String, pass: String): String {
        val token = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $token"
    }

    private fun Request.Builder.auth(user: String, pass: String) =
        header("Authorization", authHeader(user, pass))

    /** 逐级创建子文件夹（已存在忽略 405/409） */
    private fun ensureFolder(base: String, folder: String, user: String, pass: String) {
        val root = base.trim().trimEnd('/') + "/"
        val parts = folder.trim().split("/").filter { it.isNotBlank() }
        var cursor = root
        parts.forEach { seg ->
            cursor += encodeSeg(seg) + "/"
            try {
                val req = Request.Builder().url(cursor).method("MKCOL", null).auth(user, pass).build()
                Http.client.newCall(req).execute().use { /* 201/405/409 都视为已就绪 */ }
            } catch (_: Exception) {
            }
        }
    }

    /** 测试 WebDAV 配置是否可连通（PROPFIND 子文件夹，Depth 0） */
    fun test(base: String, user: String, pass: String, folder: String): Result {
        if (base.isBlank() || user.isBlank() || pass.isBlank()) {
            return Result(false, "请填写服务器地址、账号和授权码")
        }
        return try {
            val url = dirUrl(base, folder)
            val req = Request.Builder()
                .url(url)
                .method("PROPFIND", null)
                .auth(user, pass)
                .header("Depth", "0")
                .build()
            Http.client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200, 207, 204 -> Result(true, "连接成功，WebDAV 可用")
                    401, 403 -> Result(false, "账号或授权码不正确")
                    404 -> Result(false, "服务器地址或子文件夹不存在")
                    else -> Result(false, "服务返回状态码 ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Result(false, "无法连接，请检查网络与服务器地址")
        }
    }

    /** 上传备份 JSON 到子文件夹；返回结果与远端文件名 */
    fun upload(
        base: String, user: String, pass: String, folder: String,
        fileName: String, body: String
    ): Result {
        if (base.isBlank() || user.isBlank() || pass.isBlank()) {
            return Result(false, "请填写服务器地址、账号和授权码")
        }
        return try {
            ensureFolder(base, folder, user, pass)
            val url = dirUrl(base, folder) + encodeSeg(fileName)
            val req = Request.Builder()
                .url(url)
                .put(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .auth(user, pass)
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result(true, "备份已上传：$fileName")
                else Result(false, "上传失败，状态码 ${resp.code}")
            }
        } catch (e: Exception) {
            Result(false, "上传失败，请检查网络后重试")
        }
    }

    /** 列出子文件夹内的备份文件名，按时间戳倒序（最新在前）；无备份返回空列表 */
    fun listBackups(base: String, user: String, pass: String, folder: String): List<String> {
        val out = mutableListOf<String>()
        return try {
            val url = dirUrl(base, folder)
            val req = Request.Builder()
                .url(url)
                .method("PROPFIND", null)
                .auth(user, pass)
                .header("Depth", "1")
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val text = resp.body?.string() ?: return emptyList()
                val hrefs = Regex("<[^:>]*:?href>([^<]+)</[^:>]*:?href>").findAll(text)
                hrefs.forEach { m ->
                    val href = m.groupValues[1]
                    val name = href.substringAfterLast('/').substringBefore('?')
                    if (name.startsWith(BACKUP_PREFIX) && name.endsWith(BACKUP_SUFFIX)) {
                        out.add(name)
                    }
                }
            }
            out.sortedDescending()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 下载指定备份文件内容；失败返回 null */
    fun download(
        base: String, user: String, pass: String, folder: String, fileName: String
    ): String? {
        return try {
            val url = dirUrl(base, folder) + encodeSeg(fileName)
            val req = Request.Builder().url(url).get().auth(user, pass).build()
            Http.client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 校验备份内容是否为本应用导出的合法 JSON */
    fun isValidBackup(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return try {
            val root = JSONObject(text)
            root.optJSONObject("data") != null
        } catch (_: Exception) {
            false
        }
    }
}
