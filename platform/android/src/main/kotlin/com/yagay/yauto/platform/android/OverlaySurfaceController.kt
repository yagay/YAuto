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
import android.widget.LinearLayout
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
