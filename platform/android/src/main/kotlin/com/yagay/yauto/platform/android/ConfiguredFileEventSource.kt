package com.yagay.yauto.platform.android

import android.os.FileObserver
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches only paths referenced by enabled YAuto rules. Rule changes are reconciled without
 * restarting the runtime service; one watcher is shared for each unique event configuration.
 */
class ConfiguredFileEventSource(
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.file.configured"
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchers = ConcurrentHashMap<String, RuleWatcher>()
    @Volatile private var emitter: RuntimeEventEmitter? = null
    private var refreshJob: Job? = null

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        refreshJob = scope.launch {
            while (isActive && started.get()) {
                refreshRules()
                delay(2_000L)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        refreshJob?.cancel()
        refreshJob = null
        watchers.values.forEach(RuleWatcher::stop)
        watchers.clear()
        emitter = null
        scope.cancel()
    }

    private suspend fun refreshRules() {
        val rules = runCatching {
            workspace.load().automations.asSequence()
                .filter { it.enabled }
                .flatMap { it.activation.events.asSequence() }
                .filter { it.typeId == "android.event.file_changed" }
                .mapNotNull(::toRule)
                .distinctBy { it.key }
                .associateBy { it.key }
        }.getOrDefault(emptyMap())

        watchers.keys.filter { it !in rules }.forEach { key -> watchers.remove(key)?.stop() }
        rules.values.forEach { rule ->
            if (watchers.containsKey(rule.key)) return@forEach
            val watcher = RuleWatcher(rule) { event -> emitter?.emit(event) }
            if (watcher.start()) watchers[rule.key] = watcher
        }
    }

    private fun toRule(feature: FeatureRef): FileWatchRule? {
        val rawPath = feature.config.string("path").trim()
        if (rawPath.isBlank()) return null
        val event = feature.config.string("event", "any")
            .takeIf { it in EVENT_NAMES } ?: "any"
        return FileWatchRule(
            key = fileWatchSubscriptionKey(feature),
            path = File(rawPath).absoluteFile,
            event = event,
            recursive = feature.config.boolean("recursive"),
            nameContains = feature.config.string("nameContains").trim(),
        )
    }

    private data class FileWatchRule(
        val key: String,
        val path: File,
        val event: String,
        val recursive: Boolean,
        val nameContains: String,
    )

    private class RuleWatcher(
        private val rule: FileWatchRule,
        private val emit: (RuntimeEvent) -> Unit,
    ) {
        private val observers = ConcurrentHashMap<String, FileObserver>()
        private val targetFileName: String? = if (rule.path.isDirectory) null else rule.path.name
        private val rootDirectory: File = if (rule.path.isDirectory) rule.path else rule.path.parentFile ?: rule.path

        fun start(): Boolean {
            if (!rootDirectory.isDirectory) return false
            addDirectory(rootDirectory)
            if (rule.recursive) {
                rootDirectory.walkTopDown()
                    .maxDepth(64)
                    .filter { it.isDirectory }
                    .forEach(::addDirectory)
            }
            return observers.isNotEmpty()
        }

        fun stop() {
            observers.values.forEach { runCatching { it.stopWatching() } }
            observers.clear()
        }

        private fun addDirectory(directory: File) {
            val absolute = directory.absoluteFile
            val path = absolute.path
            if (!absolute.isDirectory || observers.containsKey(path)) return
            val observer = object : FileObserver(absolute, WATCH_MASK) {
                override fun onEvent(event: Int, relativePath: String?) {
                    val normalized = event and FileObserver.ALL_EVENTS
                    val eventName = fileEventName(normalized) ?: return
                    val childName = relativePath.orEmpty()
                    if (absolute == rootDirectory && targetFileName != null && childName != targetFileName) return
                    if (rule.event != "any" && rule.event != eventName) return
                    if (rule.nameContains.isNotBlank() && !childName.contains(rule.nameContains, ignoreCase = true)) return

                    val fullPath = if (childName.isBlank()) absolute else File(absolute, childName)
                    val isDirectory = event and FileObserver.ISDIR != 0
                    emit(
                        RuntimeEvent(
                            typeId = "android.event.file_changed",
                            payload = mapOf(
                                "subscription" to ConfigValue.StringValue(rule.key),
                                "event" to ConfigValue.StringValue(eventName),
                                "path" to ConfigValue.StringValue(fullPath.absolutePath),
                                "relativePath" to ConfigValue.StringValue(childName),
                                "watchedPath" to ConfigValue.StringValue(rule.path.absolutePath),
                                "directory" to ConfigValue.BooleanValue(isDirectory),
                            ),
                            source = "android.file.configured",
                        )
                    )

                    if (rule.recursive && isDirectory && normalized in setOf(FileObserver.CREATE, FileObserver.MOVED_TO)) {
                        addDirectory(fullPath)
                    }
                    if (isDirectory && normalized in setOf(FileObserver.DELETE, FileObserver.MOVED_FROM, FileObserver.DELETE_SELF)) {
                        removeDirectory(fullPath)
                    }
                }
            }
            observers[path] = observer
            observer.startWatching()
        }

        private fun removeDirectory(directory: File) {
            val prefix = directory.absolutePath
            observers.keys.filter { it == prefix || it.startsWith("$prefix${File.separator}") }.forEach { key ->
                observers.remove(key)?.stopWatching()
            }
        }
    }

    private companion object {
        val EVENT_NAMES = setOf("any", "create", "delete", "modify", "move", "close_write")
        const val WATCH_MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MODIFY or
            FileObserver.ATTRIB or FileObserver.CLOSE_WRITE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}

private fun fileEventName(event: Int): String? = when (event) {
    FileObserver.CREATE -> "create"
    FileObserver.DELETE, FileObserver.DELETE_SELF -> "delete"
    FileObserver.MODIFY, FileObserver.ATTRIB -> "modify"
    FileObserver.MOVED_FROM, FileObserver.MOVED_TO, FileObserver.MOVE_SELF -> "move"
    FileObserver.CLOSE_WRITE -> "close_write"
    else -> null
}
