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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class OverlaySurfaceController(context: Context) {
    private val context = context.applicationContext
    private val windowManager = context.applicationContext.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val surfaces = ConcurrentHashMap<String, android.view.View>()

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show(
        id: String,
        title: String,
        text: String,
        buttonLabel: String,
        buttonAction: String,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(0xEE202124.toInt())
                }
            }
            if (title.isNotBlank()) root.addView(TextView(context).apply {
                this.text = title
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            if (text.isNotBlank()) root.addView(TextView(context).apply {
                this.text = text
                setTextColor(0xFFE8EAED.toInt())
                textSize = 14f
                setPadding(0, (6 * density).toInt(), 0, 0)
            })
            if (buttonLabel.isNotBlank()) root.addView(Button(context).apply {
                this.text = buttonLabel
                setOnClickListener {
                    SurfaceRuntimeBridge.emit(id, buttonAction.ifBlank { "button" })
                }
            })
            root.setOnLongClickListener {
                SurfaceRuntimeBridge.emit(id, "long_press")
                true
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                this.gravity = when (gravity) {
                    "top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    "top_left" -> Gravity.TOP or Gravity.START
                    "top_right" -> Gravity.TOP or Gravity.END
                    "bottom_left" -> Gravity.BOTTOM or Gravity.START
                    "bottom_right" -> Gravity.BOTTOM or Gravity.END
                    else -> Gravity.CENTER
                }
                x = 0
                y = (24 * density).toInt()
            }
            runCatching {
                windowManager.addView(root, params)
                surfaces[id] = root
                SurfaceRuntimeBridge.emit(id, "shown")
                if (autoHideMs > 0) main.postDelayed({ hide(id) }, autoHideMs.coerceAtMost(86_400_000))
            }
        }
        return true
    }


    fun showInput(
        id: String,
        title: String,
        hint: String,
        initialValue: String,
        submitLabel: String,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val input = EditText(context).apply {
                this.hint = hint
                setText(initialValue)
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(0xFF9AA0A6.toInt())
                minWidth = (240 * density).toInt()
            }
            val root = basePanel(title, density).apply {
                addView(input)
                addView(Button(context).apply {
                    text = submitLabel.ifBlank { "OK" }
                    setOnClickListener {
                        SurfaceRuntimeBridge.emit(id, "submit", input.text?.toString().orEmpty())
                    }
                })
            }
            addSurface(id, root, gravity, autoHideMs, focusable = true)
            input.requestFocus()
        }
        return true
    }

    fun showList(
        id: String,
        title: String,
        items: List<String>,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank() || items.isEmpty()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                items.take(100).forEachIndexed { index, item ->
                    addView(Button(context).apply {
                        text = item
                        setOnClickListener { SurfaceRuntimeBridge.emit(id, "select:$index", item) }
                    })
                }
            }
            val root = basePanel(title, density).apply {
                addView(ScrollView(context).apply {
                    addView(list)
                    layoutParams = LinearLayout.LayoutParams(
                        (300 * density).toInt(),
                        (420 * density).toInt(),
                    )
                })
            }
            addSurface(id, root, gravity, autoHideMs)
        }
        return true
    }

    fun showButtonGrid(
        id: String,
        title: String,
        items: List<Pair<String, String>>,
        columns: Int,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank() || items.isEmpty()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val grid = GridLayout(context).apply {
                columnCount = columns.coerceIn(1, 6)
                items.take(60).forEach { (label, action) ->
                    addView(Button(context).apply {
                        text = label
                        setOnClickListener { SurfaceRuntimeBridge.emit(id, action.ifBlank { label }, label) }
                    })
                }
            }
            val root = basePanel(title, density).apply { addView(grid) }
            addSurface(id, root, gravity, autoHideMs)
        }
        return true
    }

    fun showCompact(
        id: String,
        text: String,
        action: String,
        gravity: String,
        autoHideMs: Long,
        chip: Boolean,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val view = Button(context).apply {
                this.text = text
                textSize = if (chip) 12f else 16f
                minWidth = if (chip) (64 * density).toInt() else (48 * density).toInt()
                minHeight = if (chip) (32 * density).toInt() else (48 * density).toInt()
                setOnClickListener { SurfaceRuntimeBridge.emit(id, action.ifBlank { "click" }, text) }
                setOnLongClickListener {
                    SurfaceRuntimeBridge.emit(id, "long_press", text)
                    true
                }
            }
            addSurface(id, view, gravity, autoHideMs)
        }
        return true
    }

    fun showTouchBlocker(id: String, autoHideMs: Long): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val blocker = View(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                        SurfaceRuntimeBridge.emit(id, "touch", "${event.rawX.toInt()},${event.rawY.toInt()}")
                    }
                    true
                }
            }
            addSurface(
                id = id,
                view = blocker,
                gravity = "center",
                autoHideMs = autoHideMs,
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
        return true
    }

    fun showPie(
        id: String,
        items: List<Pair<String, String>>,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank() || items.isEmpty()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val pie = PieMenuView(context, items.take(12)) { label, action ->
                SurfaceRuntimeBridge.emit(id, action.ifBlank { label }, label)
            }.apply {
                layoutParams = android.view.ViewGroup.LayoutParams((300 * density).toInt(), (300 * density).toInt())
            }
            addSurface(
                id = id,
                view = pie,
                gravity = gravity,
                autoHideMs = autoHideMs,
                width = (300 * density).toInt(),
                height = (300 * density).toInt(),
            )
        }
        return true
    }

    fun showSlider(
        id: String,
        title: String,
        minValue: Int,
        maxValue: Int,
        value: Int,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank() || maxValue <= minValue) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val label = TextView(context).apply {
                setTextColor(android.graphics.Color.WHITE)
                text = value.coerceIn(minValue, maxValue).toString()
            }
            val seek = SeekBar(context).apply {
                max = maxValue - minValue
                progress = value.coerceIn(minValue, maxValue) - minValue
                layoutParams = LinearLayout.LayoutParams((280 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        label.text = (minValue + progress).toString()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val selected = minValue + (seekBar?.progress ?: 0)
                        SurfaceRuntimeBridge.emit(id, "value_changed", selected.toString())
                    }
                })
            }
            val root = basePanel(title, density).apply {
                addView(label)
                addView(seek)
            }
            addSurface(id, root, gravity, autoHideMs, focusable = true)
        }
        return true
    }

    fun showToggle(
        id: String,
        text: String,
        checked: Boolean,
        action: String,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val root = basePanel("", density).apply {
                addView(Switch(context).apply {
                    this.text = text
                    isChecked = checked
                    setTextColor(android.graphics.Color.WHITE)
                    setOnCheckedChangeListener { _, value ->
                        SurfaceRuntimeBridge.emit(id, action.ifBlank { "toggle" }, value.toString())
                    }
                })
            }
            addSurface(id, root, gravity, autoHideMs)
        }
        return true
    }

    fun showImage(
        id: String,
        source: String,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank() || source.isBlank()) return false
        val bitmap = loadOverlayBitmap(source) ?: return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val view = ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                maxWidth = (360 * density).toInt()
                maxHeight = (480 * density).toInt()
                setOnClickListener { SurfaceRuntimeBridge.emit(id, "click", source) }
            }
            addSurface(id, view, gravity, autoHideMs)
        }
        return true
    }

    fun showWeb(
        id: String,
        url: String,
        gravity: String,
        autoHideMs: Long,
        javaScript: Boolean,
    ): Boolean {
        if (!canDraw() || id.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val web = WebView(context).apply {
                settings.javaScriptEnabled = javaScript
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        SurfaceRuntimeBridge.emit(id, "page_finished", finishedUrl.orEmpty())
                    }
                }
                loadUrl(url)
            }
            addSurface(
                id = id,
                view = web,
                gravity = gravity,
                autoHideMs = autoHideMs,
                focusable = true,
                width = (360 * density).toInt(),
                height = (520 * density).toInt(),
            )
        }
        return true
    }

    fun showDrawBoard(
        id: String,
        title: String,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val board = DrawingBoardView(context).apply {
                layoutParams = LinearLayout.LayoutParams((320 * density).toInt(), (320 * density).toInt())
            }
            val root = basePanel(title, density).apply {
                addView(board)
                val actions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(context).apply {
                        text = "Clear"
                        setOnClickListener { board.clear(); SurfaceRuntimeBridge.emit(id, "clear") }
                    })
                    addView(Button(context).apply {
                        text = "Save"
                        setOnClickListener {
                            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Drawings").apply { mkdirs() }
                            val file = File(dir, "drawing-${System.currentTimeMillis()}.png")
                            val ok = board.save(file)
                            SurfaceRuntimeBridge.emit(id, if (ok) "saved" else "save_failed", if (ok) file.absolutePath else "")
                        }
                    })
                }
                addView(actions)
            }
            addSurface(id, root, gravity, autoHideMs, focusable = true)
        }
        return true
    }

    private fun loadOverlayBitmap(source: String): Bitmap? = runCatching {
        when {
            source.startsWith("content://") -> context.contentResolver.openInputStream(android.net.Uri.parse(source))?.use(BitmapFactory::decodeStream)
            source.startsWith("file://") -> BitmapFactory.decodeFile(android.net.Uri.parse(source).path)
            else -> BitmapFactory.decodeFile(source)
        }
    }.getOrNull()

    fun showEdgeLighting(
        id: String,
        color: Int,
        thicknessDp: Int,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val thickness = (thicknessDp.coerceIn(1, 48) * density).toInt()
            val view = EdgeLightingView(context, color, thickness)
            addSurface(
                id = id,
                view = view,
                gravity = "center",
                autoHideMs = autoHideMs.coerceAtLeast(250L),
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.MATCH_PARENT,
                touchable = false,
            )
        }
        return true
    }

    fun showProgress(
        id: String,
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val root = basePanel(title, density).apply {
                if (text.isNotBlank()) addView(TextView(context).apply {
                    this.text = text
                    setTextColor(0xFFE8EAED.toInt())
                })
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    this.isIndeterminate = indeterminate
                    max = 100
                    this.progress = progress.coerceIn(0, 100)
                    layoutParams = LinearLayout.LayoutParams((260 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
            addSurface(id, root, gravity, autoHideMs)
        }
        return true
    }

    fun showSidebar(
        id: String,
        title: String,
        items: List<Pair<String, String>>,
        side: String,
        autoHideMs: Long,
    ): Boolean = showButtonGrid(
        id = id,
        title = title,
        items = items,
        columns = 1,
        gravity = if (side == "right") "top_right" else "top_left",
        autoHideMs = autoHideMs,
    )

    private fun basePanel(title: String, density: Float): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(0xEE202124.toInt())
        }
        if (title.isNotBlank()) addView(TextView(context).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun addSurface(
        id: String,
        view: android.view.View,
        gravity: String,
        autoHideMs: Long,
        focusable: Boolean = false,
        width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        touchable: Boolean = true,
    ) {
        val density = context.resources.displayMetrics.density
        val flags = (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravityValue(gravity)
            x = 0
            y = (24 * density).toInt()
            if (focusable) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching {
            windowManager.addView(view, params)
            surfaces[id] = view
            SurfaceRuntimeBridge.emit(id, "shown")
            if (autoHideMs > 0) main.postDelayed({ hide(id) }, autoHideMs.coerceAtMost(86_400_000))
        }
    }

    private fun gravityValue(gravity: String): Int = when (gravity) {
        "top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        "left" -> Gravity.CENTER_VERTICAL or Gravity.START
        "right" -> Gravity.CENTER_VERTICAL or Gravity.END
        "top_left" -> Gravity.TOP or Gravity.START
        "top_right" -> Gravity.TOP or Gravity.END
        "bottom_left" -> Gravity.BOTTOM or Gravity.START
        "bottom_right" -> Gravity.BOTTOM or Gravity.END
        else -> Gravity.CENTER
    }

    fun hide(id: String): Boolean {
        if (id.isBlank()) return false
        val existed = surfaces.containsKey(id)
        main.post { hideInternal(id) }
        return existed
    }

    fun hideAll() {
        main.post { surfaces.keys.toList().forEach(::hideInternal) }
    }

    fun isShown(id: String): Boolean = surfaces.containsKey(id)

    private fun hideInternal(id: String) {
        val view = surfaces.remove(id) ?: return
        runCatching { windowManager.removeView(view) }
        SurfaceRuntimeBridge.emit(id, "hidden")
    }
}


private class PieMenuView(
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


private class DrawingBoardView(context: Context) : View(context) {
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


private class EdgeLightingView(
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
