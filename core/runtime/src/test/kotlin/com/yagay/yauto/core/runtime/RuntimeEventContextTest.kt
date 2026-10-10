package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.WorkspaceData
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeEventContextTest {
    @Test fun contextPrioritizesAutomationVariablesAndNamespacedEvents() {
        val workspace = WorkspaceData(
            globalVariables = mapOf("key" to "global"),
            persistentVariables = mapOf("key" to ConfigValue.StringValue("persistent")),
        )
        val context = RuntimeEventContext.variables(
            workspace,
            mapOf("key" to ConfigValue.StringValue("automation")),
            RuntimeEvent("example.changed", mapOf("key" to ConfigValue.StringValue("event")), source = "test"),
        )
        assertEquals(ConfigValue.StringValue("automation"), context["key"])
        assertEquals(ConfigValue.StringValue("event"), context["event.key"])
        assertEquals(ConfigValue.StringValue("example.changed"), context["event.type"])
        assertEquals(ConfigValue.StringValue("test"), context["event.source"])
    }
}
