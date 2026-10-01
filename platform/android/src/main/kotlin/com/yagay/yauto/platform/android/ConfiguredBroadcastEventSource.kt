package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers only the custom broadcast actions referenced by enabled automations. The workspace is
 * watched on disk so imports, backup restores and future writers all refresh the receiver without
 * coupling the source to a particular UI screen.
 */
class ConfiguredBroadcastEventSource(
    context: Context,
    private val workspace: WorkspaceRepository = JsonWorkspaceRepository(context),
) : AndroidEventSource {
    override val id: String = "android.configured.broadcasts"
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    private val lock = Any()
    private var emitter: RuntimeEventEmitter? = null
    private var registered = false
    private var registeredActions = emptySet<String>()
    private var refreshJob: Job? = null
    private var scope = newScope()
    private val workspaceDir = File(this.context.filesDir, "workspace")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.broadcast",
                    payload = buildMap {
                        put("action", ConfigValue.StringValue(action))
                        intent.dataString?.let { put("data", ConfigValue.StringValue(it)) }
                        put("flags", ConfigValue.NumberValue(intent.flags.toDouble()))
                        put("extras", ConfigValue.ObjectValue(bundleValues(intent.extras)))
                    },
                    source = id,
                )
            )
        }
    }

    private val observer = object : FileObserver(
        workspaceDir,
        CLOSE_WRITE or MOVED_TO or CREATE or DELETE or MOVED_FROM,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path == null || !path.startsWith("workspace.json")) return
            scheduleRefresh()
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        if (!workspaceDir.isDirectory) workspaceDir.mkdirs()
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        observer.startWatching()
        scheduleRefresh(immediate = true)
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        observer.stopWatching()
        refreshJob?.cancel()
        refreshJob = null
        scope.cancel()
        synchronized(lock) { unregisterLocked() }
        emitter = null
    }

    private fun scheduleRefresh(immediate: Boolean = false) {
        if (!started.get()) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (!immediate) delay(150)
            val actions = runCatching { workspace.load().configuredBroadcastActions() }.getOrElse { emptySet() }
            updateActions(actions)
        }
    }

    private fun updateActions(actions: Set<String>) = synchronized(lock) {
        val normalized = actions.asSequence().map(String::trim).filter { it.isNotEmpty() && it.length <= 512 }.toSortedSet()
        if (normalized == registeredActions) return@synchronized
        unregisterLocked()
        registeredActions = emptySet()
        if (normalized.isEmpty() || !started.get()) return@synchronized

        val filter = IntentFilter().apply { normalized.forEach(::addAction) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        }.onSuccess {
            registered = true
            registeredActions = normalized
        }
    }

    private fun unregisterLocked() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        registeredActions = emptySet()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun bundleValues(bundle: Bundle?): Map<String, ConfigValue> {
        if (bundle == null) return emptyMap()
        return bundle.keySet().associateWith { key -> anyValue(runCatching { bundle.get(key) }.getOrNull()) }
    }

    private fun anyValue(value: Any?): ConfigValue = when (value) {
        null -> ConfigValue.NullValue
        is Boolean -> ConfigValue.BooleanValue(value)
        is Number -> ConfigValue.NumberValue(value.toDouble())
        is CharSequence -> ConfigValue.StringValue(value.toString())
        is BooleanArray -> ConfigValue.ListValue(value.map(ConfigValue::BooleanValue))
        is ByteArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is ShortArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is IntArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is LongArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is FloatArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is DoubleArray -> ConfigValue.ListValue(value.map(ConfigValue::NumberValue))
        is Array<*> -> ConfigValue.ListValue(value.map(::anyValue))
        is Iterable<*> -> ConfigValue.ListValue(value.map(::anyValue))
        else -> ConfigValue.StringValue(value.toString())
    }
}

fun WorkspaceData.configuredBroadcastActions(): Set<String> = automations.asSequence()
    .filter { it.enabled }
    .flatMap { it.activation.events.asSequence() }
    .filter { it.typeId == "android.event.broadcast" }
    .mapNotNull { (it.config["action"] as? ConfigValue.StringValue)?.value?.trim()?.takeIf(String::isNotEmpty) }
    .toSet()
