package com.yagay.yauto.platform.android

import com.yagay.yauto.core.storage.ObservableWorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Consume repository change notifications rather than repeatedly decoding the entire workspace.
 * A non-observable repository retains a slow polling fallback for compatibility/test fixtures.
 */
internal fun CoroutineScope.watchWorkspaceChanges(
    repository: WorkspaceRepository,
    onChanged: suspend (WorkspaceData) -> Unit,
): Job = launch {
    val observable = repository as? ObservableWorkspaceRepository
    val changes = Channel<WorkspaceData>(Channel.CONFLATED)
    val subscription = observable?.addListener { data -> changes.trySend(data) }
    try {
        if (observable != null) {
            // Register first so a save during initial load cannot be missed.
            changes.trySend(observable.snapshotOrNull() ?: repository.load())
            // A queued initial snapshot can be older than a simultaneous save callback.
            // Prefer the repository's latest atomic snapshot before applying each update.
            for (data in changes) onChanged(observable.snapshotOrNull() ?: data)
        } else {
            while (isActive) {
                onChanged(repository.load())
                delay(5_000L)
            }
        }
    } finally {
        subscription?.close()
        changes.close()
    }
}
