package com.dsa.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dsa.mobile.databinding.ActivityReportBinding
import java.time.LocalDate

class ReportActivity : BaseActivity() {

    private lateinit var binding: ActivityReportBinding
    private var code = ""
    private var name = ""
    private var score = 0
    private var signal = ""
    private var date = ""
    private var time = ""
    private var report = ""
    private var readonly = false
    /** v2.12.1 收藏：指向历史报告唯一 id，0 表示还没落库 */
    private var historyId = 0L
    private var favorited = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI 分析报告"

        code = intent.getStringExtra("code") ?: ""
        name = intent.getStringExtra("name") ?: ""
        score = intent.getIntExtra("score", 0)
        signal = intent.getStringExtra("signal") ?: ""
        date = intent.getStringExtra("date") ?: LocalDate.now().toString()
        time = intent.getStringExtra("time") ?: ""
        report = intent.getStringExtra("report") ?: ""
        readonly = intent.getBooleanExtra("readonly", false)
        historyId = intent.getLongExtra("history_id", 0L)

        binding.reportTitle.text = "$name（$code）"
        binding.reportSub.text = if (time.isBlank()) "$date · 信号：$signal" else "$date $time · 信号：$signal"
        binding.reportScore.text = score.toString()
        binding.reportContent.text = report

        if (readonly) binding.btnSaveReport.visibility = android.view.View.GONE
        else binding.btnSaveReport.isEnabled = true
        binding.btnSaveReport.setOnClickListener {
            Prefs.saveHistory(
                this, HistoryReport(
                    code = code, name = name, date = date,
                    score = score, signal = signal, report = report,
                    time = time.ifBlank { Prefs.clock() }
                )
            )
            Toast.makeText(this, "已保存到历史报告", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnFavReport.setOnClickListener { toggleFavorite() }
        refreshFavoriteState()
    }

    // ---------- v2.12.1 收藏 ----------

    private fun ensureHistoryId(): Long {
        if (historyId != 0L) return historyId
        historyId = Prefs.findHistoryId(this, code, date, report)
        return historyId
    }

    private fun refreshFavoriteState() {
        val id = ensureHistoryId()
        favorited = id != 0L && Prefs.isFavorite(this, id)
        binding.btnFavReport.text = if (favorited) "★ 已收藏" else "☆ 收藏"
    }

    private fun toggleFavorite() {
        var id = ensureHistoryId()
        if (id == 0L) {
            // 生成后还没落库（例如 readonly=false 的报告）：收藏时先落库，保证收藏有实体
            id = Prefs.saveHistory(
                this, HistoryReport(
                    code = code, name = name, date = date,
                    score = score, signal = signal, report = report,
                    time = time.ifBlank { Prefs.clock() }
                )
            )
            historyId = id
        }
        favorited = !favorited
        Prefs.setFavorite(this, id, favorited)
        binding.btnFavReport.text = if (favorited) "★ 已收藏" else "☆ 收藏"
        Toast.makeText(
            this,
            if (favorited) "已收藏，可在「我的 → 我的收藏」查看" else "已取消收藏",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onResume() {
        super.onResume()
        refreshFavoriteState()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
