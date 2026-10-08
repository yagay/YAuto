package com.yagay.yauto.platform.android

import com.yagay.yauto.core.storage.ObservableWorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.runtimeEventFeatureIds
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Starts an expensive configured event source only while the workspace actually contains a
 * matching enabled trigger. The delegate is otherwise fully stopped, so idle YAuto workspaces do
 * not keep sensor, location, file, logcat or polling loops alive.
 */
class WorkspaceGatedEventSource(
    private val workspace: ObservableWorkspaceRepository,
    private val requiredEventTypeIds: Set<String>,
    private val factory: () -> AndroidEventSource,
) : AndroidEventSource {
    private val started = AtomicBoolean(false)
    /** Every start gets a fresh job; a cancelled scope cannot be reused after stop(). */
    private var scope: CoroutineScope? = null
    private var delegate: AndroidEventSource? = null
    private var delegateStarted = false
    private var subscription: AutoCloseable? = null
    @Volatile private var emitter: RuntimeEventEmitter? = null

    override val id: String =
        "workspace-gated:" + requiredEventTypeIds.sorted().joinToString(",")

    @Synchronized
    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = activeScope
        this.emitter = emitter
        subscription = workspace.addListener { data ->
            activeScope.launch { applyWorkspace(data) }
        }
        // Not all ObservableWorkspaceRepository implementations replay their snapshot to newly
        // added listeners. Always apply the initial workspace explicitly.
        val snapshot = workspace.snapshotOrNull()
        if (snapshot != null) {
            activeScope.launch { applyWorkspace(snapshot) }
        } else {
            activeScope.launch {
                runCatching { workspace.load() }.onSuccess(::applyWorkspace)
            }
        }
    }

    @Synchronized
    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        subscription?.close()
        subscription = null
        scope?.cancel()
        scope = null
        stopDelegate()
        emitter = null
    }

    @Synchronized
    private fun applyWorkspace(data: WorkspaceData) {
        if (!started.get()) return
        val needed = data.runtimeEventFeatureIds().any { it in requiredEventTypeIds }
        if (needed) startDelegate() else stopDelegate()
    }

    @Synchronized
    private fun startDelegate() {
        if (delegateStarted) return
        val target = delegate ?: factory().also { delegate = it }
        val currentEmitter = emitter ?: return
        target.start(currentEmitter)
        delegateStarted = true
    }

    @Synchronized
    private fun stopDelegate() {
        if (!delegateStarted) return
        runCatching { delegate?.stop() }
        delegateStarted = false
        // Configured sources generally cancel their internal scope on stop and are intentionally
        // not restartable. Drop the instance so a future subscription gets a fresh source.
        delegate = null
    }
}
