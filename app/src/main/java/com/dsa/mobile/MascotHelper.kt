package com.dsa.mobile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

/**
 * v2.12.11 吉祥物通用挂件：
 * - 透明背景，贴容器右下角最底部；
 * - 待机静止不动，仅点击时弹跳一次并播放 bui 音效；
 * - 长按可单指拖拽跟随移动；双指捏合可放大缩小（比例持久化）。
 *
 * 同时挂在多智能体分析页（始终显示）与主界面（外观设置开启全局显示后）。
 */
object MascotHelper {

    private var soundPool: SoundPool? = null
    private var buiSoundId = 0

    private const val MIN_SCALE = 0.6f
    private const val MAX_SCALE = 1.8f

    private fun ensureSound(ctx: android.content.Context) {
        if (soundPool != null) return
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        try {
            val fd = ctx.assets.openFd("sound/bui.wav")
            buiSoundId = soundPool!!.load(fd, 1)
            fd.close()
        } catch (e: Exception) {
            buiSoundId = 0
        }
    }

    private fun playBui(ctx: android.content.Context) {
        ensureSound(ctx)
        if (buiSoundId != 0) soundPool?.play(buiSoundId, 1f, 1f, 1, 0, 1f)
    }

    /**
     * 在容器右下角挂一个可交互宠物。返回创建的 ImageView。
     * @param sizeDp 宠物显示宽度（dp）
     * @param bottomMarginDp 距容器底部的边距（dp），主页需避开底部 dock
     */
    fun attach(activity: Activity, container: ViewGroup, sizeDp: Int = 76, bottomMarginDp: Int = 8): ImageView {
        ensureSound(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val mascot = ImageView(activity).apply {
            setImageResource(R.drawable.mascot)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "吉祥物"
            isClickable = true
            isFocusable = true
        }

        val lp = FrameLayout.LayoutParams(dp(sizeDp), FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.END or Gravity.BOTTOM
        lp.rightMargin = dp(12)
        lp.bottomMargin = dp(bottomMarginDp)
        container.addView(mascot, lp)

        val baseScale = Prefs.mascotScale(activity)
        mascot.scaleX = baseScale
        mascot.scaleY = baseScale

        fun clampTranslation() {
            val w = mascot.width.coerceAtLeast(1)
            val h = mascot.height.coerceAtLeast(1)
            val cw = container.width.coerceAtLeast(w)
            val ch = container.height.coerceAtLeast(h)
            mascot.translationX = mascot.translationX.coerceIn(-(cw - w).toFloat(), 0f)
            mascot.translationY = mascot.translationY.coerceIn(-(ch - h).toFloat(), 0f)
        }

        // 恢复上次拖动位置（相对右下角默认位的 translation）；布局完成后再夹取到容器内
        val (savedX, savedY) = Prefs.mascotPos(activity)
        mascot.translationX = savedX
        mascot.translationY = savedY
        mascot.post { clampTranslation() }

        // ---- 手势状态 ----
        var currentScale = baseScale
        var dragging = false
        var pinching = false
        var downX = 0f
        var downY = 0f
        var originTx = 0f
        var originTy = 0f
        var movedBeyondSlop = false
        val touchSlop = dp(8).toFloat()

        val scaleDetector = ScaleGestureDetector(activity,
            object : ScaleGestureDetector.OnScaleGestureListener {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentScale = (currentScale * detector.scaleFactor)
                        .coerceIn(MIN_SCALE, MAX_SCALE)
                    mascot.scaleX = currentScale
                    mascot.scaleY = currentScale
                    Prefs.saveMascotScale(activity, currentScale)
                    return true
                }

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    pinching = true
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    pinching = false
                }
            })

        mascot.setOnLongClickListener {
            dragging = true
            true
        }

        mascot.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    originTx = mascot.translationX
                    originTy = mascot.translationY
                    movedBeyondSlop = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pinching = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pinching) return@setOnTouchListener false
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!movedBeyondSlop && abs(dx) < touchSlop && abs(dy) < touchSlop) {
                        return@setOnTouchListener false
                    }
                    movedBeyondSlop = true
                    if (dragging) {
                        mascot.translationX = originTx + dx
                        mascot.translationY = originTy + dy
                        clampTranslation()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    pinching = false
                    // 拖动结束后持久化当前位置，下次进入沿用
                    Prefs.saveMascotPos(activity, mascot.translationX, mascot.translationY)
                }
            }
            false
        }

        // 点击：弹跳一次，不进入循环动画
        mascot.setOnClickListener { bounce(mascot, activity, currentScale) }

        return mascot
    }

    /** 三段 Q 弹：按压 → 过冲弹起 → 回落复位到当前缩放档位；保留拖动后的平移位置 */
    private fun bounce(m: ImageView, ctx: android.content.Context, base: Float) {
        m.animate().cancel()
        m.clearAnimation()
        m.scaleX = base
        m.scaleY = base
        // 记录弹跳起始的纵向平移，动画结束回到该位置，而不是清零回右下角
        val startTy = m.translationY
        val lift = -m.resources.displayMetrics.density * 10f

        val down = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(m, "scaleX", base, base * 0.88f),
                ObjectAnimator.ofFloat(m, "scaleY", base, base * 0.88f)
            )
            duration = 80
        }
        val up = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(m, "scaleX", base * 0.88f, base * 1.18f),
                ObjectAnimator.ofFloat(m, "scaleY", base * 0.88f, base * 1.18f),
                ObjectAnimator.ofFloat(m, "translationY", startTy, startTy + lift)
            )
            duration = 200
            interpolator = android.view.animation.OvershootInterpolator(1.6f)
        }
        val back = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(m, "scaleX", base * 1.18f, base),
                ObjectAnimator.ofFloat(m, "scaleY", base * 1.18f, base),
                ObjectAnimator.ofFloat(m, "translationY", startTy + lift, startTy)
            )
            duration = 140
        }
        AnimatorSet().apply {
            playSequentially(down, up, back)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    m.scaleX = base
                    m.scaleY = base
                    m.translationY = startTy
                }
            })
            start()
        }
        playBui(ctx)
    }

    /** 退出页面时释放音效资源（全局仅一处持有） */
    fun release() {
        soundPool?.release()
        soundPool = null
        buiSoundId = 0
    }
}
