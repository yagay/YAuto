package com.yagay.yauto.platform.android

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

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
    ) {
        val density = context.resources.displayMetrics.density
        val flags = (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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
