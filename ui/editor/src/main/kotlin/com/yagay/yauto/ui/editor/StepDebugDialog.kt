package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.ui.design.R as TextR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Interactive step tester for top-level, direct EVENT actions.
 * Each step is deliberately isolated through FeatureTestGateway.test().
 * Compound AST nodes are skipped, not flattened (flattening would change control flow).
 */
@Composable
internal fun StepDebugDialog(
    nodes: List<ActionNode>,
    descriptors: Map<String, FeatureDescriptor>,
    onDismiss: () -> Unit,
) {
    val tester = LocalFeatureTestGateway.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var cursor by remember(nodes) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var breakpoints by remember(nodes) { mutableStateOf(setOf<Int>()) }
    var report by remember(nodes) { mutableStateOf(mapOf<Int, FeatureTestResult>()) }
    var message by remember { mutableStateOf<String?>(null) }
    val actions = remember(nodes) { nodes.map { (it as? ActionNode.Action)?.feature } }
    val unsupported = stringResource(TextR.string.debug_compound_skipped)
    val reached = stringResource(TextR.string.debug_breakpoint_reached)

    suspend fun executeStep(index: Int): Boolean {
        val feature = actions[index]
        if (feature == null) {
            message = unsupported
            cursor = index + 1
            return true
        }
        val gateway = tester ?: return false
        val outcome = try {
            gateway.test(feature, FeatureKind.ACTION)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FeatureTestResult(
                success = false,
                detail = error.message ?: error.javaClass.simpleName,
                elapsedMs = 0,
                kind = FeatureKind.ACTION,
            )
        }
        report = report + (index to outcome)
        cursor = index + 1
        return outcome.success
    }

    fun runSteps(continuous: Boolean) {
        if (busy || !armed || cursor !in actions.indices || tester == null) return
        busy = true
        message = null
        scope.launch {
            try {
                var first = true
                while (cursor < actions.size) {
                    if (continuous && !first && cursor in breakpoints) {
                        message = reached
                        break
                    }
                    val index = cursor
                    val success = executeStep(index)
                    if (!continuous || !success) break
                    first = false
                }
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(TextR.string.debug_steps_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(TextR.string.debug_steps_warning),
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = armed, enabled = !busy, onCheckedChange = { armed = it })
                    Text(stringResource(TextR.string.debug_enable_real_actions),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(TextR.string.debug_progress, cursor.coerceAtMost(nodes.size), nodes.size))
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(modifier = Modifier.heightIn(max = 330.dp)) {
                    itemsIndexed(nodes) { index, node ->
                        val feature = actions[index]
                        Column {
                            Row {
                                Checkbox(
                                    checked = index in breakpoints,
                                    enabled = !busy,
                                    onCheckedChange = { checked ->
                                        breakpoints = if (checked) breakpoints + index else breakpoints - index
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(TextR.string.debug_step_label, index + 1,
                                        feature?.let { descriptors[it.typeId]?.let { descriptor -> descriptor.id.value } ?: it.typeId }
                                            ?: node.javaClass.simpleName),
                                        style = MaterialTheme.typography.bodyMedium)
                                    report[index]?.let {
                                        Text(stringResource(
                                            if (it.success) TextR.string.debug_step_success
                                            else TextR.string.debug_step_failed,
                                            it.elapsedMs, it.detail),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (feature == null) Text(unsupported, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && armed && cursor < nodes.size && tester != null,
                onClick = { runSteps(false) },
            ) { Text(stringResource(TextR.string.debug_next_step)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !busy && report.isNotEmpty(),
                    onClick = {
                        val summary = buildString {
                            appendLine("YAuto step debug report")
                            appendLine("Completed: ${report.size}/${nodes.size}")
                            report.toSortedMap().forEach { (index, result) ->
                                appendLine("Step ${index + 1} | ${actions[index]?.typeId ?: "compound"} | " +
                                    "${if (result.success) "PASS" else "FAIL"} | ${result.elapsedMs}ms")
                                appendLine(result.detail)
                                result.executionId?.let { appendLine("Trace: $it") }
                            }
                        }
                        clipboard.setText(AnnotatedString(summary))
                    },
                ) { Text(stringResource(TextR.string.debug_copy_report)) }
                TextButton(
                    enabled = !busy && armed && cursor < nodes.size && tester != null,
                    onClick = { runSteps(true) },
                ) { Text(stringResource(TextR.string.debug_run_to_breakpoint)) }
                TextButton(enabled = !busy, onClick = onDismiss) {
                    Text(stringResource(TextR.string.common_close))
                }
            }
        },
    )
}
