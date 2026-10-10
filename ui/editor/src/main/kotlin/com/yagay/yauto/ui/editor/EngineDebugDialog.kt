package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.ActionNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Live AST debugger: nested nodes share the engine's execution context. */
@Composable
internal fun EngineDebugDialog(automation: Automation, onDismiss: () -> Unit) {
    val gateway = LocalFeatureTestGateway.current
    val session = remember(automation) { gateway?.newDebugRun(automation) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var armed by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(EngineDebugSnapshotUi(null, emptyList())) }
    var result by remember { mutableStateOf<FeatureTestResult?>(null) }
    var breakpoints by remember { mutableStateOf(setOf<String>()) }
    val ids = remember(automation) { automation.onEvent.map { it.id.value } }
    val active = job?.isActive == true

    LaunchedEffect(session, active) {
        while (active && session != null) {
            report = session.snapshot()
            delay(150)
        }
        session?.let { report = it.snapshot() }
    }
    DisposableEffect(session) {
        onDispose { job?.cancel() }
    }
    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        title = { Text("Engine step debugger") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live actions will modify your device. Debugging starts paused before the first node.")
                Row {
                    Checkbox(checked = armed, enabled = !active, onCheckedChange = { armed = it })
                    Text("Allow real actions")
                }
                Text("Paused at: ${report.pausedId ?: "—"}")
                LazyColumn(Modifier.heightIn(max = 310.dp)) {
                    items(ids) { id ->
                        Row {
                            Checkbox(
                                checked = id in breakpoints,
                                onCheckedChange = { checked ->
                                    breakpoints = if (checked) breakpoints + id else breakpoints - id
                                    scope.launch { session?.setBreakpoint(id, checked) }
                                },
                            )
                            Text("Breakpoint: $id")
                        }
                    }
                    items(report.steps) { step ->
                        Column {
                            Text("${step.kind} [${step.id}] ${if (step.success) "PASS" else "FAIL"} — ${step.elapsedMs} ms")
                            Text("Before: ${step.before}", style = MaterialTheme.typography.bodySmall)
                            Text("After: ${step.after}", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                }
                result?.let { Text("Result: ${it.detail} (${it.elapsedMs} ms)") }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = armed && !active && session != null,
                    onClick = {
                        val run = session ?: return@TextButton
                        job = scope.launch { result = run.execute() }
                    },
                ) { Text("Start paused") }
                TextButton(
                    enabled = active && report.pausedId != null,
                    onClick = { scope.launch { session?.step() } },
                ) { Text("Next step") }
                TextButton(
                    enabled = active && report.pausedId != null,
                    onClick = { scope.launch { session?.resume() } },
                ) { Text("Continue") }
            }
        },
        dismissButton = {
            TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("Close") }
        },
    )
}
