package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.Activation
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.storage.WorkspaceData
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfiguredBroadcastEventSourceTest {
    @Test
    fun `extracts only enabled configured broadcast actions`() {
        val workspace = WorkspaceData(
            automations = listOf(
                automation("one", true, "com.example.ONE"),
                automation("two", true, " com.example.TWO "),
                automation("duplicate", true, "com.example.ONE"),
                automation("disabled", false, "com.example.DISABLED"),
                Automation(
                    id = AutomationId("other"),
                    name = "Other",
                    activation = Activation(events = listOf(FeatureRef("android.event.screen_on"))),
                ),
            )
        )

        assertEquals(setOf("com.example.ONE", "com.example.TWO"), workspace.configuredBroadcastActions())
    }

    private fun automation(id: String, enabled: Boolean, action: String) = Automation(
        id = AutomationId(id),
        name = id,
        enabled = enabled,
        activation = Activation(
            events = listOf(
                FeatureRef(
                    typeId = "android.event.broadcast",
                    config = mapOf("action" to ConfigValue.StringValue(action)),
                )
            )
        ),
    )
}
