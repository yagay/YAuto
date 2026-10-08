package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ConfiguredDataUsageEventSource(
    context: Context,
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.data_usage.configured"
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val above = ConcurrentHashMap<String, Boolean>()

    override fun start(emitter: RuntimeEventEmitter) {
        job = scope.launch {
            while (isActive) {
                val currentKeys = HashSet<String>()
                val automations = runCatching { workspace.load().automations }.getOrDefault(emptyList())
                automations.filter { it.enabled }.forEach { automation ->
                    automation.activation.events.filter { it.typeId == "android.event.data_usage_threshold" }.forEachIndexed { index, feature ->
                        val pkg = feature.config.string("package").trim()
                        if (pkg.isBlank()) return@forEachIndexed
                        val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: return@forEachIndexed
                        val threshold = feature.config["thresholdBytes"].numberOrNull()?.takeIf { it > 0.0 } ?: return@forEachIndexed
                        val hours = (feature.config["pastHours"].numberOrNull() ?: 24.0).coerceIn(0.01, 8760.0)
                        val end = System.currentTimeMillis()
                        val usage = queryUidUsage(context, uid, end - (hours * 3_600_000.0).toLong(), end, feature.config.string("network", "all"))
                        val value = when (feature.config.string("direction", "total")) {
                            "received" -> usage.rxBytes
                            "transmitted" -> usage.txBytes
                            else -> usage.rxBytes + usage.txBytes
                        }
                        val key = "${automation.id}:$index:$pkg:${feature.config.string("network", "all")}:${feature.config.string("direction", "total")}:$threshold"
                        currentKeys += key
                        val isAbove = value.toDouble() >= threshold
                        val wasAbove = above.put(key, isAbove) ?: false
                        if (isAbove && !wasAbove) {
                            emitter.emit(
                                RuntimeEvent(
                                    typeId = "android.event.data_usage_threshold",
                                    payload = mapOf(
                                        "package" to ConfigValue.StringValue(pkg),
                                        "bytes" to ConfigValue.NumberValue(value.toDouble()),
                                        "thresholdBytes" to ConfigValue.NumberValue(threshold),
                                        "receivedBytes" to ConfigValue.NumberValue(usage.rxBytes.toDouble()),
                                        "transmittedBytes" to ConfigValue.NumberValue(usage.txBytes.toDouble()),
                                    ),
                                    source = id,
                                )
                            )
                        }
                    }
                }
                above.keys.removeIf { it !in currentKeys }
                delay(30_000L)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        above.clear()
    }
}
