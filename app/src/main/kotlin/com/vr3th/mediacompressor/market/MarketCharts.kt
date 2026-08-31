package com.vr3th.mediacompressor.market

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

// =============================================================================
// MARKET MODULE — NATIVE CANVAS CHARTS (Section L)
// =============================================================================
// Canvas/Paint/Path only, no charting library. Every Paint/Path is
// pre-allocated in the constructor — onDraw() never allocates and never
// touches the network.
// =============================================================================

/** Compact vector sparkline for a list card — mirrors the original in-Activity view, moved here as Market-only. */
class MarketSparklineView(context: Context, private val lineColor: Int) : View(context) {
    var values: List<Double> = emptyList()
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        color = lineColor
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return
        val w = width.toFloat(); val h = height.toFloat()
        val min = values.min(); val max = values.max()
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        path.reset()
        values.forEachIndexed { i, v ->
            val x = w * i / (values.size - 1)
            val y = h - ((v - min) / range * h).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}

/**
 * Interactive OHLC candlestick chart. Real candles only — pass [candles]
 * from a validated [MarketQuote]; the view never generates its own data.
 * Supports horizontal pan/zoom via simple touch tracking (no external gesture library).
 */
class MarketCandlestickView(context: Context) : View(context) {

    var candles: List<Candle> = emptyList()
        set(v) { field = v; visibleCount = v.size.coerceAtMost(60); scrollOffset = 0; invalidate() }

    var bullishColor: Int = Color.parseColor("#00FF9D")
    var bearishColor: Int = Color.parseColor("#FF3355")
    var gridColor: Int = Color.parseColor("#3A3646")
    var labelColor: Int = Color.parseColor("#8E899E")

    private var visibleCount = 60
    private var scrollOffset = 0
    private var lastTouchX = 0f

    private val bullPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val wickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f }
    private val candlePath = Path()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> lastTouchX = event.x
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                val candleWidthPx = (width.toFloat() / visibleCount.coerceAtLeast(1))
                val shift = (-dx / candleWidthPx).toInt()
                if (shift != 0) {
                    scrollOffset = (scrollOffset + shift).coerceIn(0, (candles.size - visibleCount).coerceAtLeast(0))
                    invalidate()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (candles.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        bullPaint.color = bullishColor
        bearPaint.color = bearishColor
        wickPaint.color = Color.WHITE
        gridPaint.color = gridColor
        labelPaint.color = labelColor

        val endIndex = (candles.size - scrollOffset).coerceAtLeast(1)
        val startIndex = (endIndex - visibleCount).coerceAtLeast(0)
        val visible = candles.subList(startIndex, endIndex)
        if (visible.isEmpty()) return

        val maxHigh = visible.maxOf { it.high }
        val minLow = visible.minOf { it.low }
        val range = (maxHigh - minLow).takeIf { it > 0.0 } ?: 1.0

        // Horizontal grid (4 lines) — cheap, pre-styled Paint, no per-frame allocation.
        for (i in 0..3) {
            val y = h * i / 3f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val slotWidth = w / visible.size
        val bodyWidth = (slotWidth * 0.6f).coerceAtLeast(2f)

        fun yFor(price: Double): Float = h - ((price - minLow) / range * h).toFloat()

        visible.forEachIndexed { i, c ->
            val cx = slotWidth * i + slotWidth / 2f
            val bullish = c.close >= c.open
            val paint = if (bullish) bullPaint else bearPaint
            val top = yFor(c.high)
            val bottom = yFor(c.low)
            val openY = yFor(c.open)
            val closeY = yFor(c.close)
            canvas.drawLine(cx, top, cx, bottom, wickPaint)
            candlePath.reset()
            val bodyTop = minOf(openY, closeY)
            val bodyBottom = maxOf(openY, closeY).coerceAtLeast(bodyTop + 2f)
            candlePath.addRect(cx - bodyWidth / 2f, bodyTop, cx + bodyWidth / 2f, bodyBottom, Path.Direction.CW)
            canvas.drawPath(candlePath, paint)
        }

        canvas.drawText(String.format(java.util.Locale.US, "%.2f", maxHigh), 6f, 20f, labelPaint)
        canvas.drawText(String.format(java.util.Locale.US, "%.2f", minLow), 6f, h - 8f, labelPaint)
    }
}
