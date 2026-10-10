package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.ui.design.R as TextR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun flattenDebugNodes(nodes: List<ActionNode>): List<ActionNode> = buildList {
    fun visit(node: ActionNode) {
        add(node)
        val children: List<ActionNode> = when (node) {
            is ActionNode.If -> node.thenActions + node.elseActions
            is ActionNode.Switch -> node.cases.flatMap { it.actions } + node.defaultActions
            is ActionNode.Repeat -> node.actions
            is ActionNode.While -> node.actions
            is ActionNode.DoWhile -> node.actions
            is ActionNode.ForEach -> node.actions
            is ActionNode.Parallel -> node.branches.flatten()
            is ActionNode.Try -> node.actions + node.onError + node.finallyActions
            else -> emptyList()
        }
        children.forEach(::visit)
    }
    nodes.forEach(::visit)
}

/** Real engine-backed debugging. Branch/loop children are reached through normal engine control flow. */
@Composable
internal fun EngineDebugDialog(automation: Automation, onDismiss: () -> Unit) {
    val gateway = LocalFeatureTestGateway.current
    val session = remember(gateway, automation) { gateway?.newDebugRun(automation) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val nodes = remember(automation) { flattenDebugNodes(automation.onEvent) }
    val ids = remember(nodes) { nodes.map { it.id.value }.toSet() }
    var job by remember { mutableStateOf<Job?>(null) }
    var armed by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(EngineDebugSnapshotUi(null, emptyList())) }
    var result by remember { mutableStateOf<FeatureTestResult?>(null) }
    var breakpoints by remember(automation.id) {
        mutableStateOf(gateway?.savedDebugBreakpoints(automation.id.value).orEmpty().intersect(ids))
    }
    val active = job?.isActive == true
    val currentJob by rememberUpdatedState(job)

    LaunchedEffect(session, active) {
        while (active && session != null) {
            report = session.snapshot()
            delay(150)
        }
        session?.let { report = it.snapshot() }
    }
    DisposableEffect(session) { onDispose { currentJob?.cancel() } }

    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        title = { Text(stringResource(TextR.string.engine_debug_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(TextR.string.engine_debug_warning), style = MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(checked = armed, enabled = !active, onCheckedChange = { armed = it })
                    Text(stringResource(TextR.string.engine_debug_allow))
                }
                Text(stringResource(TextR.string.engine_debug_paused, report.pausedId ?: "—"))
                LazyColumn(Modifier.heightIn(max = 310.dp)) {
                    items(nodes) { node ->
                        val id = node.id.value
                        Row {
                            Checkbox(
                                checked = id in breakpoints,
                                onCheckedChange = { checked ->
                                    val next = if (checked) breakpoints + id else breakpoints - id
                                    breakpoints = next
                                    gateway?.saveDebugBreakpoints(automation.id.value, next)
                                    scope.launch { session?.setBreakpoint(id, checked) }
                                },
                            )
                            Text(stringResource(TextR.string.engine_debug_breakpoint, node.javaClass.simpleName, id))
                        }
                    }
                    items(report.steps) { step ->
                        Column {
                            Text(stringResource(TextR.string.engine_debug_step,
                                step.kind, step.id,
                                stringResource(if (step.success) TextR.string.feature_test_success else TextR.string.feature_test_failed),
                                step.elapsedMs))
                            Text(stringResource(TextR.string.engine_debug_before, step.before),
                                style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(TextR.string.engine_debug_after, step.after),
                                style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                }
                result?.let { Text(stringResource(TextR.string.engine_debug_result, it.detail, it.elapsedMs)) }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = armed && !active && result == null && session != null,
                    onClick = {
                        val run = session ?: return@TextButton
                        job = scope.launch {
                            try {
                                breakpoints.forEach { run.setBreakpoint(it, true) }
                                result = run.execute()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                result = FeatureTestResult(false, error.message ?: error.javaClass.simpleName,
                                    0L, kind = com.yagay.yauto.core.registry.FeatureKind.ACTION)
                            } finally {
                                report = run.snapshot()
                            }
                        }
                    },
                ) { Text(stringResource(TextR.string.engine_debug_start)) }
                TextButton(enabled = active && report.pausedId != null,
                    onClick = { scope.launch { session?.step() } }) {
                    Text(stringResource(TextR.string.engine_debug_next))
                }
                TextButton(enabled = active && report.pausedId != null,
                    onClick = { scope.launch { session?.resume() } }) {
                    Text(stringResource(TextR.string.engine_debug_continue))
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(enabled = report.steps.isNotEmpty(), onClick = {
                    val details = buildString {
                        appendLine("YAuto Engine Debug — ${automation.name}")
                        result?.let { appendLine("Result: ${it.detail}; Trace: ${it.executionId}") }
                        report.steps.forEachIndexed { index, step ->
                            appendLine("${index + 1}. ${step.kind} ${step.id} ${if (step.success) "PASS" else "FAIL"} ${step.elapsedMs}ms")
                            appendLine("Before: ${step.before}")
                            appendLine("After: ${step.after}")
                        }
                    }
                    clipboard.setText(AnnotatedString(details))
                }) { Text(stringResource(TextR.string.debug_copy_report)) }
                TextButton(onClick = { job?.cancel(); onDismiss() }) {
                    Text(stringResource(TextR.string.common_close))
                }
            }
        },
    )
}
