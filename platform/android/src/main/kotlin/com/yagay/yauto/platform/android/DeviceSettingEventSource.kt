package com.yagay.yauto.platform.android

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

/** Emits runtime events for settings that do not have a reliable public broadcast on modern Android. */
class DeviceSettingEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.device.settings"
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null

    private val autoRotateUri = Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)
    private val screenTimeoutUri = Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            when (uri) {
                autoRotateUri -> emitAutoRotate()
                screenTimeoutUri -> emitScreenTimeout()
            }
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        try {
            resolver.registerContentObserver(autoRotateUri, false, observer)
            resolver.registerContentObserver(screenTimeoutUri, false, observer)
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { resolver.unregisterContentObserver(observer) }
        emitter = null
    }

    private fun emitAutoRotate() {
        val enabled = runCatching {
            Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        }.getOrDefault(false)
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.auto_rotate_changed",
                payload = mapOf("enabled" to ConfigValue.BooleanValue(enabled)),
                source = id,
            )
        )
    }

    private fun emitScreenTimeout() {
        val timeoutMs = runCatching {
            Settings.System.getLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT).toDouble()
        }.getOrNull() ?: return
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.screen_timeout_changed",
                payload = mapOf("timeoutMs" to ConfigValue.NumberValue(timeoutMs)),
                source = id,
            )
        )
    }
}
