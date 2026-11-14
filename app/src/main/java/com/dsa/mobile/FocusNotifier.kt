package com.dsa.mobile

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * 小米焦点通知 / 超级岛（Xiaomi HyperOS）适配层（v2.12.1）
 *
 * 原理：HyperOS 2 起支持「焦点通知」，HyperOS 3 起升级为「超级岛」。
 * 客户端只需在通知 extras 里带一个 `miui.focus.param`（JSON，官方限制 ≤ 3072 字节），
 * 系统按协议版本自动渲染成状态栏焦点通知 / 岛上摘要态。
 *
 * 权限现实：应用必须在小米开放平台开通「小米超级岛服务」并通过场景审核，
 * 才会真正上岛；未开通时系统会把带参通知降级为普通通知，不影响原有提醒。
 * 所以这里始终「能带就带」——权限一旦到位即可生效，无需再改代码。
 */
object FocusNotifier {

    const val KEY_PARAM = "miui.focus.param"
    const val KEY_PICS = "miui.focus.pics"
    private const val TAG = "FocusNotifier"
    private const val MAX_PARAM_BYTES = 3072
    private const val PROVIDER = "content://miui.statusbar.notification.public"

    private const val PIC_SUMMARY = "miui.focus.pic_summary"
    private const val PIC_TICKER = "miui.focus.pic_ticker"
    private const val PIC_AOD = "miui.focus.pic_aod"

    /** 设备能力快照（设置页 / 诊断弹窗直接展示） */
    data class Capability(
        val miui: Boolean,          // 小米系 ROM
        val island: Boolean,        // 系统是否支持超级岛
        val protocol: Int,          // 0=不支持 1=OS1 2=OS2 3=OS3
        val canShowFocus: Boolean   // 当前应用是否已拿到焦点通知权限
    ) {
        val osLabel: String
            get() = when (protocol) {
                1 -> "OS1"
                2 -> "OS2（焦点通知）"
                3 -> "OS3（超级岛）"
                else -> "未知"
            }
    }

    fun capability(ctx: Context): Capability {
        val miui = isMiui()
        val proto = if (miui) protocol(ctx) else 0
        val focus = if (miui && proto > 0) canShowFocus(ctx) else false
        return Capability(miui, isIslandSupported(), proto, focus)
    }

    /**
     * 把焦点通知参数挂到通知上；返回是否挂载成功。
     * 任何异常都只落日志，绝不能影响通知本身。
     */
    fun attach(ctx: Context, n: Notification, title: String, text: String): Boolean {
        return try {
            if (!isMiui()) return false
            val proto = protocol(ctx)
            if (proto < 2) return false
            val param = buildParam(title, text)
            val bytes = param.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_PARAM_BYTES) {
                Log.w(TAG, "param skipped: $bytes bytes > $MAX_PARAM_BYTES")
                return false
            }
            // 图片先挂：param 里的 pic 字段要能在这里找到对应 Icon
            n.extras.putBundle(KEY_PICS, picsBundle(ctx))
            n.extras.putString(KEY_PARAM, param)
            Log.i(
                TAG,
                "param attached protocol=$proto bytes=$bytes canShowFocus=${canShowFocus(ctx)}"
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "param skipped: ${e.message}")
            false
        }
    }

    private fun picsBundle(ctx: Context): Bundle {
        val icon = Icon.createWithResource(ctx, R.mipmap.ic_launcher)
        return Bundle().apply {
            putParcelable(PIC_SUMMARY, icon)
            putParcelable(PIC_TICKER, icon)
            putParcelable(PIC_AOD, icon)
        }
    }

    // ---------- 参数构建 ----------

    /**
     * 一次性「分析完成」场景的最小参数集：
     * ticker（状态栏）+ aodTitle（息屏）+ 岛（大岛 A 区文案 + 小岛应用图标）+ 焦点通知卡。
     */
    private fun buildParam(title: String, text: String): String {
        val stock = extractStock(title)
        val brief = briefText(text)

        // 大岛 A 区：左侧图标 + 图文。官方示例字段名为 miui.focus.paramtextInfo
        val left = JSONObject()
            .put("type", 1)
            .put("picInfo", JSONObject().put("type", 1).put("pic", PIC_SUMMARY))
            .put("miui.focus.paramtextInfo", JSONObject()
                .put("frontTitle", "分析完成")
                .put("title", stock)
                .put("content", brief)
                .put("useHighLight", true)
            )

        val island = JSONObject()
            .put("islandProperty", 1)
            .put("islandTimeout", 90)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", left)
                .put("picInfo", JSONObject().put("type", 1).put("pic", PIC_SUMMARY))
            )
            .put("smallIslandArea", JSONObject()
                .put("picInfo", JSONObject().put("type", 1).put("pic", PIC_SUMMARY))
            )

        val v2 = JSONObject()
            .put("protocol", 1)
            // business 为必选字段（运营场景标识），缺失时系统无法归入岛业务
            .put("business", "stock_analysis")
            .put("enableFloat", true)
            .put("islandFirstFloat", true)
            .put("updatable", false)
            .put("timeout", 720)
            .put("ticker", "分析完成")
            .put("tickerPic", PIC_TICKER)
            .put("aodTitle", "分析完成")
            .put("aodPic", PIC_AOD)
            .put("param_island", island)
            .put("baseInfo", JSONObject()
                .put("type", 2)
                .put("title", "分析完成")
                .put("content", if (brief.isBlank()) stock else "$stock · $brief")
                .put("colorTitle", "#006EFF")
            )
            .put("hintInfo", JSONObject()
                .put("type", 1)
                .put("title", "点击查看报告")
            )

        return JSONObject().put("param_v2", v2).toString()
    }

    /** 「五粮液」AI 分析完成 → 五粮液（岛上文案短，取 6 字内） */
    private fun extractStock(title: String): String {
        val s = Regex("「(.+?)」").find(title)?.groupValues?.getOrNull(1) ?: title
        val clean = s.replace("「", "").replace("」", "").trim()
        return if (clean.length <= 6) clean else clean.take(6)
    }

    /** 「综合评分 65 · 买入，点击查看报告」→「评分 65 · 买入」 */
    private fun briefText(text: String): String {
        val t = text
            .replace("，点击查看报告", "")
            .replace("点击查看报告", "")
            .replace("综合评分", "评分")
            .trim()
        return if (t.length <= 18) t else t.take(18)
    }

    // ---------- 能力查询 ----------

    fun isMiui(): Boolean {
        val m = (Build.MANUFACTURER + Build.BRAND + Build.MODEL).lowercase()
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return true
        return sysProp("ro.miui.ui.version.name").isNotBlank() ||
            sysProp("ro.mi.os.version.name").isNotBlank()
    }

    fun isIslandSupported(): Boolean = try {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        (m.invoke(null, "persist.sys.feature.island", false) as? Boolean) ?: false
    } catch (e: Exception) {
        false
    }

    /** 1=OS1 2=OS2 3=OS3；0=不支持 */
    fun protocol(ctx: Context): Int = try {
        Settings.System.getInt(ctx.contentResolver, "notification_focus_protocol", 0)
    } catch (e: Exception) {
        0
    }

    /** 应用自身的焦点通知开关（必须由本应用调用，adb 无法代查） */
    fun canShowFocus(ctx: Context): Boolean = try {
        val extras = Bundle().apply { putString("package", ctx.packageName) }
        val b = ctx.contentResolver.call(Uri.parse(PROVIDER), "canShowFocus", null, extras)
        b?.getBoolean("canShowFocus", false) ?: false
    } catch (e: Exception) {
        Log.i(TAG, "canShowFocus unavailable: ${e.message}")
        false
    }

    fun osVersion(): String =
        sysProp("ro.mi.os.version.incremental").ifBlank { Build.DISPLAY }

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun sysProp(key: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("get", String::class.java)
        (m.invoke(null, key) as? String) ?: ""
    } catch (e: Exception) {
        ""
    }
}
