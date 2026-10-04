package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue

/**
 * Runtime-facing control surface for YAuto automations. Feature packs depend on this contract rather
 * than storage/runtime implementation details, keeping automation-control features reusable.
 */
interface AutomationControl {
    suspend fun run(
        target: String,
        variables: Map<String, ConfigValue> = emptyMap(),
        allowDisabled: Boolean = false,
    ): ActionExecutionResult

    suspend fun setEnabled(target: String, mode: AutomationEnableMode): ActionExecutionResult

    suspend fun cancel(target: String): ActionExecutionResult

    suspend fun isEnabled(target: String): Boolean?

    suspend fun isRunning(target: String): Boolean?

    suspend fun setCategoryEnabled(category: String, mode: AutomationEnableMode): ActionExecutionResult

    suspend fun isCategoryEnabled(category: String): Boolean?

    suspend fun lastRunEpochMs(target: String): Long?

    suspend fun setRuntimeEnabled(mode: AutomationEnableMode): ActionExecutionResult

    suspend fun isRuntimeEnabled(): Boolean

    suspend fun setTriggerEnabled(
        automation: String,
        triggerType: String,
        tag: String,
        mode: AutomationEnableMode,
    ): ActionExecutionResult

    suspend fun isTriggerEnabled(
        automation: String,
        triggerType: String,
        tag: String,
    ): Boolean?
}

enum class AutomationEnableMode {
    ENABLE,
    DISABLE,
    TOGGLE,
}
