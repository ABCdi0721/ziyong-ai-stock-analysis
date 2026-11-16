package com.dsa.mobile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** 历史报告容器页；可通过 extra code 进入时只看某只股票的报告 */
class HistoryActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "历史报告"

        val code = intent.getStringExtra("code").orEmpty()
        val fragment = HistoryFragment().apply {
            arguments = Bundle().apply { putString("code", code) }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.history_container, fragment)
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
