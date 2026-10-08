package com.yagay.yauto.platform.android

import android.content.Context
import android.telephony.TelephonyManager
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ConfiguredCellTowerEventSource(
    context: Context,
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.cell_tower.configured"
    private val context = context.applicationContext
    private val telephony = this.context.getSystemService(TelephonyManager::class.java)
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var emitter: RuntimeEventEmitter? = null
    private var previousSignature: Set<String> = emptySet()

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        job = scope.launch {
            while (isActive && started.get()) {
                val needed = runCatching {
                    workspace.load().automations.any { automation ->
                        automation.enabled && automation.activation.events.any { it.typeId == "android.event.cell_tower_changed" }
                    }
                }.getOrDefault(false)
                if (needed && runtimePermissionGranted(context, "phone") && runtimePermissionGranted(context, "location")) {
                    @Suppress("MissingPermission")
                    val cells = runCatching { telephony.allCellInfo.orEmpty() }.getOrDefault(emptyList())
                    val values = cells.map(::cellInfoValue)
                    val signature = values.map { it.value.toString() }.toSet()
                    if (previousSignature.isNotEmpty() && signature != previousSignature) {
                        values.forEach { value ->
                            emitter?.emit(
                                RuntimeEvent(
                                    typeId = "android.event.cell_tower_changed",
                                    payload = value.value,
                                    source = id,
                                )
                            )
                        }
                    }
                    previousSignature = signature
                } else {
                    previousSignature = emptySet()
                }
                delay(5_000L)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        job?.cancel()
        job = null
        previousSignature = emptySet()
        emitter = null
        scope.cancel()
    }
}
