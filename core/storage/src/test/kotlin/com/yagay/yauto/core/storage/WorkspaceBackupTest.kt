package com.yagay.yauto.core.storage

import com.yagay.yauto.core.model.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceBackupTest {
    @Test fun `round trip preserves structured nodes unknown features flows and typed variables`() {
        val flow = Flow(FlowId("flow"), "Flow", inputs = listOf(FlowParameter("number", ValueType.NUMBER)), actions = listOf(ActionNode.Return(NodeId("return"), ConfigValue.NumberValue(42.0))))
        val data = WorkspaceData(automations = listOf(Automation(AutomationId("rule"), "Rule",
            activation = Activation(states = listOf(FeatureRef("android.state.screen"))),
            onEvent = listOf(ActionNode.If(NodeId("if"), PredicateNode.Condition(FeatureRef("future.condition")),
                listOf(ActionNode.CallFlow(NodeId("call"), flow.id)), listOf(ActionNode.Action(NodeId("unknown"), FeatureRef("future.action", 8, mapOf("payload" to ConfigValue.StringValue("raw"))))))))),
            flows = listOf(flow), globalVariables = mapOf("test" to "value"))
        val backup = WorkspaceBackupCodec.decode(WorkspaceBackupCodec.encode(data, "test", 123))
        assertEquals(data, backup.workspace)
        assertEquals(setOf("android.state.screen", "future.condition", "future.action"), backup.manifest.requiredFeatureIds)
        assertTrue(data.references(flow.id))
    }

    @Test fun `xor nested conditions are included in backup feature dependencies`() {
        val nested = PredicateNode.Xor(
            listOf(
                PredicateNode.Condition(FeatureRef("android.condition.wifi_network")),
                PredicateNode.All(listOf(
                    PredicateNode.Condition(FeatureRef("android.condition.battery_level")),
                )),
            ),
        )
        val workspace = WorkspaceData(
            automations = listOf(Automation(
                AutomationId("xor"), "XOR",
                activation = Activation(condition = nested),
                onEvent = listOf(ActionNode.WaitUntil(NodeId("wait"), nested)),
            )),
        )
        val decoded = WorkspaceBackupCodec.decode(WorkspaceBackupCodec.encode(workspace, "test"))
        assertEquals(
            setOf("android.condition.wifi_network", "android.condition.battery_level"),
            decoded.manifest.requiredFeatureIds,
        )
    }

    @Test fun `workspace merge preserves typed persistent variables while keeping unrelated values`() {
        val original = WorkspaceData(
            globalVariables = mapOf("old" to "original"),
            persistentVariables = mapOf(
                "oldNumber" to ConfigValue.NumberValue(3.0),
                "overwrite" to ConfigValue.BooleanValue(false),
            ),
        )
        val merged = original.merge(
            automationsToImport = emptyList(),
            flowsToImport = emptyList(),
            variablesToImport = mapOf("new" to "imported"),
            persistentVariablesToImport = mapOf(
                "overwrite" to ConfigValue.BooleanValue(true),
                "newList" to ConfigValue.ListValue(listOf(ConfigValue.StringValue("item"))),
            ),
        )
        assertEquals(mapOf("old" to "original", "new" to "imported"), merged.globalVariables)
        assertEquals(ConfigValue.NumberValue(3.0), merged.persistentVariables["oldNumber"])
        assertEquals(ConfigValue.BooleanValue(true), merged.persistentVariables["overwrite"])
        assertEquals(
            ConfigValue.ListValue(listOf(ConfigValue.StringValue("item"))),
            merged.persistentVariables["newList"],
        )
    }

    @Test(expected = IllegalArgumentException::class) fun `future backup versions are rejected`() {
        WorkspaceBackupCodec.decode(WorkspaceBackupCodec.encode(WorkspaceData(), "test").replace("\"formatVersion\": 1", "\"formatVersion\": 99"))
    }
}
