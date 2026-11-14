package com.dsa.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.dsa.mobile.Indicators
import com.dsa.mobile.KLineBar
import com.dsa.mobile.ThemeManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * K线图（同花顺风格）：主图指标 MA/EMA/BOLL/BBI + 附图指标 VOL/MACD/KDJ/RSI 可切换；
 * 十字光标跟随，显示该根 K 线的日期与 OHLC；支持横向拖动浏览历史
 */
class KLineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class MainIndex(val label: String) {
        MA("MA"), EMA("EMA"), BOLL("BOLL"), BBI("BBI")
    }

    enum class SubIndex(val label: String) {
        VOL("VOL"), MACD("MACD"), KDJ("KDJ"), RSI("RSI"), NONE("无")
    }

    /** 图表形态：K线 / 分时折线 */
    enum class Mode { CANDLE, MINUTE }

    /** 分时模式下均价线是否绘制（同花顺分时有均价线） */
    private val minuteLineColor = Color.rgb(30, 136, 229)
    private val avgLineColor = Color.rgb(255, 179, 0)

    private val upColor = Color.rgb(229, 57, 53)
    private val downColor = Color.rgb(67, 160, 71)
    private var gridColor = Color.rgb(220, 220, 220)
    private var textColor = Color.rgb(80, 80, 80)
    private val ma5Color = Color.rgb(255, 179, 0)
    private val ma10Color = Color.rgb(30, 136, 229)
    private val ma20Color = Color.rgb(142, 36, 170)
    private val difColor = Color.rgb(255, 179, 0)
    private val deaColor = Color.rgb(30, 136, 229)
    private val dashColor = Color.rgb(150, 150, 150)
    private val bollUpColor = Color.rgb(255, 152, 0)
    private val bollMidColor = Color.rgb(30, 136, 229)
    private val bollLowColor = Color.rgb(0, 150, 136)
    private val bollFillColor = Color.argb(28, 30, 136, 229)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val candlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 26f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 24f
    }
    /** 底部日期/时间轴标签（加大 + 白底，防止被 K 线内容遮挡） */
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(51, 51, 51)
        textSize = 22f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }
    private val dateBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val priceLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 22f
        textAlign = Paint.Align.LEFT
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 255, 255, 255)
    }
    private val floatBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(250, 255, 255, 255)
    }
    private val floatTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 28f
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    /**
     * v2.12.4：K 线图深色适配。
     * 线路与买卖色保持不变，只把网格、坐标文字、日期轴/浮动提示的底色
     * 从浅色系切到深色系，避免深色卡片上出现白色小块。
     */
    init {
        if (ThemeManager.isDark(context)) {
            textColor = Color.rgb(198, 198, 206)
            gridColor = Color.rgb(46, 46, 54)
            textPaint.color = textColor
            labelPaint.color = textColor
            priceLabelPaint.color = textColor
            floatTextPaint.color = textColor
            datePaint.color = Color.rgb(198, 198, 206)
            dateBgPaint.color = Color.argb(228, 14, 14, 19)
            tagPaint.color = Color.argb(228, 14, 14, 19)
            floatBgPaint.color = Color.argb(240, 12, 12, 17)
        }
    }

    private var bars: List<KLineBar> = emptyList()

    /** 主图 / 附图指标 */
    var mainIndex: MainIndex = MainIndex.MA
    var subIndex: SubIndex = SubIndex.VOL

    /** 图表形态（K线/分时）；量柱开关（关闭时 VOL 附图不显示） */
    var mode: Mode = Mode.CANDLE
    var showVolume: Boolean = true

    /** 预计算的指标数组 */
    private var ma5: DoubleArray = DoubleArray(0)
    private var ma10: DoubleArray = DoubleArray(0)
    private var ma20: DoubleArray = DoubleArray(0)
    private var ema5: DoubleArray = DoubleArray(0)
    private var ema10: DoubleArray = DoubleArray(0)
    private var ema20: DoubleArray = DoubleArray(0)
    private var bollMid: DoubleArray = DoubleArray(0)
    private var bollUp: DoubleArray = DoubleArray(0)
    private var bollLow: DoubleArray = DoubleArray(0)
    private var bbiArr: DoubleArray = DoubleArray(0)
    private var volMa5: DoubleArray = DoubleArray(0)
    private var volMa10: DoubleArray = DoubleArray(0)
    private var macdDif: DoubleArray = DoubleArray(0)
    private var macdDea: DoubleArray = DoubleArray(0)
    private var macdBar: DoubleArray = DoubleArray(0)
    private var kdjK: DoubleArray = DoubleArray(0)
    private var kdjD: DoubleArray = DoubleArray(0)
    private var kdjJ: DoubleArray = DoubleArray(0)
    private var rsi6: DoubleArray = DoubleArray(0)
    private var rsi12: DoubleArray = DoubleArray(0)
    private var rsi24: DoubleArray = DoubleArray(0)

    /** 当前可视窗口 [start, end) */
    private var start = 0
    private var end = 0
    private var lastTouchX = 0f
    private var cursorIndex = -1

    /** 设置主图/附图指标 */
    fun setIndices(main: MainIndex, sub: SubIndex) {
        if (mainIndex == main && subIndex == sub) return
        mainIndex = main
        subIndex = sub
        invalidate()
    }

    /** 设置图表形态（K线/分时） */
    fun setChartMode(m: Mode) {
        if (mode == m) return
        mode = m
        if (m == Mode.MINUTE && bars.isNotEmpty()) {
            start = 0
            end = bars.size
            cursorIndex = end - 1
        }
        invalidate()
    }

    /** 设置量柱开关 */
    fun setVolumeVisible(on: Boolean) {
        if (showVolume == on) return
        showVolume = on
        invalidate()
    }

    fun setData(data: List<KLineBar>) {
        bars = data
        if (data.isEmpty()) {
            resetArrays()
            start = 0; end = 0; cursorIndex = -1
            invalidate()
            return
        }
        val n = data.size
        val closes = DoubleArray(n) { data[it].close }
        val highs = DoubleArray(n) { data[it].high }
        val lows = DoubleArray(n) { data[it].low }
        val vols = DoubleArray(n) { data[it].volume.toDouble() }
        ma5 = Indicators.sma(closes, 5)
        ma10 = Indicators.sma(closes, 10)
        ma20 = Indicators.sma(closes, 20)
        ema5 = Indicators.ema(closes, 5)
        ema10 = Indicators.ema(closes, 10)
        ema20 = Indicators.ema(closes, 20)
        val boll = Indicators.boll(closes, 20, 2.0)
        bollMid = boll.first; bollUp = boll.second; bollLow = boll.third
        bbiArr = Indicators.bbi(closes)
        volMa5 = Indicators.sma(vols, 5)
        volMa10 = Indicators.sma(vols, 10)
        val macd = Indicators.macd(closes)
        macdDif = macd.first; macdDea = macd.second; macdBar = macd.third
        val kdj = Indicators.kdj(highs, lows, closes)
        kdjK = kdj.first; kdjD = kdj.second; kdjJ = kdj.third
        rsi6 = Indicators.rsi(closes, 6)
        rsi12 = Indicators.rsi(closes, 12)
        rsi24 = Indicators.rsi(closes, 24)
        if (mode == Mode.MINUTE) {
            start = 0
            end = data.size
        } else {
            end = data.size
            start = max(0, data.size - VISIBLE_COUNT)
        }
        cursorIndex = end - 1
        invalidate()
    }

    private fun resetArrays() {
        ma5 = DoubleArray(0); ma10 = DoubleArray(0); ma20 = DoubleArray(0)
        ema5 = DoubleArray(0); ema10 = DoubleArray(0); ema20 = DoubleArray(0)
        bollMid = DoubleArray(0); bollUp = DoubleArray(0); bollLow = DoubleArray(0)
        bbiArr = DoubleArray(0)
        volMa5 = DoubleArray(0); volMa10 = DoubleArray(0)
        macdDif = DoubleArray(0); macdDea = DoubleArray(0); macdBar = DoubleArray(0)
        kdjK = DoubleArray(0); kdjD = DoubleArray(0); kdjJ = DoubleArray(0)
        rsi6 = DoubleArray(0); rsi12 = DoubleArray(0); rsi24 = DoubleArray(0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                updateCursor(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (bars.isEmpty() || end <= start) return true
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                if (abs(dx) > 2f) {
                    val chartW = width - LEFT_PAD - RIGHT_PAD
                    val barW = chartW.toFloat() / (end - start)
                    val shift = (dx / barW).toInt()
                    if (shift != 0) {
                        val maxStart = max(0, bars.size - MIN_VISIBLE)
                        var ns = start - shift
                        ns = ns.coerceIn(0, maxStart)
                        if (ns != start) {
                            start = ns
                            end = min(bars.size, start + VISIBLE_COUNT)
                        }
                    }
                }
                updateCursor(event.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> return true
        }
        return super.onTouchEvent(event)
    }

    private fun updateCursor(touchX: Float) {
        if (bars.isEmpty() || end <= start) return
        val chartW = width - LEFT_PAD - RIGHT_PAD
        if (chartW <= 0) return
        val barW = chartW.toFloat() / (end - start)
        val rel = (touchX - LEFT_PAD) / barW
        cursorIndex = (start + rel.toInt()).coerceIn(start, end - 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty() || end <= start) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无数据", width / 2f, height / 2f, textPaint)
            return
        }

        if (mode == Mode.MINUTE) {
            drawMinute(canvas)
            return
        }

        val chartW = width - LEFT_PAD - RIGHT_PAD
        val chartH = height - TOP_PAD - BOTTOM_PAD
        val effSub = if (subIndex == SubIndex.VOL && !showVolume) SubIndex.NONE else subIndex
        val hasSub = effSub != SubIndex.NONE
        val subH = if (hasSub) (chartH * 0.30).toInt() else 0
        val kH = chartH - subH - (if (hasSub) GAP else 0)
        val count = end - start
        val barW = chartW.toFloat() / count
        val bodyW = max(1f, barW * 0.62f)

        // ---- 主图价格范围（蜡烛 + 主图指标，NaN 安全）----
        var lo = Double.MAX_VALUE
        var hi = Double.MIN_VALUE
        for (i in start until end) {
            val b = bars[i]
            lo = safeMin(lo, b.low)
            hi = safeMax(hi, b.high)
        }
        for (i in start until end) {
            when (mainIndex) {
                MainIndex.MA -> {
                    lo = safeMin(lo, safeMin(ma5[i], safeMin(ma10[i], ma20[i])))
                    hi = safeMax(hi, safeMax(ma5[i], safeMax(ma10[i], ma20[i])))
                }
                MainIndex.EMA -> {
                    lo = safeMin(lo, safeMin(ema5[i], safeMin(ema10[i], ema20[i])))
                    hi = safeMax(hi, safeMax(ema5[i], safeMax(ema10[i], ema20[i])))
                }
                MainIndex.BOLL -> {
                    lo = safeMin(lo, safeMin(bollMid[i], bollLow[i]))
                    hi = safeMax(hi, safeMax(bollMid[i], bollUp[i]))
                }
                MainIndex.BBI -> {
                    lo = safeMin(lo, bbiArr[i]); hi = safeMax(hi, bbiArr[i])
                }
            }
        }
        if (lo >= hi) { lo = hi - 1; hi = lo + 2 }
        val padRange = (hi - lo) * 0.06
        lo -= padRange; hi += padRange
        if (lo < 0) lo = 0.0

        fun y(price: Double): Float =
            TOP_PAD + ((hi - price) / (hi - lo) * kH).toFloat()

        // ---- 附图值域 ----
        var subLo = 0.0
        var subHi = 1.0
        when (effSub) {
            SubIndex.VOL -> {
                var mv = 0.0
                for (i in start until end) mv = max(mv, bars[i].volume.toDouble())
                subLo = 0.0; subHi = mv * 1.05
            }
            SubIndex.MACD -> {
                for (i in start until end) {
                    subLo = min(subLo, min(macdBar[i], min(macdDif[i], macdDea[i])))
                    subHi = max(subHi, max(macdBar[i], max(macdDif[i], macdDea[i])))
                }
                val pad2 = (subHi - subLo) * 0.08
                subLo -= pad2; subHi += pad2
            }
            SubIndex.KDJ -> { subLo = 0.0; subHi = 100.0 }
            SubIndex.RSI -> { subLo = 0.0; subHi = 100.0 }
            SubIndex.NONE -> {}
        }
        fun subY(v: Double): Float =
            if (subH > 0) TOP_PAD + kH + GAP + ((subHi - v) / (subHi - subLo) * subH).toFloat()
            else TOP_PAD.toFloat()

        // ---- 主图网格 + 价格轴 ----
        linePaint.color = gridColor
        linePaint.strokeWidth = 1f
        for (g in 0..4) {
            val gy = TOP_PAD + kH * g / 4f
            canvas.drawLine(LEFT_PAD.toFloat(), gy, (width - RIGHT_PAD).toFloat(), gy, linePaint)
            drawPriceTag(canvas, String.format("%.2f", hi - (hi - lo) * g / 4.0), gy, textColor)
        }

        // ---- 主图蜡烛 ----
        for (i in start until end) {
            val b = bars[i]
            val x = LEFT_PAD + (i - start) * barW + barW / 2
            val isUp = b.close >= b.open
            val c = if (isUp) upColor else downColor
            candlePaint.color = c
            linePaint.color = c
            canvas.drawLine(x, y(b.high), x, y(b.low), linePaint)
            val yo = y(b.open)
            val yc = y(b.close)
            val top = min(yo, yc)
            val bottom = max(yo, yc)
            canvas.drawRect(x - bodyW / 2, top, x + bodyW / 2, max(1f, bottom - top) + top, candlePaint)
        }

        // ---- 主图指标 ----
        when (mainIndex) {
            MainIndex.MA -> {
                drawLine(canvas, ma5, ma5Color, barW) { p -> y(p) }
                drawLine(canvas, ma10, ma10Color, barW) { p -> y(p) }
                drawLine(canvas, ma20, ma20Color, barW) { p -> y(p) }
            }
            MainIndex.EMA -> {
                drawLine(canvas, ema5, ma5Color, barW) { p -> y(p) }
                drawLine(canvas, ema10, ma10Color, barW) { p -> y(p) }
                drawLine(canvas, ema20, ma20Color, barW) { p -> y(p) }
            }
            MainIndex.BOLL -> {
                // 上下轨之间半透明带状填充
                for (i in start until end) {
                    if (bollUp[i].isNaN()) continue
                    val x = LEFT_PAD + (i - start) * barW + barW / 2
                    candlePaint.color = bollFillColor
                    canvas.drawRect(
                        RectF(x - barW / 2, y(bollUp[i]), x + barW / 2, y(bollLow[i])),
                        candlePaint
                    )
                }
                drawLine(canvas, bollUp, bollUpColor, barW) { p -> y(p) }
                drawLine(canvas, bollMid, bollMidColor, barW) { p -> y(p) }
                drawLine(canvas, bollLow, bollLowColor, barW) { p -> y(p) }
            }
            MainIndex.BBI -> drawLine(canvas, bbiArr, Color.rgb(233, 30, 99), barW) { p -> y(p) }
        }

        // ---- 附图 ----
        if (hasSub) {
            val sepY = TOP_PAD + kH + GAP
            linePaint.color = gridColor
            canvas.drawLine(LEFT_PAD.toFloat(), sepY.toFloat(), (width - RIGHT_PAD).toFloat(), sepY.toFloat(), linePaint)
            // 附图标题
            labelPaint.color = textColor
            labelPaint.textAlign = Paint.Align.LEFT
            when (effSub) {
                SubIndex.VOL -> {
                    canvas.drawText("VOL", LEFT_PAD + 4f, sepY + 28f, labelPaint)
                    for (i in start until end) {
                        val b = bars[i]
                        val x = LEFT_PAD + (i - start) * barW + barW / 2
                        val isUp = b.close >= b.open
                        candlePaint.color = if (isUp) upColor else downColor
                        canvas.drawRect(
                            x - bodyW / 2, subY(b.volume.toDouble()), x + bodyW / 2,
                            (sepY + subH).toFloat(), candlePaint
                        )
                    }
                    drawLine(canvas, volMa5, ma5Color, barW) { v -> subY(v) }
                    drawLine(canvas, volMa10, ma10Color, barW) { v -> subY(v) }
                }
                SubIndex.MACD -> {
                    canvas.drawText("MACD(12,26,9)", LEFT_PAD + 4f, sepY + 28f, labelPaint)
                    for (i in start until end) {
                        val x = LEFT_PAD + (i - start) * barW + barW / 2
                        val v = macdBar[i]
                        candlePaint.color = if (v >= 0) upColor else downColor
                        val yTop = subY(v)
                        val yZero = subY(0.0)
                        canvas.drawRect(
                            x - bodyW / 2, min(yTop, yZero), x + bodyW / 2,
                            max(yTop, yZero), candlePaint
                        )
                    }
                    drawLine(canvas, macdDif, difColor, barW) { v -> subY(v) }
                    drawLine(canvas, macdDea, deaColor, barW) { v -> subY(v) }
                }
                SubIndex.KDJ -> {
                    canvas.drawText("KDJ(9,3,3)", LEFT_PAD + 4f, sepY + 28f, labelPaint)
                    linePaint.color = gridColor
                    for (g in 0..2) {
                        val gy = sepY + subH * g / 2f
                        canvas.drawLine(LEFT_PAD.toFloat(), gy, (width - RIGHT_PAD).toFloat(), gy, linePaint)
                    }
                    drawLine(canvas, kdjK, Color.rgb(233, 30, 99), barW) { v -> subY(v) }
                    drawLine(canvas, kdjD, Color.rgb(30, 136, 229), barW) { v -> subY(v) }
                    drawLine(canvas, kdjJ, Color.rgb(255, 179, 0), barW) { v -> subY(v) }
                }
                SubIndex.RSI -> {
                    canvas.drawText("RSI(6,12,24)", LEFT_PAD + 4f, sepY + 28f, labelPaint)
                    linePaint.color = gridColor
                    for (g in 0..2) {
                        val gy = sepY + subH * g / 2f
                        canvas.drawLine(LEFT_PAD.toFloat(), gy, (width - RIGHT_PAD).toFloat(), gy, linePaint)
                    }
                    drawLine(canvas, rsi6, Color.rgb(255, 179, 0), barW) { v -> subY(v) }
                    drawLine(canvas, rsi12, Color.rgb(30, 136, 229), barW) { v -> subY(v) }
                    drawLine(canvas, rsi24, Color.rgb(142, 36, 170), barW) { v -> subY(v) }
                }
                SubIndex.NONE -> {}
            }
        }

        // ---- 日期轴（只显示日，白底标签，画在底部留白区内）----
        drawDateAxis(canvas, count, barW, maxLabels = 10, labelAt = { i -> bars[i].date.substring(8, 10) })

        // ---- 最新价虚线（带背景标签）----
        val last = bars[end - 1]
        linePaint.color = dashColor
        linePaint.strokeWidth = 1.5f
        val ly = y(last.close)
        canvas.drawLine(LEFT_PAD.toFloat(), ly, (width - RIGHT_PAD).toFloat(), ly, linePaint)
        drawPriceTag(
            canvas, String.format("%.2f", last.close), ly - 7f,
            if (last.close >= last.open) upColor else downColor
        )

        // ---- 顶部图例（按主图指标）----
        drawLegend(canvas, last)

        // ---- 十字光标 ----
        if (cursorIndex in start until end) {
            drawCursor(canvas, cursorIndex, barW, { p -> y(p) }, { v -> subY(v) }, kH, subH, effSub)
        }
    }

    /** 分时模式：价格折线 + 均价线（同花顺风格） */
    private fun drawMinute(canvas: Canvas) {
        val chartW = width - LEFT_PAD - RIGHT_PAD
        val chartH = height - TOP_PAD - BOTTOM_PAD
        val count = end - start
        val step = chartW.toFloat() / count

        var lo = Double.MAX_VALUE
        var hi = Double.MIN_VALUE
        for (i in start until end) {
            lo = min(lo, bars[i].low)
            hi = max(hi, bars[i].high)
        }
        if (lo >= hi) { lo = hi - 1; hi = lo + 2 }
        val pad = (hi - lo) * 0.05
        lo -= pad; hi += pad
        if (lo < 0) lo = 0.0
        fun y(v: Double): Float = TOP_PAD + ((hi - v) / (hi - lo) * chartH).toFloat()

        // 网格 + 价格轴
        linePaint.color = gridColor
        linePaint.strokeWidth = 1f
        for (g in 0..4) {
            val gy = TOP_PAD + chartH * g / 4f
            canvas.drawLine(LEFT_PAD.toFloat(), gy, (width - RIGHT_PAD).toFloat(), gy, linePaint)
            drawPriceTag(canvas, String.format("%.2f", hi - (hi - lo) * g / 4.0), gy, textColor)
        }

        // 均价线
        var sum = 0.0
        val avg = DoubleArray(bars.size)
        for (i in bars.indices) { sum += bars[i].close; avg[i] = sum / (i + 1) }

        // 价格折线 + 均价线
        val prices = DoubleArray(bars.size) { bars[it].close }
        drawLine(canvas, prices, minuteLineColor, step) { v -> y(v) }
        drawLine(canvas, avg, avgLineColor, step) { v -> y(v) }

        // 最新价虚线
        val last = bars[end - 1]
        linePaint.color = dashColor
        linePaint.strokeWidth = 1.5f
        val ly = y(last.close)
        canvas.drawLine(LEFT_PAD.toFloat(), ly, (width - RIGHT_PAD).toFloat(), ly, linePaint)
        drawPriceTag(canvas, String.format("%.2f", last.close), ly - 7f, minuteLineColor)

        // 时间轴（分时显示 HHMM 时间；5日线等日期型显示日，画在底部留白区内）
        drawDateAxis(canvas, count, step, maxLabels = 5, labelAt = { i ->
            val d = bars[i].date
            if (d.length >= 10) d.substring(8, 10) else d
        })

        // 顶部图例：价格 / 均价
        val cy = TOP_PAD - 16f
        labelPaint.textAlign = Paint.Align.LEFT
        linePaint.color = minuteLineColor
        linePaint.strokeWidth = 7f
        canvas.drawLine(LEFT_PAD.toFloat(), cy, LEFT_PAD + 16f, cy, linePaint)
        labelPaint.color = textColor
        canvas.drawText("价格 ${String.format("%.2f", last.close)}", LEFT_PAD + 20f, cy + 7f, labelPaint)
        val ax = LEFT_PAD + 20f + labelPaint.measureText("价格 ${String.format("%.2f", last.close)}") + 14f
        linePaint.color = avgLineColor
        canvas.drawLine(ax, cy, ax + 16f, cy, linePaint)
        canvas.drawText("均价 ${String.format("%.2f", avg[end - 1])}", ax + 20f, cy + 7f, labelPaint)

        // 十字光标
        if (cursorIndex in start until end) drawMinuteCursor(canvas, cursorIndex, step) { v -> y(v) }
    }

    /** 分时十字光标浮动框：时间 + 价格 */
    private fun drawMinuteCursor(canvas: Canvas, idx: Int, step: Float, y: (Double) -> Float) {
        val b = bars[idx]
        val cx = LEFT_PAD + (idx - start) * step + step / 2
        val cy = y(b.close)
        linePaint.color = dashColor
        linePaint.strokeWidth = 1f
        canvas.drawLine(cx, TOP_PAD.toFloat(), cx, (height - BOTTOM_PAD).toFloat(), linePaint)
        canvas.drawLine(LEFT_PAD.toFloat(), cy, (width - RIGHT_PAD).toFloat(), cy, linePaint)

        val lines = listOf(b.date, "价格 " + String.format("%.2f", b.close))
        var maxW = 0f
        for (s in lines) maxW = max(maxW, floatTextPaint.measureText(s))
        val boxW = maxW + 20f
        val lineH = 34f
        val boxH = lines.size * lineH + 12f
        val boxL = if (cx < width / 2f) width - RIGHT_PAD - boxW else LEFT_PAD.toFloat()
        val boxT = if (cy > height / 2f) cy - boxH - 12f else cy + 12f
        canvas.drawRoundRect(RectF(boxL, boxT, boxL + boxW, boxT + boxH), 8f, 8f, floatBgPaint)
        linePaint.color = Color.rgb(190, 190, 190)
        linePaint.strokeWidth = 1f
        canvas.drawRoundRect(RectF(boxL, boxT, boxL + boxW, boxT + boxH), 8f, 8f, linePaint)
        var ty = boxT + 20f
        floatTextPaint.color = textColor
        canvas.drawText(lines[0], boxL + 10f, ty, floatTextPaint)
        ty += lineH
        floatTextPaint.color = minuteLineColor
        canvas.drawText(lines[1], boxL + 10f, ty, floatTextPaint)
    }

    /** 底部日期/时间轴：白底标签，数量随可见根数自适应，画在底部留白区内 */
    private fun drawDateAxis(canvas: Canvas, count: Int, barW: Float, labelAt: (Int) -> String, maxLabels: Int = 10) {
        if (count <= 0) return
        val dateY = (height - BOTTOM_PAD / 2 + 6).toFloat()
        val labelCount = when {
            count <= 4 -> count
            count <= 30 -> minOf(6, maxLabels)
            count <= 120 -> minOf(8, maxLabels)
            else -> minOf(10, maxLabels)
        }
        val idxs = IntArray(labelCount)
        for (k in 0 until labelCount) {
            idxs[k] = if (labelCount == count) k
            else start + (count - 1) * k / (labelCount - 1)
        }
        var prevRight = -1000f
        for (k in 0 until labelCount) {
            val i = idxs[k]
            val x = LEFT_PAD + (i - start) * barW + barW / 2
            val label = labelAt(i)
            val w = datePaint.measureText(label)
            val rightMax = (width - RIGHT_PAD - 2 - w).toFloat()
            var tx = (x - w / 2).coerceAtLeast(LEFT_PAD.toFloat())
            if (tx > rightMax) tx = rightMax
            if (tx < prevRight + 10) tx = prevRight + 10
            if (tx > rightMax) continue
            prevRight = tx + w
            canvas.drawRoundRect(RectF(tx - 4, dateY - 20, tx + w + 4, dateY + 6), 4f, 4f, dateBgPaint)
            canvas.drawText(label, tx, dateY, datePaint)
        }
    }

    /** 顶部图例行（按主图指标显示对应图例） */
    private fun drawLegend(canvas: Canvas, last: KLineBar) {
        val cy = TOP_PAD - 16f
        labelPaint.textAlign = Paint.Align.LEFT
        val entries = when (mainIndex) {
            MainIndex.MA -> listOf(
                Triple("MA5", at(ma5), ma5Color),
                Triple("MA10", at(ma10), ma10Color),
                Triple("MA20", at(ma20), ma20Color)
            )
            MainIndex.EMA -> listOf(
                Triple("EMA5", at(ema5), ma5Color),
                Triple("EMA10", at(ema10), ma10Color),
                Triple("EMA20", at(ema20), ma20Color)
            )
            MainIndex.BOLL -> listOf(
                Triple("BOLL上轨", at(bollUp), bollUpColor),
                Triple("中轨", at(bollMid), bollMidColor),
                Triple("下轨", at(bollLow), bollLowColor)
            )
            MainIndex.BBI -> listOf(
                Triple("BBI", at(bbiArr), Color.rgb(233, 30, 99))
            )
        }
        var x = LEFT_PAD.toFloat()
        for ((name, v, color) in entries) {
            if (v.isNaN()) continue
            linePaint.color = color
            linePaint.strokeWidth = 7f
            canvas.drawLine(x, cy, x + 16f, cy, linePaint)
            labelPaint.color = textColor
            val txt = "$name ${String.format("%.2f", v)}"
            canvas.drawText(txt, x + 20f, cy + 7f, labelPaint)
            x += 20f + labelPaint.measureText(txt) + 14f
            if (x > width - RIGHT_PAD - 90f) break
        }
        priceLabelPaint.textAlign = Paint.Align.RIGHT
        priceLabelPaint.color = if (last.close >= last.open) upColor else downColor
        canvas.drawText(
            String.format("%.2f", last.close), (width - RIGHT_PAD - 6).toFloat(), cy + 7f, priceLabelPaint
        )
    }

    private fun at(arr: DoubleArray): Double =
        if (arr.size > end - 1) arr[end - 1] else Double.NaN

    /** NaN 安全 min/max（指标早期值为 NaN 时不污染范围） */
    private fun safeMin(a: Double, b: Double): Double =
        if (a.isNaN()) b else if (b.isNaN()) a else min(a, b)

    private fun safeMax(a: Double, b: Double): Double =
        if (a.isNaN()) b else if (b.isNaN()) a else max(a, b)

    /** 带白底的价格标签 */
    private fun drawPriceTag(canvas: Canvas, text: String, y: Float, color: Int) {
        val w = priceLabelPaint.measureText(text)
        val l = (width - RIGHT_PAD + 4).toFloat()
        val r = min(l + w + 8f, width.toFloat() - 2f)
        canvas.drawRoundRect(RectF(l, y - 2f, r, y + 16f), 4f, 4f, tagPaint)
        priceLabelPaint.color = color
        canvas.drawText(text, l + 4f, y + 12f, priceLabelPaint)
    }

    /** 十字光标：竖线贯穿主图与附图，浮动框显示日期 OHLC + 附图数值 */
    private fun drawCursor(
        canvas: Canvas, idx: Int, barW: Float,
        y: (Double) -> Float, subY: (Double) -> Float, kH: Int, subH: Int,
        effSub: SubIndex
    ) {
        val b = bars[idx]
        val cx = LEFT_PAD + (idx - start) * barW + barW / 2
        val cy = y(b.close)
        val bottomY = TOP_PAD + kH + GAP + subH
        linePaint.color = dashColor
        linePaint.strokeWidth = 1f
        canvas.drawLine(cx, TOP_PAD.toFloat(), cx, bottomY.toFloat(), linePaint)
        canvas.drawLine(LEFT_PAD.toFloat(), cy, (width - RIGHT_PAD).toFloat(), cy, linePaint)

        // 浮动内容
        val lines = mutableListOf<String>()
        lines.add(b.date)
        lines.add("开 " + String.format("%.2f", b.open))
        lines.add("高 " + String.format("%.2f", b.high))
        lines.add("低 " + String.format("%.2f", b.low))
        lines.add("收 " + String.format("%.2f", b.close))
        when (effSub) {
            SubIndex.VOL -> lines.add("量 " + formatVol(b.volume))
            SubIndex.MACD -> {
                lines.add("DIF " + String.format("%.3f", at(macdDif)))
                lines.add("DEA " + String.format("%.3f", at(macdDea)))
            }
            SubIndex.KDJ -> {
                lines.add("K " + String.format("%.1f", at(kdjK)))
                lines.add("D " + String.format("%.1f", at(kdjD)))
            }
            SubIndex.RSI -> {
                lines.add("RSI6 " + String.format("%.1f", at(rsi6)))
                lines.add("RSI12 " + String.format("%.1f", at(rsi12)))
            }
            SubIndex.NONE -> {}
        }

        var maxW = 0f
        for (s in lines) maxW = max(maxW, floatTextPaint.measureText(s))
        val boxW = maxW + 20f
        val lineH = 34f
        val boxH = lines.size * lineH + 12f
        val boxL = if (cx < width / 2f) width - RIGHT_PAD - boxW else LEFT_PAD.toFloat()
        val boxT = if (cy > height / 2f) cy - boxH - 12f else cy + 12f
        val boxR = boxL + boxW
        val boxB = boxT + boxH
        canvas.drawRoundRect(RectF(boxL, boxT, boxR, boxB), 8f, 8f, floatBgPaint)
        linePaint.color = Color.rgb(190, 190, 190)
        linePaint.strokeWidth = 1f
        canvas.drawRoundRect(RectF(boxL, boxT, boxR, boxB), 8f, 8f, linePaint)

        var ty = boxT + 20f
        floatTextPaint.color = textColor
        canvas.drawText(lines[0], boxL + 10f, ty, floatTextPaint)
        ty += lineH
        val up = b.close >= b.open
        floatTextPaint.color = if (up) upColor else downColor
        for (i in 1 until lines.size) {
            canvas.drawText(lines[i], boxL + 10f, ty, floatTextPaint)
            ty += lineH
        }
    }

    private fun drawLine(
        canvas: Canvas, arr: DoubleArray, color: Int, barW: Float, yMap: (Double) -> Float
    ) {
        if (arr.size < end) return
        linePaint.color = color
        linePaint.strokeWidth = 2f
        var prevValid = false
        var prevX = 0f
        var prevY = 0f
        for (i in start until end) {
            if (arr[i].isNaN()) { prevValid = false; continue }
            val x = LEFT_PAD + (i - start) * barW + barW / 2
            val y = yMap(arr[i])
            if (prevValid) canvas.drawLine(prevX, prevY, x, y, linePaint)
            prevValid = true
            prevX = x
            prevY = y
        }
    }

    private fun formatVol(v: Long): String = when {
        v >= 100_000_000 -> String.format("%.2f亿", v / 100_000_000.0)
        v >= 10_000 -> String.format("%.0f万", v / 10_000.0)
        else -> v.toString()
    }

    companion object {
        private const val VISIBLE_COUNT = 90
        private const val MIN_VISIBLE = 30
        private const val LEFT_PAD = 6
        private const val RIGHT_PAD = 66
        private const val TOP_PAD = 36
        private const val BOTTOM_PAD = 28
        private const val GAP = 14
    }
}
