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

class OverlaySurfaceController(context: Context) {
    private val context = context.applicationContext
    private val windowManager = context.applicationContext.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val surfaces = ConcurrentHashMap<String, android.view.View>()
    private val recordedGestures = ConcurrentHashMap<String, String>()
    private val taskerSceneModels = ConcurrentHashMap<String, String>()

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


    fun showAppLauncherList(
        id: String,
        title: String,
        apps: List<Pair<String, String>>,
        columns: Int,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank() || apps.isEmpty()) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val grid = GridLayout(context).apply {
                columnCount = columns.coerceIn(1, 6)
                apps.take(120).forEach { (label, packageName) ->
                    addView(Button(context).apply {
                        text = label
                        setOnClickListener {
                            val launched = runCatching {
                                val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                                    ?: return@runCatching false
                                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launch)
                                true
                            }.getOrDefault(false)
                            SurfaceRuntimeBridge.emit(
                                id,
                                if (launched) "recent_app_selected" else "recent_app_launch_failed",
                                packageName,
                            )
                            if (launched) hide(id)
                        }
                    })
                }
            }
            val root = basePanel(title, density).apply {
                addView(ScrollView(context).apply {
                    addView(grid)
                    layoutParams = LinearLayout.LayoutParams(
                        (340 * density).toInt(),
                        (480 * density).toInt(),
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


    fun showEdgeGesture(
        id: String,
        edge: String,
        thicknessDp: Int,
        minDistanceDp: Int,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        val validEdge = edge in setOf("left", "right", "top", "bottom", "top_left", "top_right", "bottom_left", "bottom_right")
        if (!validEdge) return false
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val thickness = (thicknessDp.coerceIn(4, 96) * density).toInt()
            val corner = edge.contains("_")
            val width = when {
                corner -> thickness * 3
                edge == "left" || edge == "right" -> thickness
                else -> WindowManager.LayoutParams.MATCH_PARENT
            }
            val height = when {
                corner -> thickness * 3
                edge == "top" || edge == "bottom" -> thickness
                else -> WindowManager.LayoutParams.MATCH_PARENT
            }
            val view = EdgeGestureView(
                context = context,
                edge = edge,
                minDistancePx = minDistanceDp.coerceIn(8, 400) * density,
            ) { action, value ->
                SurfaceRuntimeBridge.emit(id, action, value)
            }
            addSurface(
                id = id,
                view = view,
                gravity = edge,
                autoHideMs = autoHideMs,
                width = width,
                height = height,
            )
        }
        return true
    }

    fun showRegionSelector(
        id: String,
        autoHideMs: Long,
        onSelected: ((Int, Int, Int, Int) -> Unit)? = null,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val view = RegionSelectorView(context) { left, top, right, bottom ->
                SurfaceRuntimeBridge.emit(
                    id,
                    "region_selected",
                    listOf(left, top, right, bottom).joinToString(","),
                )
                hide(id)
                onSelected?.invoke(left, top, right, bottom)
            }
            addSurface(
                id = id,
                view = view,
                gravity = "center",
                autoHideMs = autoHideMs,
                focusable = true,
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
        return true
    }


    fun showScreenFlash(
        id: String,
        color: Int,
        alpha: Int,
        durationMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val view = View(context).apply {
                setBackgroundColor(
                    android.graphics.Color.argb(
                        alpha.coerceIn(0, 255),
                        android.graphics.Color.red(color),
                        android.graphics.Color.green(color),
                        android.graphics.Color.blue(color),
                    )
                )
            }
            addSurface(
                id = id,
                view = view,
                gravity = "center",
                autoHideMs = durationMs.coerceIn(50L, 60_000L),
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.MATCH_PARENT,
                touchable = false,
            )
            SurfaceRuntimeBridge.emit(id, "flash_shown")
        }
        return true
    }

    fun showDanmu(
        id: String,
        text: String,
        color: Int,
        textSizeSp: Float,
        durationMs: Long,
        gravity: String,
    ): Boolean {
        if (!canDraw() || id.isBlank() || text.isBlank()) return false
        main.post {
            hideInternal(id)
            val view = TextView(context).apply {
                this.text = text
                setTextColor(color)
                textSize = textSizeSp.coerceIn(8f, 72f)
                setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
                setSingleLine(true)
                setPadding(8, 4, 8, 4)
            }
            val duration = durationMs.coerceIn(500L, 120_000L)
            addSurface(
                id = id,
                view = view,
                gravity = gravity,
                autoHideMs = duration + 500L,
                touchable = false,
            )
            view.post {
                val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
                view.translationX = screenWidth
                view.animate()
                    .translationX(-screenWidth - view.width.toFloat())
                    .setDuration(duration)
                    .withEndAction {
                        SurfaceRuntimeBridge.emit(id, "danmu_finished", text)
                        hide(id)
                    }
                    .start()
            }
            SurfaceRuntimeBridge.emit(id, "danmu_started", text)
        }
        return true
    }


    fun showGestureRecorder(
        id: String,
        maxPoints: Int,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        main.post {
            hideInternal(id)
            val view = GestureRecorderView(
                context = context,
                maxPoints = maxPoints.coerceIn(16, 4096),
            ) { path ->
                recordedGestures[id] = path
                SurfaceRuntimeBridge.emit(id, "gesture_recorded", path)
            }
            addSurface(
                id = id,
                view = view,
                gravity = "center",
                autoHideMs = autoHideMs,
                focusable = true,
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.MATCH_PARENT,
            )
            SurfaceRuntimeBridge.emit(id, "gesture_recording_started")
        }
        return true
    }

    fun stopGestureRecorder(id: String): Boolean {
        if (id.isBlank() || surfaces[id] !is GestureRecorderView) return false
        main.post {
            val recorder = surfaces[id] as? GestureRecorderView ?: return@post
            recorder.finishRecording()
            hideInternal(id)
            SurfaceRuntimeBridge.emit(id, "gesture_recording_stopped")
        }
        return true
    }

    fun recordedGesture(id: String): String? = recordedGestures[id]

    fun clearRecordedGesture(id: String): Boolean = recordedGestures.remove(id) != null


    fun createTaskerScene(id: String, modelJson: String): Boolean {
        if (id.isBlank() || modelJson.isBlank()) return false
        return runCatching {
            JSONObject(modelJson)
            taskerSceneModels[id] = modelJson
            true
        }.getOrDefault(false)
    }

    fun showTaskerScene(
        id: String,
        title: String,
        modelJson: String,
        gravity: String,
        autoHideMs: Long,
    ): Boolean {
        if (!canDraw() || id.isBlank()) return false
        val resolved = modelJson.takeIf { it.isNotBlank() } ?: taskerSceneModels[id] ?: return false
        val model = runCatching { JSONObject(resolved) }.getOrNull() ?: return false
        taskerSceneModels[id] = resolved
        main.post {
            hideInternal(id)
            val density = context.resources.displayMetrics.density
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val elements = model.optJSONArray("elements") ?: JSONArray()
            for (index in 0 until elements.length()) {
                val item = elements.optJSONObject(index) ?: continue
                val kind = item.optString("kind")
                val elementId = item.optString("id", "element_" + index)
                val label = item.optString("label")
                val value = item.optString("value")
                val clickTask = item.optString("clickTask")
                val longClickTask = item.optString("longClickTask")
                fun emitTask(taskId: String, action: String, extra: String = "") {
                    if (taskId.isBlank()) return
                    SurfaceRuntimeBridge.emit(
                        id,
                        "task:" + taskId,
                        elementId + if (extra.isBlank()) "" else ":" + extra,
                    )
                    SurfaceRuntimeBridge.emit(id, action, elementId)
                }
                when (kind) {
                    "button" -> content.addView(Button(context).apply {
                        text = label.ifBlank { elementId }
                        setOnClickListener { emitTask(clickTask, "scene_click") }
                        if (longClickTask.isNotBlank()) setOnLongClickListener {
                            emitTask(longClickTask, "scene_long_click")
                            true
                        }
                    })
                    "text" -> content.addView(TextView(context).apply {
                        text = value.ifBlank { label.ifBlank { elementId } }
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 15f
                        setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                        if (clickTask.isNotBlank()) setOnClickListener { emitTask(clickTask, "scene_click") }
                        if (longClickTask.isNotBlank()) setOnLongClickListener {
                            emitTask(longClickTask, "scene_long_click")
                            true
                        }
                    })
                    "input" -> content.addView(EditText(context).apply {
                        hint = label
                        setText(value)
                        setTextColor(android.graphics.Color.WHITE)
                        setHintTextColor(0xFF9AA0A6.toInt())
                        setOnFocusChangeListener { _, hasFocus ->
                            if (!hasFocus) emitTask(clickTask, "scene_input", text.toString())
                        }
                    })
                    "image" -> content.addView(ImageView(context).apply {
                        adjustViewBounds = true
                        value.takeIf(String::isNotBlank)?.let { source ->
                            loadOverlayBitmap(source)?.let(::setImageBitmap)
                        }
                        if (clickTask.isNotBlank()) setOnClickListener { emitTask(clickTask, "scene_click") }
                        if (longClickTask.isNotBlank()) setOnLongClickListener {
                            emitTask(longClickTask, "scene_long_click")
                            true
                        }
                    })
                    "list", "spinner" -> {
                        val items = item.optJSONArray("items") ?: JSONArray()
                        val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                        for (itemIndex in 0 until items.length()) {
                            val itemText = items.optString(itemIndex)
                            group.addView(Button(context).apply {
                                text = itemText
                                setOnClickListener { emitTask(clickTask, "scene_item", itemText) }
                            })
                        }
                        content.addView(group)
                    }
                    "slider" -> content.addView(SeekBar(context).apply {
                        max = item.optInt("max", 100).coerceAtLeast(1)
                        progress = item.optInt("progress", 0).coerceIn(0, max)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                                emitTask(clickTask, "scene_value", progress.toString())
                            }
                        })
                    })
                    "toggle", "switch", "checkbox" -> content.addView(Switch(context).apply {
                        text = label.ifBlank { elementId }
                        isChecked = item.optBoolean("checked", false)
                        setTextColor(android.graphics.Color.WHITE)
                        setOnCheckedChangeListener { _, checked ->
                            emitTask(clickTask, "scene_value", checked.toString())
                        }
                    })
                    "web" -> content.addView(WebView(context).apply {
                        settings.javaScriptEnabled = false
                        webViewClient = WebViewClient()
                        if (value.startsWith("http://") || value.startsWith("https://")) loadUrl(value)
                        else loadDataWithBaseURL(null, value, "text/html", "UTF-8", null)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (320 * density).toInt(),
                        )
                    })
                    else -> if (label.isNotBlank() || value.isNotBlank()) {
                        content.addView(TextView(context).apply {
                            text = listOf(label, value).filter(String::isNotBlank).joinToString(" ")
                            setTextColor(0xFFE8EAED.toInt())
                        })
                    }
                }
            }
            val root = basePanel(title.ifBlank { model.optString("name") }, density).apply {
                addView(
                    ScrollView(context).apply { addView(content) },
                    LinearLayout.LayoutParams(
                        (360 * density).toInt(),
                        (520 * density).toInt(),
                    ),
                )
            }
            addSurface(id, root, gravity, autoHideMs)
            SurfaceRuntimeBridge.emit(id, "scene_shown")
        }
        return true
    }

    fun destroyTaskerScene(id: String): Boolean {
        val existed = taskerSceneModels.remove(id) != null
        hide(id)
        return existed
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
