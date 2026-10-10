package com.yagay.yauto.platform.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal class PieMenuView(
    context: Context,
    private val items: List<Pair<String, String>>,
    private val onSelect: (String, String) -> Unit,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE202124.toInt() }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5F6368.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13f * resources.displayMetrics.scaledDensity
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val oval = RectF(left, top, left + size, top + size)
        val sweep = 360f / items.size
        items.forEachIndexed { index, (label, _) ->
            val start = -90f + index * sweep
            canvas.drawArc(oval, start, sweep, true, fill)
            canvas.drawArc(oval, start, sweep, true, divider)
            val angle = (start + sweep / 2f) * PI / 180.0
            val radius = size * 0.31f
            val cx = width / 2f + (cos(angle) * radius).toFloat()
            val cy = height / 2f + (sin(angle) * radius).toFloat() - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(label.take(18), cx, cy, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP || items.isEmpty()) return true
        val dx = event.x - width / 2f
        val dy = event.y - height / 2f
        var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
        if (degrees < 0) degrees += 360.0
        val index = (degrees / (360.0 / items.size)).toInt().coerceIn(0, items.lastIndex)
        val (label, action) = items[index]
        onSelect(label, action)
        return true
    }
}


internal class DrawingBoardView(context: Context) : View(context) {
    private val strokes = mutableListOf<Path>()
    private var current: Path? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setBackgroundColor(0xFF202124.toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = Path().also {
                    it.moveTo(event.x, event.y)
                    strokes += it
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                current?.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.lineTo(event.x, event.y)
                current = null
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokes.forEach { canvas.drawPath(it, paint) }
    }

    fun clear() {
        strokes.clear()
        current = null
        invalidate()
    }

    fun save(file: File): Boolean = runCatching {
        if (width <= 0 || height <= 0) return@runCatching false
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        true
    }.getOrDefault(false)
}


internal class EdgeLightingView(
    context: Context,
    private val edgeColor: Int,
    private val thickness: Int,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = edgeColor
        style = Paint.Style.STROKE
        strokeWidth = thickness.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = thickness / 2f
        canvas.drawRoundRect(
            inset,
            inset,
            width - inset,
            height - inset,
            24f * resources.displayMetrics.density,
            24f * resources.displayMetrics.density,
            paint,
        )
    }
}


internal class EdgeGestureView(
    context: Context,
    private val edge: String,
    private val minDistancePx: Float,
    private val emit: (String, String) -> Unit,
) : View(context) {
    private var downX = 0f
    private var downY = 0f

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distance >= minDistancePx) {
                    val direction = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                        if (dx >= 0f) "right" else "left"
                    } else {
                        if (dy >= 0f) "down" else "up"
                    }
                    val value = "edge=" + edge +
                        ";direction=" + direction +
                        ";start=" + downX.toInt() + "," + downY.toInt() +
                        ";end=" + event.rawX.toInt() + "," + event.rawY.toInt()
                    emit("edge_swipe", value)
                } else {
                    emit("edge_tap", "edge=" + edge + ";x=" + event.rawX.toInt() + ";y=" + event.rawY.toInt())
                }
                return true
            }
        }
        return true
    }
}

internal class RegionSelectorView(
    context: Context,
    private val selected: (Int, Int, Int, Int) -> Unit,
) : View(context) {
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var selecting = false
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                endX = startX
                endY = startY
                selecting = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x
                endY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x
                endY = event.y
                invalidate()
                val left = minOf(startX, endX).toInt().coerceAtLeast(0)
                val top = minOf(startY, endY).toInt().coerceAtLeast(0)
                val right = maxOf(startX, endX).toInt().coerceAtMost(width)
                val bottom = maxOf(startY, endY).toInt().coerceAtMost(height)
                selecting = false
                if (right - left >= 4 && bottom - top >= 4) {
                    // Region selection coordinates must match Accessibility's full-display screenshot.
                    val offset = IntArray(2)
                    getLocationOnScreen(offset)
                    selected(left + offset[0], top + offset[1], right + offset[0], bottom + offset[1])
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                selecting = false
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        if (!selecting) return
        val left = minOf(startX, endX)
        val top = minOf(startY, endY)
        val right = maxOf(startX, endX)
        val bottom = maxOf(startY, endY)
        canvas.drawRect(left, top, right, bottom, border)
    }
}


internal class GestureRecorderView(
    context: Context,
    private val maxPoints: Int,
    private val recorded: (String) -> Unit,
) : View(context) {
    private val points = ArrayList<Pair<Float, Float>>()
    private var lastCommitted: String? = null

    fun finishRecording(): Boolean {
        if (points.size < 2) return false
        val text = points.joinToString("\n") { point ->
            point.first.toInt().toString() + "," + point.second.toInt().toString()
        }
        if (text != lastCommitted) {
            lastCommitted = text
            recorded(text)
        }
        return true
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init { setBackgroundColor(0x22000000) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                path.reset()
                lastCommitted = null
                addPoint(event.x, event.y, true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.historySize) {
                    addPoint(event.getHistoricalX(index), event.getHistoricalY(index), false)
                }
                addPoint(event.x, event.y, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                addPoint(event.x, event.y, false)
                invalidate()
                finishRecording()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                points.clear()
                path.reset()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun addPoint(x: Float, y: Float, first: Boolean) {
        if (points.size >= maxPoints) return
        val last = points.lastOrNull()
        if (last != null && kotlin.math.abs(last.first - x) < 1f && kotlin.math.abs(last.second - y) < 1f) return
        points += x to y
        if (first || points.size == 1) path.moveTo(x, y) else path.lineTo(x, y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }
}
