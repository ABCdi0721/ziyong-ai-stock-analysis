package com.dsa.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * AI 分析后台通知（v2.6.0）：
 * 点击 AI 分析类按钮后，先在通知栏显示"运行中"（常驻、进度样式，挂在后台等待）；
 * 结果产出后推送"完成"通知，点击一键跳转结果页（ReportActivity / 回测页）。
 *
 * tag 区分同一股票的不同分析类型：ai（AI 报告）/ news（新闻分析）/ multi（多智能体）/ backtest（回测）。
 * 通知 id 与 PendingIntent requestCode 由 code+tag 派生，同一类型重复分析会覆盖旧通知。
 */
object Notifier {

    const val CHANNEL_ID = "ai_analysis"

    private fun idOf(code: String, tag: String): Int =
        (code.hashCode() * 31 + tag.hashCode()) and 0x7fffffff

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "AI 分析通知", NotificationManager.IMPORTANCE_DEFAULT
            )
            ch.description = "多智能体 / AI 报告 / 新闻分析 / A股回测 的运行与完成通知"
            nm.createNotificationChannel(ch)
        }
    }

    /** 分析中通知：常驻 + 不确定进度条，点击回到该股票详情页 */
    fun showRunning(ctx: Context, code: String, tag: String, title: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            ctx, idOf(code, tag),
            Intent(ctx, StockDetailActivity::class.java).putExtra("code", code),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("后台分析中，完成后将推送通知…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(pi)
            .build()
        nm.notify(idOf(code, tag), n)
    }

    /** 完成通知：点击一键跳转 resultTarget（ReportActivity 或回测页） */
    fun showDone(
        ctx: Context,
        code: String,
        tag: String,
        title: String,
        text: String,
        resultTarget: Intent
    ) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            ctx, idOf(code, tag), resultTarget,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        // v2.12.1：挂上小米焦点通知 / 超级岛参数（非小米设备或权限未开时自动降级为普通通知）
        FocusNotifier.attach(ctx, n, title, text)
        nm.notify(idOf(code, tag), n)
    }

    /** 取消（失败 / 用户中途退出） */
    fun cancel(ctx: Context, code: String, tag: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(idOf(code, tag))
    }
}
