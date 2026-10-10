package com.yagay.yauto.platform.android

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.view.View
import android.view.WindowManager
import java.util.concurrent.ConcurrentHashMap

/** Owns all Android overlay windows and delayed dismissals in one lifecycle. */
internal class OverlayWindowLifecycle(
    private val context: Context,
    private val main: Handler,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val surfaces = ConcurrentHashMap<String, View>()
    private val dismissals = ConcurrentHashMap<String, Runnable>()

    fun view(id: String): View? = surfaces[id]
    fun contains(id: String): Boolean = surfaces.containsKey(id)

    fun add(
        id: String, view: View, gravity: Int, timeoutMs: Long,
        focusable: Boolean = false,
        width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        touchable: Boolean = true,
    ) {
        val flags = (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val params = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            x = 0
            y = (24 * context.resources.displayMetrics.density).toInt()
            if (focusable) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        attach(id, view, params, timeoutMs)
    }

    fun attach(id: String, view: View, params: WindowManager.LayoutParams, timeoutMs: Long) {
        // A surface ID owns at most one attached View. Remove the previous window
        // before installing its replacement to avoid orphaned WindowManager views.
        if (surfaces.containsKey(id)) remove(id)
        runCatching {
            manager.addView(view, params)
            surfaces[id] = view
            SurfaceRuntimeBridge.emit(id, "shown")
            schedule(id, view, timeoutMs)
        }
    }

    private fun schedule(id: String, view: View, timeoutMs: Long) {
        dismissals.remove(id)?.let(main::removeCallbacks)
        if (timeoutMs <= 0) return
        val callback = object : Runnable {
            override fun run() {
                dismissals.remove(id, this)
                if (surfaces[id] === view) remove(id)
            }
        }
        dismissals[id] = callback
        main.postDelayed(callback, timeoutMs.coerceAtMost(86_400_000))
    }

    fun remove(id: String) {
        dismissals.remove(id)?.let(main::removeCallbacks)
        val view = surfaces.remove(id) ?: return
        runCatching { manager.removeView(view) }
        SurfaceRuntimeBridge.emit(id, "hidden")
    }

    fun removeAll() {
        surfaces.keys.toList().forEach(::remove)
    }
}
