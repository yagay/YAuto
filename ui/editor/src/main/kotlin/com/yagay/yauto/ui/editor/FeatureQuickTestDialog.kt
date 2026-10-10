package com.yagay.yauto.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.ui.design.R as TextR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Context-menu test shared by triggers, states, constraints and actions.
 * This is separate from the configuration editor to support one-tap
 * MacroDroid-style test actions on existing tree items.
 */
@Composable
internal fun FeatureQuickTestDialog(
    feature: FeatureRef,
    kind: FeatureKind,
    onDismiss: () -> Unit,
) {
    val tester = LocalFeatureTestGateway.current
    val scope = rememberCoroutineScope()
    var running by remember(feature, kind) { mutableStateOf(false) }
    var result by remember(feature, kind) { mutableStateOf<FeatureTestResult?>(null) }
    val label = stringResource(when (kind) {
        FeatureKind.ACTION -> TextR.string.feature_test_action
        FeatureKind.CONDITION -> TextR.string.feature_test_constraint
        FeatureKind.STATE -> TextR.string.feature_test_state
        FeatureKind.EVENT -> TextR.string.feature_test_trigger
    })
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    running -> {
                        CircularProgressIndicator()
                        Text(stringResource(TextR.string.feature_test_running))
                    }
                    result != null -> {
                        val report = requireNotNull(result)
                        Text(
                            stringResource(if (report.success)
                                TextR.string.feature_test_success
                            else TextR.string.feature_test_failed),
                            fontWeight = FontWeight.SemiBold,
                            color = if (report.success) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                        )
                        Text(report.detail)
                        Text(stringResource(TextR.string.feature_test_duration,
                            report.elapsedMs))
                    }
                    kind == FeatureKind.ACTION -> Text(
                        stringResource(TextR.string.feature_test_action_warning),
                    )
                    kind == FeatureKind.EVENT -> Text(
                        stringResource(TextR.string.feature_test_event_check_only),
                    )
                    else -> Text(stringResource(TextR.string.feature_test_unsaved_warning))
                }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !running && tester != null,
                    onClick = {
                        val service = tester ?: return@TextButton
                        running = true
                        scope.launch {
                            try {
                                result = service.test(feature, kind)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                result = FeatureTestResult(false, error.message
                                    ?: error.javaClass.simpleName, 0L, kind = kind)
                            } finally {
                                running = false
                            }
                        }
                    },
                ) { Text(stringResource(TextR.string.feature_test_run_now)) }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(TextR.string.common_close))
                }
            }
        },
        dismissButton = {
            if (!running && result == null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(TextR.string.common_cancel))
                }
            }
        },
    )
}
