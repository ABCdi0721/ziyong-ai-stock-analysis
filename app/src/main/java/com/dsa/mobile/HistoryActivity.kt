package com.dsa.mobile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** 历史报告容器页 */
class HistoryActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "历史报告"
        supportFragmentManager.beginTransaction()
            .replace(R.id.history_container, HistoryFragment())
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
