package com.yagay.yauto.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.ui.design.R

/** Isolated test-centre dialogs; the home screen only owns navigation state. */
@Composable
internal fun HomeTestCenterDialogs(
    visible: Boolean,
    pendingAutomationId: String?,
    automations: List<Automation>,
    onDismiss: () -> Unit,
    onChooseAutomation: (String) -> Unit,
    onDismissConfirmation: () -> Unit,
    onTestAutomation: (String) -> Unit,
    onRunManual: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.test_center_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.test_center_description), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.test_center_saved_heading), style = MaterialTheme.typography.titleSmall)
                    if (automations.isEmpty()) {
                        Text(stringResource(R.string.test_center_no_automations), style = MaterialTheme.typography.bodySmall)
                    } else {
                        automations.forEach { automation ->
                            OutlinedButton(
                                onClick = { onChooseAutomation(automation.id.value) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(automation.name.ifBlank { automation.id.value }, maxLines = 1)
                            }
                        }
                    }
                    OutlinedButton(onClick = onRunManual, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.test_center_manual_run))
                    }
                    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.test_center_diagnostics))
                    }
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.test_center_permissions))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.test_center_close)) }
            },
        )
    }

    pendingAutomationId?.let { id ->
        AlertDialog(
            onDismissRequest = onDismissConfirmation,
            title = { Text(stringResource(R.string.test_center_confirm_title)) },
            text = { Text(stringResource(R.string.test_center_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { onTestAutomation(id) }) {
                    Text(stringResource(R.string.test_center_execute))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissConfirmation) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
