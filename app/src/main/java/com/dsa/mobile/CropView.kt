package com.dsa.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 背景图裁切预览 View：固定屏幕宽高比裁切框，单指平移、双指缩放，图片始终覆盖裁切框。
 * 确认后按当前变换输出居中裁切的 Bitmap（center-crop，不拉伸变形）。
 */
class CropView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var src: Bitmap? = null
        private set

    /** 目标裁切宽高比（宽/高），<=0 时按 View 宽高比 */
    var targetRatio: Float = 0f

    private val matrix = Matrix()
    private var minScale = 1f
    private val cropRect = RectF()

    private val scaleDetector: ScaleGestureDetector
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val maskPaint = Paint().apply { color = 0x99000000.toInt() }
    private val framePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val ns = currentScale() * d.scaleFactor
                val clamped = ns.coerceIn(minScale, minScale * 4f)
                val factor = clamped / currentScale()
                matrix.postScale(factor, factor, d.focusX, d.focusY)
                clamp()
                invalidate()
                return true
            }

            override fun onScaleBegin(d: ScaleGestureDetector) = true
            override fun onScaleEnd(d: ScaleGestureDetector) {}
        })
    }

    fun setBitmap(bmp: Bitmap) {
        src = bmp
        matrix.reset()
        if (width > 0 && height > 0) initMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeCropRect(w, h)
        if (src != null) initMatrix()
    }

    private fun computeCropRect(w: Int, h: Int) {
        val margin = dp(18f)
        var r = targetRatio
        if (r <= 0f) r = w.toFloat() / h.toFloat()
        var cw = (w - margin * 2).toFloat()
        var ch = cw / r
        if (ch > h - margin * 2) {
            ch = (h - margin * 2).toFloat()
            cw = ch * r
        }
        val cx = w / 2f
        val cy = h / 2f
        cropRect.set(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
    }

    private fun initMatrix() {
        val bmp = src ?: return
        val scaleX = cropRect.width() / bmp.width
        val scaleY = cropRect.height() / bmp.height
        minScale = max(scaleX, scaleY) // cover：覆盖裁切框
        matrix.reset()
        matrix.postScale(minScale, minScale)
        val drawnW = bmp.width * minScale
        val drawnH = bmp.height * minScale
        matrix.postTranslate(
            cropRect.centerX() - drawnW / 2f,
            cropRect.centerY() - drawnH / 2f
        )
        clamp()
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return (v[Matrix.MSCALE_X] + v[Matrix.MSCALE_Y]) / 2f
    }

    /** 限制图片始终覆盖裁切框，不露底 */
    private fun clamp() {
        val bmp = src ?: return
        val srcRect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val mapped = RectF()
        matrix.mapRect(mapped, srcRect)
        var dx = 0f
        var dy = 0f
        if (mapped.left > cropRect.left) dx = cropRect.left - mapped.left
        else if (mapped.right < cropRect.right) dx = cropRect.right - mapped.right
        if (mapped.top > cropRect.top) dy = cropRect.top - mapped.top
        else if (mapped.bottom < cropRect.bottom) dy = cropRect.bottom - mapped.bottom
        matrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    matrix.postTranslate(dx, dy)
                    clamp()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = src ?: return
        canvas.drawBitmap(bmp, matrix, bitmapPaint)
        // 框外四条遮罩
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, maskPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, maskPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, maskPaint)
        canvas.drawRect(cropRect, framePaint)
    }

    /** 输出当前裁切框对应的原图 Bitmap（center-crop，最长边缩到 maxSide） */
    fun cropBitmap(maxSide: Int = 1280): Bitmap? {
        val bmp = src ?: return null
        val inv = Matrix()
        matrix.invert(inv)
        val pts = floatArrayOf(
            cropRect.left, cropRect.top,
            cropRect.right, cropRect.top,
            cropRect.right, cropRect.bottom,
            cropRect.left, cropRect.bottom
        )
        inv.mapPoints(pts)
        val l = min(min(pts[0], pts[2]), min(pts[4], pts[6])).toInt().coerceIn(0, bmp.width)
        val t = min(min(pts[1], pts[3]), min(pts[5], pts[7])).toInt().coerceIn(0, bmp.height)
        val r = max(max(pts[0], pts[2]), max(pts[4], pts[6])).toInt().coerceIn(0, bmp.width)
        val b = max(max(pts[1], pts[3]), max(pts[5], pts[7])).toInt().coerceIn(0, bmp.height)
        val w = (r - l).coerceAtLeast(1)
        val h = (b - t).coerceAtLeast(1)
        val out = Bitmap.createBitmap(bmp, l, t, w, h)
        val max = max(out.width, out.height)
        if (max > maxSide) {
            val s = maxSide.toFloat() / max
            return Bitmap.createScaledBitmap(
                out,
                (out.width * s).toInt().coerceAtLeast(1),
                (out.height * s).toInt().coerceAtLeast(1),
                true
            )
        }
        return out
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
