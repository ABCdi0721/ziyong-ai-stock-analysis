package com.dsa.mobile

import android.os.Bundle
import android.widget.Toast
import com.dsa.mobile.databinding.ActivityBackupBinding
import org.json.JSONObject

/** v2.12.15：WebDAV 备份与恢复页（数据源 / 新闻检索 / AI 模型配置与基础设置） */
class BackupActivity : BaseActivity() {

    private lateinit var binding: ActivityBackupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowCustomEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = "备份与恢复"

        binding.etUrl.setText(Prefs.webdavUrl(this))
        binding.etUser.setText(Prefs.webdavUser(this))
        binding.etPass.setText(Prefs.webdavPass(this))
        binding.etFolder.setText(Prefs.webdavFolder(this))
        binding.etDevice.setText(
            Prefs.webdavDevice(this).ifBlank { android.os.Build.MODEL ?: "" }
        )

        binding.btnTest.setOnClickListener { runTest() }
        binding.btnBackup.setOnClickListener { runBackup() }
        binding.btnRestore.setOnClickListener { runRestore() }
    }

    private fun readForm(): WebDavForm {
        return WebDavForm(
            url = binding.etUrl.text.toString(),
            user = binding.etUser.text.toString(),
            pass = binding.etPass.text.toString(),
            folder = binding.etFolder.text.toString(),
            device = binding.etDevice.text.toString()
        )
    }

    private fun saveForm(form: WebDavForm) {
        Prefs.saveWebDav(this, form.url, form.user, form.pass, form.folder, form.device)
    }

    private fun setBusy(busy: Boolean, status: String) {
        binding.btnTest.isEnabled = !busy
        binding.btnBackup.isEnabled = !busy
        binding.btnRestore.isEnabled = !busy
        binding.tvStatus.text = status
    }

    private fun runTest() {
        val form = readForm()
        saveForm(form)
        setBusy(true, "正在测试连接…")
        Thread {
            val r = WebDav.test(form.url, form.user, form.pass, form.folder)
            runOnUiThread {
                setBusy(false, r.message)
                Toast.makeText(this, r.message, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun runBackup() {
        val form = readForm()
        saveForm(form)
        setBusy(true, "正在备份到云端…")
        Thread {
            val body = Prefs.exportBackup(this).toString()
            val fileName = WebDav.backupFileName()
            val r = WebDav.upload(form.url, form.user, form.pass, form.folder, fileName, body)
            runOnUiThread {
                setBusy(false, r.message)
                Toast.makeText(this, r.message, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun runRestore() {
        val form = readForm()
        saveForm(form)
        setBusy(true, "正在从云端读取备份…")
        Thread {
            val backups = WebDav.listBackups(form.url, form.user, form.pass, form.folder)
            if (backups.isEmpty()) {
                runOnUiThread { setBusy(false, "暂无云端备份，请先完成一次备份") }
                return@Thread
            }
            val latest = backups.first()
            val text = WebDav.download(form.url, form.user, form.pass, form.folder, latest)
            if (!WebDav.isValidBackup(text)) {
                runOnUiThread { setBusy(false, "备份内容暂不可用，请稍后重试") }
                return@Thread
            }
            val count = Prefs.restoreBackup(this, JSONObject(text!!))
            runOnUiThread {
                setBusy(false, "已还原 $count 项设置，部分项返回后生效")
                Toast.makeText(this, "恢复完成", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // 固定本页标题，覆盖基类按自定义标签设置的标题
        supportActionBar?.title = "备份与恢复"
    }

    private data class WebDavForm(
        val url: String, val user: String, val pass: String,
        val folder: String, val device: String
    )
}
