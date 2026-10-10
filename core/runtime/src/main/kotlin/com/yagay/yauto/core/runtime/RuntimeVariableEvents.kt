package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConfigValue

/** Canonical persistent-variable change event used by runtime dispatch. */
internal fun variableChangedEvent(change: PersistentVariableChange): RuntimeEvent = RuntimeEvent(
        typeId = "core.event.variable_changed",
        payload = mapOf(
            "name" to ConfigValue.StringValue(change.name),
            "previous" to (change.previous ?: ConfigValue.NullValue),
            "current" to (change.current ?: ConfigValue.NullValue),
            "change" to ConfigValue.StringValue(
                when {
                    change.previous == null && change.current != null -> "created"
                    change.current == null -> "removed"
                    else -> "changed"
                }
            ),
        ),
        source = "runtime.variable",
    )

