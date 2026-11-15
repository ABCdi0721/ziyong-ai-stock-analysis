package com.dsa.mobile.glass

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2

/**
 * 底部 dock 栏宿主容器。
 *
 * 背景：dock 悬浮叠加在铺满全屏的 ViewPager2 之上。页签图标本身只响应「点击」，
 * 当手指在 dock 区域内做横向滑动时，若整组手势被 dock 子项消费，下层 pager 收不到拖拽，
 * 表现为「在底部栏左右滑没有翻页动画」。
 *
 * 这里区分两种手势：
 *  - 短按 / 未超滑动阈值：按原样分发给各页签，走原有点击切换；
 *  - 横向位移超过阈值：判定为翻页拖拽，把整组触摸事件转交给下层 [pager] 处理，
 *    使底部区域左右滑动同样可以驱动 ViewPager2 翻页。
 */
class DockBarHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 下层承载四个页签的 ViewPager2，横向拖拽转发目标 */
    var pager: ViewPager2? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var forwarded = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                forwarded = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    dragging = true
                }
                if (dragging) {
                    val target = pager ?: return super.dispatchTouchEvent(ev)
                    if (!forwarded) {
                        // 首次进入拖拽：先给 pager 补发一个 DOWN，补齐触摸状态机，再转发当前移动事件
                        forwarded = true
                        val down = MotionEvent.obtain(
                            ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN,
                            downX, downY, ev.metaState
                        )
                        target.dispatchTouchEvent(down)
                        down.recycle()
                    }
                    target.dispatchTouchEvent(ev)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (forwarded) {
                    pager?.dispatchTouchEvent(ev)
                    forwarded = false
                    dragging = false
                    return true
                }
                dragging = false
                forwarded = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
