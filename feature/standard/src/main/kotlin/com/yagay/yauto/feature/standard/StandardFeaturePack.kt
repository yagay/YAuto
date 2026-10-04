package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.AutomationControl
import com.yagay.yauto.core.registry.AutomationEnableMode
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.PersistentVariableChange
import com.yagay.yauto.core.registry.PersistentVariableControl

object StandardFeaturePacks {
    fun all(
        control: AutomationControl = UnsupportedAutomationControl,
        persistentVariables: PersistentVariableControl = UnsupportedPersistentVariableControl,
    ): List<FeaturePack> = listOf(
        CoreFeaturePack(),
        ExpressionFeaturePack(),
        TaskerConditionFeaturePack(),
        AutomationControlFeaturePack(control),
        VariableFeaturePack(),
        VariableAdvancedFeaturePack(),
        PersistentVariableFeaturePack(persistentVariables),
        DataFeaturePack(),
        DataUtilityFeaturePack(),
        CollectionExpansionFeaturePack(),
        DataCodecFeaturePack(),
        JsonFeaturePack(),
        TimeFeaturePack(),
        TimeUtilityFeaturePack(),
        PrivilegedAndroidFeaturePack(),
        PrivilegedUtilityFeaturePack(),
        SystemFeaturePack(),
    )

    private object UnsupportedAutomationControl : AutomationControl {
        override suspend fun run(
            target: String,
            variables: Map<String, ConfigValue>,
            allowDisabled: Boolean,
        ): ActionExecutionResult = ActionExecutionResult(false)

        override suspend fun setEnabled(target: String, mode: AutomationEnableMode): ActionExecutionResult =
            ActionExecutionResult(false)

        override suspend fun cancel(target: String): ActionExecutionResult = ActionExecutionResult(false)

        override suspend fun isEnabled(target: String): Boolean? = null

        override suspend fun isRunning(target: String): Boolean? = null

        override suspend fun setCategoryEnabled(
            category: String,
            mode: AutomationEnableMode,
        ): ActionExecutionResult = ActionExecutionResult(false)

        override suspend fun isCategoryEnabled(category: String): Boolean? = null

        override suspend fun lastRunEpochMs(target: String): Long? = null

        override suspend fun setRuntimeEnabled(mode: AutomationEnableMode): ActionExecutionResult =
            ActionExecutionResult(false)

        override suspend fun isRuntimeEnabled(): Boolean = false

        override suspend fun setTriggerEnabled(
            automation: String,
            triggerType: String,
            tag: String,
            mode: AutomationEnableMode,
        ): ActionExecutionResult = ActionExecutionResult(false)

        override suspend fun isTriggerEnabled(
            automation: String,
            triggerType: String,
            tag: String,
        ): Boolean? = null
    }

    private object UnsupportedPersistentVariableControl : PersistentVariableControl {
        override suspend fun get(name: String): ConfigValue? = null

        override suspend fun set(name: String, value: ConfigValue): PersistentVariableChange =
            PersistentVariableChange(false, name)

        override suspend fun clear(name: String): PersistentVariableChange =
            PersistentVariableChange(false, name)
    }
}
