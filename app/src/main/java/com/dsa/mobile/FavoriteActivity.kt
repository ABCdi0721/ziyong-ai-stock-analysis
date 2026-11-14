package com.dsa.mobile

import android.os.Bundle

/** v2.12.1 我的收藏容器页 */
class FavoriteActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "我的收藏"
        supportFragmentManager.beginTransaction()
            .replace(R.id.history_container, FavoriteFragment())
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
