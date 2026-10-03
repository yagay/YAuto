package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue

data class PersistentVariableChange(
    val success: Boolean,
    val name: String,
    val previous: ConfigValue? = null,
    val current: ConfigValue? = null,
)

interface PersistentVariableControl {
    suspend fun get(name: String): ConfigValue?
    suspend fun set(name: String, value: ConfigValue): PersistentVariableChange
    suspend fun clear(name: String): PersistentVariableChange
}
