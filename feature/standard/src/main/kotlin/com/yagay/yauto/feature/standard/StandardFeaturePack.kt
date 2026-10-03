package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.AutomationControl
import com.yagay.yauto.core.registry.AutomationEnableMode
import com.yagay.yauto.core.registry.FeaturePack

object StandardFeaturePacks {
    fun all(control: AutomationControl = UnsupportedAutomationControl): List<FeaturePack> = listOf(
        CoreFeaturePack(),
        AutomationControlFeaturePack(control),
        VariableFeaturePack(),
        DataFeaturePack(),
        DataUtilityFeaturePack(),
        JsonFeaturePack(),
        TimeFeaturePack(),
        TimeUtilityFeaturePack(),
        PrivilegedAndroidFeaturePack(),
        PrivilegedUtilityFeaturePack(),
        SystemFeaturePack(),
        CompatibilityFeaturePack(),
    )

    private object UnsupportedAutomationControl : AutomationControl {
        override suspend fun run(
            target: String,
            variables: Map<String, com.yagay.yauto.core.model.ConfigValue>,
            allowDisabled: Boolean,
        ): ActionExecutionResult = ActionExecutionResult(false)

        override suspend fun setEnabled(target: String, mode: AutomationEnableMode): ActionExecutionResult =
            ActionExecutionResult(false)

        override suspend fun cancel(target: String): ActionExecutionResult = ActionExecutionResult(false)

        override suspend fun isEnabled(target: String): Boolean? = null
    }
}
