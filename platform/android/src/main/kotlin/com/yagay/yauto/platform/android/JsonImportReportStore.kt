package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.importer.ImportReportSink
import com.yagay.yauto.core.importer.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class JsonImportReportStore(
    context: Context,
    private val json: Json = Json { encodeDefaults = true; prettyPrint = true },
) : ImportReportSink {
    private val dir = File(context.filesDir, "import-reports").apply { mkdirs() }

    override suspend fun save(result: ImportResult) = withContext(Dispatchers.IO) {
        val file = File(dir, "import-${System.currentTimeMillis()}-${result.importerId}.json")
        file.writeText(json.encodeToString(ImportResult.serializer(), result))
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
    }
}
