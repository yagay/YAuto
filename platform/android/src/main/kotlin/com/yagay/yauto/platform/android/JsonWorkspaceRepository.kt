package com.yagay.yauto.platform.android

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class JsonWorkspaceRepository(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false },
) : WorkspaceRepository {
    private val dir = File(context.filesDir, "workspace")
    private val file = File(dir, "workspace.json")
    private val backup = File(dir, "workspace.json.bak")
    private val atomic = AtomicFile(file)
    private val mutex = Mutex()

    override suspend fun load(): WorkspaceData = withContext(Dispatchers.IO) {
        mutex.withLock {
            val candidates = buildList {
                if (file.isFile) add(file)
                if (backup.isFile) add(backup)
            }
            if (candidates.isEmpty()) return@withLock WorkspaceData()

            var lastError: Throwable? = null
            for (candidate in candidates) {
                try {
                    val decoded = candidate.bufferedReader().use {
                        json.decodeFromString(WorkspaceData.serializer(), it.readText())
                    }
                    if (candidate != file) writeLocked(decoded)
                    return@withLock decoded
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                    lastError = error
                    Log.e(TAG, "Unable to decode workspace candidate ${candidate.name}", error)
                }
            }

            quarantineCorruptFiles()
            Log.e(TAG, "Workspace could not be recovered; starting with an empty workspace", lastError)
            WorkspaceData()
        }
    }

    override suspend fun save(data: WorkspaceData) = withContext(Dispatchers.IO) {
        mutex.withLock { writeLocked(data) }
    }

    private fun writeLocked(data: WorkspaceData) {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create workspace directory" }
        val bytes = json.encodeToString(WorkspaceData.serializer(), data).toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun quarantineCorruptFiles() {
        val stamp = System.currentTimeMillis()
        listOf(file, backup).forEach { source ->
            if (!source.isFile) return@forEach
            val target = File(dir, "${source.name}.corrupt.$stamp")
            runCatching { source.renameTo(target) }
        }
    }

    private companion object {
        const val TAG = "YAutoWorkspace"
    }
}
