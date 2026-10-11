package com.yagay.yauto

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.storage.WorkspaceBackupCodec
import com.yagay.yauto.core.storage.WorkspaceRestoreMode
import com.yagay.yauto.core.storage.restoreBackup
import com.yagay.yauto.core.registry.AutomationEnableMode
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.ui.design.R as TextR
import kotlinx.coroutines.launch

/** Dedicated backup transport: all network I/O is off the main thread. */
@Composable
internal fun ShortXWebDavScreen(context: Context, graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var directory by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<WebDavRestorePreview?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<WorkspaceRestoreMode?>(null) }
    val saved = stringResource(TextR.string.sx_saved)
    val failed = stringResource(TextR.string.sx_webdav_failed)
    val restored = stringResource(TextR.string.sx_webdav_restored)
    val downloaded = stringResource(TextR.string.sx_webdav_downloaded)
    LaunchedEffect(Unit) {
        runCatching { ShortXWebDav.load(context) }.onSuccess { profile ->
            directory = profile.directory
            username = profile.username
            password = profile.password
        }.onFailure { message = failed }
        loaded = true
    }
    fun profile() = WebDavSettings(directory, username, password)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(TextR.string.sx_webdav_hint), style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(directory, { directory = it.take(700) },
                label = { Text(stringResource(TextR.string.sx_webdav_url)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(username, { username = it.take(150) },
                label = { Text(stringResource(TextR.string.sx_webdav_user)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(password, { password = it.take(4000) },
                label = { Text(stringResource(TextR.string.sx_webdav_password)) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    message = runCatching { ShortXWebDav.save(context, profile()); saved }
                        .getOrElse { failed }
                }, enabled = loaded && !uploading && !restoring) {
                    Text(stringResource(TextR.string.sx_save))
                }
                OutlinedButton(onClick = {
                    ShortXWebDav.forget(context)
                    directory = ""; username = ""; password = ""; preview = null
                    message = saved
                }, enabled = !uploading && !restoring) { Text(stringResource(TextR.string.sx_webdav_forget)) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    uploading = true
                    message = null
                    scope.launch {
                        message = runCatching {
                            val snapshot = graph.workspace.load()
                            val workspaceJson = WorkspaceBackupCodec.encode(snapshot, "YAuto")
                            val libraryJson = RuntimeLibraryStore.exportJson(context)
                            ShortXWebDav.push(profile(), workspaceJson, libraryJson)
                            saved
                        }.getOrElse { failed + ": " + (it.message ?: "") }
                        uploading = false
                    }
                }, enabled = loaded && !uploading && !restoring) {
                    Text(stringResource(TextR.string.sx_webdav_upload))
                }
                OutlinedButton(onClick = {
                    uploading = true
                    message = null
                    scope.launch {
                        preview = runCatching { ShortXWebDav.pull(profile()) }.getOrElse {
                            message = failed + ": " + (it.message ?: "")
                            null
                        }
                        if (preview != null) message = downloaded
                        uploading = false
                    }
                }, enabled = loaded && !uploading && !restoring) {
                    Text(stringResource(TextR.string.sx_webdav_download))
                }
            }
        }
        if (preview != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val backup = remember(preview) { WorkspaceBackupCodec.decode(preview!!.workspace) }
                        Text(stringResource(TextR.string.sx_webdav_preview, backup.workspace.automations.size,
                            backup.workspace.flows.size), fontWeight = FontWeight.Medium)
                        Text(stringResource(TextR.string.sx_webdav_confirm_hint), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { confirm = WorkspaceRestoreMode.MERGE }, enabled = !restoring) {
                                Text(stringResource(TextR.string.sx_webdav_merge))
                            }
                            Button(onClick = { confirm = WorkspaceRestoreMode.REPLACE }, enabled = !restoring) {
                                Text(stringResource(TextR.string.sx_webdav_replace))
                            }
                        }
                    }
                }
            }
        }
        item { message?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
    confirm?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(TextR.string.sx_webdav_restore_title)) },
            text = { Text(stringResource(TextR.string.sx_webdav_confirm_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    val data = preview ?: return@TextButton
                    confirm = null
                    restoring = true
                    scope.launch {
                        message = runCatching {
                            val decoded = WorkspaceBackupCodec.decode(data.workspace)
                            val current = graph.workspace.load()
                            // Cancel running work before replacing live activation rules.
                            graph.runtime.setRuntimeEnabled(AutomationEnableMode.DISABLE)
                            graph.workspace.save(current.restoreBackup(decoded.workspace, mode))
                            graph.runtime.resetState()
                            graph.runtime.dispatch(RuntimeEvent("android.event.workspace_changed", source = "ui.webdav"), statesOnly = true)
                            val imported = RuntimeLibraryStore.importJson(context, data.library)
                            preview = null
                            "$restored ($imported)"
                        }.getOrElse { failed + ": " + (it.message ?: "") }
                        restoring = false
                    }
                }) { Text(stringResource(TextR.string.sx_webdav_restore_title)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(TextR.string.common_cancel))
                }
            },
        )
    }
}
