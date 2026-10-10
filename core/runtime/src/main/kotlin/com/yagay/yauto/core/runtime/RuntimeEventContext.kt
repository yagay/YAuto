package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.WorkspaceData

/** One canonical event context shared by trigger matching and manual dispatch. */
internal object RuntimeEventContext {
    fun variables(
        workspace: WorkspaceData,
        automationVariables: Map<String, ConfigValue>,
        event: RuntimeEvent,
    ): Map<String, ConfigValue> = buildMap {
        workspace.globalVariables.forEach { (name, value) ->
            put(name, ConfigValue.StringValue(value))
        }
        putAll(workspace.persistentVariables)
        putAll(automationVariables)
        event.payload.forEach { (name, value) -> put("event.$name", value) }
        put("event.type", ConfigValue.StringValue(event.typeId))
        put("event.source", ConfigValue.StringValue(event.source))
    }
}
