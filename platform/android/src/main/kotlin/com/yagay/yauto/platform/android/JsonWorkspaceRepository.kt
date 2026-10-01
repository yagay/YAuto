package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JsonWorkspaceRepository(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false },
) : WorkspaceRepository {
    private val dir = File(context.filesDir, "workspace")
    private val file = File(dir, "workspace.json")
    private val atomic = AtomicFile(file)
    private val mutex = Mutex()

    override suspend fun load(): WorkspaceData = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists() && !File(dir, "workspace.json.bak").exists()) WorkspaceData()
            else atomic.openRead().bufferedReader().use { json.decodeFromString(WorkspaceData.serializer(), it.readText()) }
        }
    }

    override suspend fun save(data: WorkspaceData) = withContext(Dispatchers.IO) {
        mutex.withLock {
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
    }
}
