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

    @Test(expected = IllegalArgumentException::class) fun `future backup versions are rejected`() {
        WorkspaceBackupCodec.decode(WorkspaceBackupCodec.encode(WorkspaceData(), "test").replace("\"formatVersion\": 1", "\"formatVersion\": 99"))
    }
}
