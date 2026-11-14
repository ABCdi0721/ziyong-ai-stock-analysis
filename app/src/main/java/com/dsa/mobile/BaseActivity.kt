package com.dsa.mobile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** v2.8.0：所有页面的基类——应用主题 + 自定义标题（v2.12.6 起不再叠加自定义颜色） */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        supportActionBar?.title = Prefs.appTitle(this)
    }
}
