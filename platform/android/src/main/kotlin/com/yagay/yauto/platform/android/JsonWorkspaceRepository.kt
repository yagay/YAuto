package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class JsonWorkspaceRepository(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false },
) : WorkspaceRepository {
    private val dir = File(context.filesDir, "workspace")
    private val file = File(dir, "workspace.json")

    override suspend fun load(): WorkspaceData = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext WorkspaceData()
        runCatching { json.decodeFromString(WorkspaceData.serializer(), file.readText()) }.getOrElse { WorkspaceData() }
    }

    override suspend fun save(data: WorkspaceData) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "workspace.json.tmp")
        tmp.writeText(json.encodeToString(WorkspaceData.serializer(), data))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
