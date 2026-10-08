package com.yagay.yauto.core.storage

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.Activation
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.Flow
import com.yagay.yauto.core.model.FlowId
import com.yagay.yauto.core.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceEventSubscriptionsTest {
    @Test
    fun `subscriptions include activation and wait events reachable through flows`() {
        val flow = Flow(
            id = FlowId("flow"),
            name = "Flow",
            actions = listOf(
                ActionNode.WaitEvent(
                    id = NodeId("flow-wait"),
                    events = listOf(FeatureRef("android.event.sound_level")),
                )
            ),
        )
        val active = Automation(
            id = AutomationId("active"),
            name = "Active",
            activation = Activation(events = listOf(FeatureRef("android.event.ui_scrolled"))),
            onEvent = listOf(
                ActionNode.WaitEvent(
                    id = NodeId("wait"),
                    events = listOf(FeatureRef("android.event.system_log_entry")),
                ),
                ActionNode.CallFlow(NodeId("call"), flow.id),
            ),
        )
        val disabled = Automation(
            id = AutomationId("disabled"),
            name = "Disabled",
            enabled = false,
            activation = Activation(events = listOf(FeatureRef("android.event.sensor_value"))),
        )

        val ids = WorkspaceData(
            automations = listOf(active, disabled),
            flows = listOf(flow),
        ).runtimeEventFeatureIds()

        assertEquals(
            setOf(
                "android.event.ui_scrolled",
                "android.event.system_log_entry",
                "android.event.sound_level",
            ),
            ids,
        )
    }

    @Test
    fun `paused runtime does not keep costly configured event sources alive`() {
        val paused = WorkspaceData(
            runtimeEnabled = false,
            automations = listOf(
                Automation(
                    id = AutomationId("paused"),
                    name = "Paused",
                    activation = Activation(events = listOf(FeatureRef("android.event.sensor_value"))),
                    onEvent = listOf(
                        ActionNode.WaitEvent(
                            id = NodeId("wait"),
                            events = listOf(FeatureRef("android.event.file_changed")),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(emptySet<String>(), paused.runtimeEventFeatureIds())
        assertEquals(
            setOf("android.event.sensor_value", "android.event.file_changed"),
            paused.copy(runtimeEnabled = true).runtimeEventFeatureIds(),
        )
    }

    @Test
    fun `disabled triggers stop subscriptions but wait events can still require same backend`() {
        val id = "android.event.file_changed"
        val trigger = FeatureRef(id)
        val automation = Automation(
            id = AutomationId("rule"),
            name = "Rule",
            activation = Activation(events = listOf(trigger)),
        )
        val disabled = WorkspaceData(
            automations = listOf(automation),
            disabledTriggerKeys = setOf("rule|$id|"),
        )
        assertEquals(emptySet<String>(), disabled.runtimeEventFeatureIds())
        assertEquals(
            setOf(id),
            disabled.copy(automations = listOf(automation.copy(onEvent = listOf(
                ActionNode.WaitEvent(NodeId("wait"), listOf(FeatureRef(id))),
            )))).runtimeEventFeatureIds(),
        )
    }

    @Test
    fun `disabled imported trigger keys honor historical source type and tag`() {
        val feature = FeatureRef(
            "android.event.broadcast",
            config = mapOf(
                "source.type" to com.yagay.yauto.core.model.ConfigValue.StringValue("legacy.trigger"),
                "tag" to com.yagay.yauto.core.model.ConfigValue.StringValue(" key "),
            ),
        )
        val data = WorkspaceData(
            automations = listOf(Automation(
                AutomationId("imported"), "Imported",
                activation = Activation(events = listOf(feature)),
            )),
            disabledTriggerKeys = setOf("imported|legacy.trigger|key"),
        )
        assertEquals(emptySet<String>(), data.runtimeEventFeatureIds())
    }

    @Test
    fun `disabled categories do not keep event sources alive`() {
        val automation = Automation(
            id = AutomationId("a"),
            name = "A",
            category = "heavy",
            activation = Activation(events = listOf(FeatureRef("android.event.sensor_value"))),
        )

        val ids = WorkspaceData(
            automations = listOf(automation),
            disabledCategories = setOf("heavy"),
        ).runtimeEventFeatureIds()

        assertEquals(emptySet<String>(), ids)
    }
}
