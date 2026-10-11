package com.yagay.yauto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.registry.AutomationEnableMode
import com.yagay.yauto.platform.root.RootShell
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.design.YAutoAppearanceSettings
import kotlinx.coroutines.launch

@Composable
internal fun ShortXEngineSettingsScreen(context: Context, graph: AppGraph, onLibraries: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rootShared by remember { mutableStateOf(RuntimeSettingsPreferences.sharedRootQueries(context)) }
    var emergency by remember { mutableStateOf(RuntimeSettingsPreferences.emergencyPowerKey(context)) }
    var keyPrompt by remember { mutableStateOf(RuntimeSettingsPreferences.keyEventPrompt(context)) }
    var ocrEnabled by remember { mutableStateOf(RuntimeSettingsPreferences.ocrEngineEnabled(context)) }
    var smsEnabled by remember { mutableStateOf(RuntimeSettingsPreferences.smsEngineEnabled(context)) }
    var qsOpt by remember { mutableStateOf(RuntimeSettingsPreferences.qsClickOptimization(context)) }
    var executable by remember { mutableStateOf(RuntimeSettingsPreferences.suBinary(context)) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var runtimeEnabled by remember {
        mutableStateOf(!RuntimeEmergencyState.isPaused() &&
            (graph.workspace.snapshotOrNull()?.runtimeEnabled ?: true))
    }
    DisposableEffect(graph.workspace) {
        val listener = graph.workspace.addListener { data ->
            scope.launch { runtimeEnabled = data.runtimeEnabled && !RuntimeEmergencyState.isPaused() }
        }
        onDispose { listener.close() }
    }
    var pointerEnabled by remember { mutableStateOf<Boolean?>(null) }
    var systemUiCommand by remember { mutableStateOf("id") }
    var systemUiOutput by remember { mutableStateOf<String?>(null) }
    val saved = stringResource(TextR.string.sx_saved)
    val failed = stringResource(TextR.string.sx_error)

    LaunchedEffect(Unit) {
        val status = graph.rootShell.run("settings get system pointer_location", 3_000)
        pointerEnabled = if (status.exitCode == 0) status.stdout.trim() == "1" else null
    }

    fun notifyService() {
        context.startService(Intent(context, AutomationRuntimeService::class.java)
            .setAction(AutomationRuntimeService.ACTION_REFRESH_ENGINE_SETTINGS))
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Text(stringResource(TextR.string.sx_engine_tools), fontWeight = FontWeight.SemiBold)
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.sx_master_rules),
                stringResource(TextR.string.sx_master_rules_hint),
                runtimeEnabled,
            ) { value ->
                scope.launch {
                    val result = graph.runtime.setRuntimeEnabled(
                        if (value) AutomationEnableMode.ENABLE else AutomationEnableMode.DISABLE
                    )
                    if (result.success) {
                        if (value) RuntimeEmergencyState.resume(context)
                        runtimeEnabled = value
                        feedback = saved
                    } else feedback = result.message ?: failed
                }
            }
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.sx_emergency),
                stringResource(TextR.string.sx_emergency_hint),
                emergency,
            ) { value ->
                RuntimeSettingsPreferences.setEmergencyPowerKey(context, value)
                emergency = value
                notifyService()
            }
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.sx_key_prompt),
                stringResource(TextR.string.sx_key_prompt_hint),
                keyPrompt,
            ) { value ->
                RuntimeSettingsPreferences.setKeyEventPrompt(context, value)
                keyPrompt = value
                notifyService()
            }
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.sx_ocr_engine),
                stringResource(TextR.string.sx_ocr_engine_hint),
                ocrEnabled,
            ) { value ->
                RuntimeSettingsPreferences.setOcrEngineEnabled(context, value)
                ocrEnabled = value
            }
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.sx_sms_engine),
                stringResource(TextR.string.sx_sms_engine_hint),
                smsEnabled,
            ) { value ->
                RuntimeSettingsPreferences.setSmsEngineEnabled(context, value)
                smsEnabled = value
            }
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.sx_qs_opt),
                stringResource(TextR.string.sx_qs_opt_hint),
                qsOpt,
            ) { value ->
                RuntimeSettingsPreferences.setQsClickOptimization(context, value)
                qsOpt = value
            }
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.sx_shared_shell),
                stringResource(TextR.string.sx_shared_shell_hint),
                rootShared,
            ) { value ->
                RuntimeSettingsPreferences.setSharedRootQueries(context, value)
                rootShared = value
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(TextR.string.sx_custom_su), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(TextR.string.sx_custom_su_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = executable,
                        onValueChange = { executable = it },
                        label = { Text(stringResource(TextR.string.sx_su_path)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        if (RootShell.isSafeSuBinary(executable)) {
                            RuntimeSettingsPreferences.setSuBinary(context, executable)
                            feedback = saved
                        } else feedback = failed
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(TextR.string.sx_save_path))
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(TextR.string.sx_systemui_shell), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(TextR.string.sx_systemui_shell_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = systemUiCommand,
                        onValueChange = { systemUiCommand = it.take(150) },
                        label = { Text(stringResource(TextR.string.sx_systemui_query)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        scope.launch {
                            val (success, output) = graph.xposed.querySystemUi(systemUiCommand)
                            systemUiOutput = output
                            if (!success) feedback = failed
                        }
                    }) { Text(stringResource(TextR.string.sx_systemui_run)) }
                    systemUiOutput?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(TextR.string.sx_pointer_location), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(TextR.string.sx_pointer_location_hint), style = MaterialTheme.typography.bodySmall)
                    val label = stringResource(TextR.string.sx_pointer_error)
                    Switch(
                        checked = pointerEnabled == true,
                        enabled = pointerEnabled != null,
                        onCheckedChange = { desired ->
                            scope.launch {
                                if (!graph.rootShell.isAvailable()) {
                                    feedback = label
                                } else {
                                    val output = graph.rootShell.run(
                                        "settings put system pointer_location " + if (desired) "1" else "0", 5_000
                                    )
                                    if (output.exitCode == 0 && !output.timedOut) {
                                        pointerEnabled = desired
                                        feedback = saved
                                    } else feedback = label
                                }
                            }
                        },
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = onLibraries, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(TextR.string.sx_libraries))
            }
        }
        if (RuntimeEmergencyState.isPaused()) {
            item {
                Text(stringResource(TextR.string.sx_latched), color = MaterialTheme.colorScheme.error)
            }
        }
        item { feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
internal fun ShortXLibrarySettingsScreen(context: Context) {
    var items by remember { mutableStateOf(RuntimeLibraryStore.list(context)) }
    var kind by remember { mutableStateOf("shell") }
    var editingId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val saved = stringResource(TextR.string.sx_saved)
    val failed = stringResource(TextR.string.sx_error)
    val imported = stringResource(TextR.string.sx_imported)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            message = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(RuntimeLibraryStore.exportJson(context)) }
                    ?: error("No output stream")
                saved
            }.getOrElse { failed }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            message = runCatching {
                val content = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { reader ->
                        val out = StringBuilder()
                        val buffer = CharArray(4096)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            require(out.length + n <= 1_000_000)
                            out.append(buffer, 0, n)
                        }
                        out.toString()
                    } ?: error("No input stream")
                RuntimeLibraryStore.importJson(context, content)
                items = RuntimeLibraryStore.list(context)
                imported
            }.getOrElse { failed }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(stringResource(TextR.string.sx_library_hint), style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    exportLauncher.launch("YAuto-library.json")
                }) { Text(stringResource(TextR.string.sx_export_library)) }
                OutlinedButton(onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain"))
                }) { Text(stringResource(TextR.string.sx_import_library)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("shell", "javascript", "apps").forEach { option ->
                    FilterChip(selected = kind == option, onClick = {
                        kind = option; editingId = ""; name = ""; body = ""
                    }, label = { Text(option) })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("mvel", "beanshell").forEach { option ->
                    FilterChip(selected = kind == option, onClick = {
                        kind = option; editingId = ""; name = ""; body = ""
                    }, label = { Text(option) })
                }
            }
        }
        items(items.filter { it.kind == kind }.size) { index ->
            val entry = items.filter { it.kind == kind }[index]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(entry.name, fontWeight = FontWeight.SemiBold)
                    Text(entry.body.take(120), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText(entry.name, entry.body))
                        }) { Text(stringResource(TextR.string.sx_copy)) }
                        TextButton(onClick = {
                            editingId = entry.id; name = entry.name; body = entry.body
                        }) { Text(stringResource(TextR.string.sx_edit)) }
                        TextButton(onClick = { deleteId = entry.id }) {
                            Text(stringResource(TextR.string.sx_delete))
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(TextR.string.sx_library_editor), fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(TextR.string.sx_name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = body, onValueChange = { body = it },
                        label = { Text(if (kind == "apps") stringResource(TextR.string.sx_app_packages)
                            else stringResource(TextR.string.sx_content)) },
                        minLines = 4, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            if (RuntimeLibraryStore.save(context, LibraryEntry(editingId, kind, name, body))) {
                                items = RuntimeLibraryStore.list(context)
                                editingId = ""; name = ""; body = ""
                                message = saved
                            } else message = failed
                        }) { Text(stringResource(TextR.string.sx_save)) }
                        TextButton(onClick = {
                            editingId = ""; name = ""; body = ""
                        }) { Text(stringResource(TextR.string.sx_clear)) }
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    deleteId?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(TextR.string.sx_delete)) },
            text = { Text(stringResource(TextR.string.sx_confirm_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    RuntimeLibraryStore.delete(context, target)
                    items = RuntimeLibraryStore.list(context)
                    deleteId = null
                }) { Text(stringResource(TextR.string.sx_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) {
                Text(stringResource(TextR.string.common_cancel))
            } },
        )
    }
}

@Composable
internal fun ShortXAppearanceSettingsScreen(context: Context) {
    var mode by remember { mutableStateOf(YAutoAppearanceSettings.mode(context)) }
    var dynamic by remember { mutableStateOf(YAutoAppearanceSettings.dynamic(context)) }
    var colorful by remember { mutableStateOf(YAutoAppearanceSettings.colorful(context)) }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(TextR.string.sx_theme_mode), fontWeight = FontWeight.SemiBold)
                    listOf(
                        "system" to TextR.string.sx_theme_system,
                        "light" to TextR.string.sx_theme_light,
                        "dark" to TextR.string.sx_theme_dark,
                    ).forEach { (choice, label) ->
                        Row {
                            RadioButton(selected = mode == choice, onClick = {
                                mode = choice
                                YAutoAppearanceSettings.setMode(context, choice)
                            })
                            TextButton(onClick = {
                                mode = choice
                                YAutoAppearanceSettings.setMode(context, choice)
                            }) { Text(stringResource(label)) }
                        }
                    }
                }
            }
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.sx_dynamic_color),
                stringResource(TextR.string.sx_dynamic_color_hint),
                dynamic,
            ) {
                dynamic = it
                YAutoAppearanceSettings.setDynamic(context, it)
            }
        }
        item {
            SettingsToggleCard(
                stringResource(TextR.string.sx_colorful_card),
                stringResource(TextR.string.sx_colorful_card_hint),
                colorful,
            ) {
                colorful = it
                YAutoAppearanceSettings.setColorful(context, it)
            }
        }
    }
}
