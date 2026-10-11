package com.yagay.yauto

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.platform.accessibility.AccessibilityOperations
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.ui.design.R as TextR
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object ShortXAudioRecords {
    fun list(context: Context): List<File> {
        val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "Recordings")
        return folder.listFiles().orEmpty().filter { it.isFile && it.extension.equals("m4a", true) }
            .sortedByDescending { it.lastModified() }.take(500)
    }
    fun safeRecord(context: Context, file: File): Boolean {
        val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "Recordings")
        return runCatching { file.isFile && file.canonicalFile.parentFile == folder.canonicalFile &&
            file.extension.equals("m4a", true) }.getOrDefault(false)
    }
}

@Composable
internal fun ShortXAudioRecordsScreen(context: Context) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(ShortXAudioRecords.list(context)) }
    var target by remember { mutableStateOf<File?>(null) }
    var exportTarget by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val failed = stringResource(TextR.string.sx_error)
    val saved = stringResource(TextR.string.sx_saved)
    DisposableEffect(Unit) { onDispose { runCatching { player?.release() } } }
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4")
    ) { uri ->
        val file = exportTarget
        if (uri != null && file != null) scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) {
                    require(ShortXAudioRecords.safeRecord(context, file))
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                }
                saved
            }.getOrElse { failed }
        }
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(TextR.string.sx_audio_records_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { records = ShortXAudioRecords.list(context) }) {
                Text(stringResource(TextR.string.sx_records_refresh))
            }
            message?.let { Text(it) }
        }
        items(records, key = { it.absolutePath }) { file ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(file.name, fontWeight = FontWeight.Medium)
                    Text(stringResource(TextR.string.sx_bytes, file.length()), style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            runCatching { player?.release() }
                            player = null
                            message = runCatching {
                                require(ShortXAudioRecords.safeRecord(context, file))
                                MediaPlayer().apply {
                                    setDataSource(file.absolutePath)
                                    setOnCompletionListener { it.release(); player = null }
                                    prepare()
                                    start()
                                }.also { player = it }
                                saved
                            }.getOrElse { failed }
                        }) { Text(stringResource(TextR.string.sx_play)) }
                        TextButton(onClick = {
                            exportTarget = file
                            export.launch(file.name)
                        }) { Text(stringResource(TextR.string.sx_export_library)) }
                        TextButton(onClick = { target = file }) { Text(stringResource(TextR.string.sx_delete)) }
                    }
                }
            }
        }
    }
    target?.let { file ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(stringResource(TextR.string.sx_delete)) },
            text = { Text(stringResource(TextR.string.sx_confirm_delete)) },
            confirmButton = { TextButton(onClick = {
                if (ShortXAudioRecords.safeRecord(context, file)) {
                    runCatching { player?.stop(); player?.release() }
                    player = null
                    file.delete()
                }
                records = ShortXAudioRecords.list(context)
                target = null
            }) { Text(stringResource(TextR.string.sx_delete)) } },
            dismissButton = { TextButton(onClick = { target = null }) {
                Text(stringResource(TextR.string.common_cancel))
            } },
        )
    }
}

@Composable
internal fun ShortXDanmuSettingsScreen(context: Context, graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var size by remember { mutableFloatStateOf(22f) }
    var duration by remember { mutableFloatStateOf(8f) }
    var color by remember { mutableStateOf("#FFFFFF") }
    var location by remember { mutableStateOf("top") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(TextR.string.sx_overlay_required)
    val saved = stringResource(TextR.string.sx_saved)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(TextR.string.sx_danmu_hint), style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(content, { content = it.take(300) },
                label = { Text(stringResource(TextR.string.sx_content)) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(color, { color = it.take(9) },
                label = { Text(stringResource(TextR.string.sx_danmu_color)) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Text(stringResource(TextR.string.sx_danmu_size, size.toInt()))
            Slider(value = size, onValueChange = { size = it }, valueRange = 8f..72f)
            Text(stringResource(TextR.string.sx_danmu_duration, duration.toInt()))
            Slider(value = duration, onValueChange = { duration = it }, valueRange = 1f..60f)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("top", "center", "bottom").forEach { position ->
                    FilterChip(selected = location == position, onClick = { location = position },
                        label = { Text(position) })
                }
            }
        }
        item {
            Button(onClick = {
                scope.launch {
                    val tint = runCatching { android.graphics.Color.parseColor(color) }.getOrNull()
                    feedback = if (tint == null || content.isBlank()) failed
                    else if (graph.overlaySurfaces.showDanmu("shortx.manager.danmu", content,
                            tint, size, (duration * 1000).toLong(), location)) saved else failed
                }
            }) { Text(stringResource(TextR.string.sx_preview_danmu)) }
            feedback?.let { Text(it) }
        }
    }
}

@Composable
internal fun ShortXGestureLibraryScreen(context: Context, graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(RuntimeLibraryStore.list(context).filter { it.kind == "gesture" }) }
    var name by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val saved = stringResource(TextR.string.sx_saved)
    val failed = stringResource(TextR.string.sx_error)
    val id = "shortx.manager.gesture"

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(TextR.string.sx_gesture_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it.take(80) },
                label = { Text(stringResource(TextR.string.sx_name)) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    scope.launch {
                        feedback = if (graph.overlaySurfaces.showGestureRecorder(id, 1024, 0L))
                            saved else failed
                    }
                }) { Text(stringResource(TextR.string.sx_record_start)) }
                Button(onClick = {
                    scope.launch {
                        graph.overlaySurfaces.stopGestureRecorder(id)
                        val path = graph.overlaySurfaces.recordedGesture(id).orEmpty()
                        feedback = if (path.isNotBlank() && name.isNotBlank() &&
                            RuntimeLibraryStore.save(context, LibraryEntry("", "gesture", name, path))) {
                            records = RuntimeLibraryStore.list(context).filter { it.kind == "gesture" }
                            saved
                        } else failed
                    }
                }) { Text(stringResource(TextR.string.sx_record_save)) }
            }
            feedback?.let { Text(it) }
        }
        items(records, key = { it.id }) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.name, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                val result = graph.capabilities.execute(
                                    CapabilityRequest(
                                        capability = CapabilityIds.ACCESSIBILITY,
                                        operationId = AccessibilityOperations.GESTURE_PATH,
                                        payload = mapOf(
                                            "path" to ConfigValue.StringValue(entry.body),
                                            "durationMs" to ConfigValue.NumberValue(750.0),
                                        ),
                                        preferredBackendId = "accessibility",
                                    )
                                )
                                feedback = if (result.success) saved else result.message ?: failed
                            }
                        }) { Text(stringResource(TextR.string.sx_gesture_play)) }
                        TextButton(onClick = {
                            RuntimeLibraryStore.delete(context, entry.id)
                            records = RuntimeLibraryStore.list(context).filter { it.kind == "gesture" }
                        }) { Text(stringResource(TextR.string.sx_delete)) }
                    }
                }
            }
        }
    }
}
