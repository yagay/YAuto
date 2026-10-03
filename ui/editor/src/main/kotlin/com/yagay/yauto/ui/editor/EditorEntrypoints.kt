package com.yagay.yauto.ui.editor

import androidx.compose.runtime.Composable
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.Flow
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind

/** Public YAuto editor entrypoints. Legacy Macro-prefixed implementations stay internal to the UI module. */
@Composable
fun AutomationEditorScreen(
    descriptors: List<FeatureDescriptor>,
    initial: Automation? = null,
    onSave: (Automation) -> Unit,
    onBack: () -> Unit,
    flows: List<Flow> = emptyList(),
) = MacroAutomationEditorScreen(
    descriptors = descriptors,
    initial = initial,
    onSave = onSave,
    onBack = onBack,
    flows = flows,
)

@Composable
fun FlowEditorScreen(
    initial: Flow? = null,
    descriptors: List<FeatureDescriptor>,
    flows: List<Flow>,
    onSave: (Flow) -> Unit,
    onBack: () -> Unit,
) = MacroFlowEditorScreen(
    initial = initial,
    descriptors = descriptors,
    flows = flows,
    onSave = onSave,
    onBack = onBack,
)

@Composable
fun FeaturePickerDialog(
    kind: FeatureKind,
    descriptors: List<FeatureDescriptor>,
    initial: FeatureRef? = null,
    onDismiss: () -> Unit,
    onPick: (FeatureRef) -> Unit,
) = MacroFeaturePickerDialog(
    kind = kind,
    descriptors = descriptors,
    initial = initial,
    onDismiss = onDismiss,
    onPick = onPick,
)
