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
}

enum class AutomationEnableMode {
    ENABLE,
    DISABLE,
    TOGGLE,
}
