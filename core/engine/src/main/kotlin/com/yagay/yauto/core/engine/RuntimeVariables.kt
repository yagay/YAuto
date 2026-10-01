package com.yagay.yauto.core.engine

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.VariableAccess
import java.util.concurrent.ConcurrentHashMap

class RuntimeVariables(initial: Map<String, ConfigValue> = emptyMap()) : VariableAccess {
    private val values = ConcurrentHashMap<String, ConfigValue>(initial)

    override fun get(name: String): ConfigValue? = values[name]

    override fun set(name: String, value: ConfigValue) {
        values[name] = value
    }

    override fun snapshot(): Map<String, ConfigValue> = values.toMap()
}
