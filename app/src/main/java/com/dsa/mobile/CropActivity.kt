package com.dsa.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.dsa.mobile.databinding.ActivityCropBinding
import java.io.File
import java.io.FileOutputStream

/** v2.12.64：背景图裁切预览页——按屏幕比例固定裁切框，平移/缩放后输出 center-crop 图片 */
class CropActivity : BaseActivity() {

    private lateinit var binding: ActivityCropBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        @Suppress("DEPRECATION")
        val uri = intent?.getParcelableExtra(EXTRA_URI) as? Uri
        if (uri == null) {
            finish()
            return
        }

        // 裁切比例与主界面屏幕一致，铺上去不再拉伸变形
        val m = resources.displayMetrics
        binding.cropView.targetRatio = m.widthPixels.toFloat() / m.heightPixels.toFloat()

        val bmp = decodeUri(uri)
        if (bmp == null) {
            Toast.makeText(this, "图片无法读取", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.cropView.setBitmap(bmp)

        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.btnConfirm.setOnClickListener { confirm() }
    }

    private fun confirm() {
        val out = binding.cropView.cropBitmap(1280) ?: run {
            Toast.makeText(this, "裁切失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val f = File(filesDir, "theme_bg.png")
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
            setResult(RESULT_OK)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "图片保存失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val max = maxOf(bounds.outWidth, bounds.outHeight)
        while (max / sample > 1600) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        fun buildIntent(ctx: android.content.Context, uri: Uri): Intent =
            Intent(ctx, CropActivity::class.java).apply {
                putExtra(EXTRA_URI, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }
}
