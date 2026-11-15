package com.dsa.mobile

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.dsa.mobile.databinding.FragmentMineBinding

class MineFragment : Fragment() {

    private var binding: FragmentMineBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val b = FragmentMineBinding.inflate(inflater, container, false)
        binding = b
        b.entrySettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        b.entryHistory.setOnClickListener {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
        }
        // v2.8.0：主题外观入口
        b.entryTheme.setOnClickListener {
            startActivity(Intent(requireContext(), ThemeActivity::class.java))
        }
        // v2.12.15：备份与恢复（WebDAV）
        b.entryBackup.setOnClickListener {
            startActivity(Intent(requireContext(), BackupActivity::class.java))
        }
        // v2.8.1：修改标签（点击修改标题文字）
        b.entryAppTitle.setOnClickListener { showTitleDialog() }
        // v2.12.1：我的收藏
        b.entryFavorites.setOnClickListener {
            startActivity(Intent(requireContext(), FavoriteActivity::class.java))
        }
        // v2.12.1：通知与超级岛（能力状态 + 发送测试通知）
        b.entryFocus.setOnClickListener { showFocusDialog() }
        return b.root
    }

    /** v2.12.1：超级岛能力诊断 + 手动发一条测试完成通知 */
    private fun showFocusDialog() {
        val ctx = requireContext()
        val cap = FocusNotifier.capability(ctx)
        val msg = buildString {
            append("设备：").append(FocusNotifier.deviceName()).append('\n')
            append("系统：").append(FocusNotifier.osVersion()).append('\n')
            append("焦点通知协议：").append(cap.osLabel).append('\n')
            append("超级岛：").append(if (cap.island) "系统支持" else "系统不支持").append('\n')
            append("本应用焦点权限：").append(if (cap.canShowFocus) "已开启" else "未开启").append('\n')
            append("岛参数：已按官方字段写入（协议 ").append(cap.protocol).append("）").append('\n')
            append("上岛授权：").append(if (cap.canShowFocus) "待小米开放平台开通" else "未开通")
            append("\n\n")
            if (!cap.canShowFocus) {
                append("焦点权限未开启时，完成通知照常显示在通知栏，但不会上岛。\n")
            }
            append("完成通知在任何情况下都会正常显示在通知栏。")
            append("小米超级岛需在小米开放平台开通「小米超级岛服务」并提报场景，")
            append("开通后同一条通知会自动以上岛形态展示，本应用无需再改代码。")
        }
        AlertDialog.Builder(ctx)
            .setTitle("通知与超级岛")
            .setMessage(msg)
            .setPositiveButton("发送测试通知") { _, _ -> sendTestNotification() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun sendTestNotification() {
        val ctx = requireContext()
        Notifier.showDone(
            ctx, "TEST", "focus_test",
            "「测试」AI 分析完成",
            "综合评分 88 · 买入，点击查看报告",
            Intent(ctx, MainActivity::class.java)
        )
        Toast.makeText(ctx, "已发送测试通知，请看屏幕顶部与通知栏", Toast.LENGTH_SHORT).show()
    }

    private fun showTitleDialog() {
        val ctx = requireContext()
        val input = EditText(ctx)
        input.setText(Prefs.appTitle(ctx))
        input.setSingleLine(true)
        input.setPadding(24, 8, 24, 8)
        AlertDialog.Builder(ctx)
            .setTitle("修改标签")
            .setMessage("修改后所有页面的顶部标题都会更新")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isBlank()) {
                    Toast.makeText(ctx, "名称不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.saveAppTitle(ctx, t)
                    (activity as? BaseActivity)?.supportActionBar?.setTitle(t)
                    binding?.mineTitle?.text = t
                    binding?.currentAppTitle?.text = t
                    Toast.makeText(ctx, "标题已更新：$t", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val b = binding ?: return
        val ctx = requireContext()
        ThemeManager.applyTagAlphaTree(b.root, ctx)
        val token = Prefs.tushareToken(ctx)
        val llm = Prefs.hasLlmConfig(ctx)
        b.mineTitle.text = Prefs.appTitle(ctx)
        b.currentAppTitle.text = Prefs.appTitle(ctx)
        b.configStatus.text = buildString {
            append("数据源：")
            append(if (token.isNotBlank()) "Tushare Token 已配置" else "腾讯免费行情（未配置 Token）")
            append("   ")
            append("大模型：")
            append(if (llm) "已配置" else "未配置")
        }

        // v2.12.1：收藏数量 / 超级岛状态（查询放在子线程，避免内容提供者调用卡住主线程）
        Thread {
            val favCount = Prefs.favoriteCount(ctx)
            val cap = FocusNotifier.capability(ctx)
            activity?.runOnUiThread {
                val bb = binding ?: return@runOnUiThread
                bb.favCount.text = if (favCount > 0) "$favCount 份" else "暂无"
                bb.focusState.text = when {
                    !cap.miui -> "普通通知"
                    cap.protocol >= 3 && cap.canShowFocus -> "焦点通知可用"
                    cap.island -> "待开通权限"
                    cap.protocol == 2 -> "焦点通知"
                    else -> "普通通知"
                }
            }
        }.start()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
