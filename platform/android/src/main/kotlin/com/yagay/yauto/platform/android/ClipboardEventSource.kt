package com.yagay.yauto.platform.android

import android.content.ClipboardManager
import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

class ClipboardEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.clipboard"
    private val context = context.applicationContext
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = runCatching {
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.clipboard_changed",
                payload = mapOf(
                    "text" to ConfigValue.StringValue(text),
                    "hasText" to ConfigValue.BooleanValue(text.isNotEmpty()),
                ),
                source = id,
            )
        )
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        clipboard.addPrimaryClipChangedListener(listener)
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { clipboard.removePrimaryClipChangedListener(listener) }
        emitter = null
    }
}
