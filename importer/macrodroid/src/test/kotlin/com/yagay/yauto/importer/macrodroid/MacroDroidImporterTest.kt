package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroDroidImporterTest {
    @Test fun `maps screen context and clipboard while retaining action constraints`() {
        val json = """{"macro":{"m_GUID":"contexts","m_triggerList":[{"m_classType":"ScreenOnOffTrigger","m_screenOn":false}],"m_constraintList":[{"m_classType":"ScreenOnConstraint","m_screenOn":false}],"m_actionList":[{"m_classType":"SetClipboardAction","m_text":"text"},{"m_classType":"ToastAction","m_messageText":"guarded","m_constraintList":[{"m_classType":"UnknownConstraint"}]}]}}"""
        val result = MacroDroidImporter().import(ImportInput("contexts.macro", null, json.toByteArray()))
        assertTrue(result.success)
        val rule = result.bundle.automations.single()
        assertEquals("android.event.screen_off", rule.activation.events.single().typeId)
        val condition = (rule.activation.condition as com.yagay.yauto.core.model.PredicateNode.All).children.single() as com.yagay.yauto.core.model.PredicateNode.Condition
        assertEquals("android.condition.screen", condition.feature.typeId)
        assertEquals(ConfigValue.BooleanValue(false), condition.feature.config["value"])
        assertEquals("android.clipboard.set", (rule.onEvent[0] as ActionNode.Action).feature.typeId)
        val guarded = (rule.onEvent[1] as ActionNode.Action).feature
        assertEquals("compat.source.action", guarded.typeId)
        assertTrue((guarded.config["source.raw"] as? ConfigValue.StringValue)?.value?.contains("UnknownConstraint") == true)
    }
    @Test
    fun `imports common single macro wrapper and native actions`() {
        val json = """
            {
              "macroExportVersion": 1,
              "macro": {
                "m_GUID": "123",
                "m_name": "Demo",
                "m_enabled": true,
                "m_triggerList": [],
                "m_constraintList": [],
                "m_actionList": [
                  {"m_classType":"PauseAction","m_delayInSeconds":2,"m_delayInMilliSeconds":250},
                  {"m_classType":"ToastAction","m_messageText":"hello"},
                  {"m_classType":"LaunchActivityAction","m_packageToLaunch":"com.example.app"},
                  {"m_classType":"SetVariableAction","m_variable":{"m_name":"count"},"m_newIntValue":7}
                ]
              }
            }
        """.trimIndent()

        val result = MacroDroidImporter().import(ImportInput("demo.macro", "application/json", json.toByteArray()))

        assertTrue(result.success)
        assertEquals(1, result.bundle.automations.size)
        val actions = result.bundle.automations.single().onEvent.map { (it as ActionNode.Action).feature }
        assertEquals(listOf("core.delay", "android.toast.show", "android.app.launch", "variable.set"), actions.map { it.typeId })
        assertEquals(2250.0, (actions[0].config["durationMs"] as ConfigValue.NumberValue).value, 0.0)
        assertEquals("hello", (actions[1].config["text"] as ConfigValue.StringValue).value)
        assertEquals("com.example.app", (actions[2].config["package"] as ConfigValue.StringValue).value)
    }

    @Test
    fun `converts exported action block to flow and call flow node`() {
        val json = """
            {
              "macroExportVersion": 1,
              "macro": {
                "m_GUID": "m1",
                "m_name": "Block caller",
                "m_triggerList": [],
                "m_actionList": [
                  {"m_classType":"ActionBlockAction","actionBlockId":"b1"}
                ],
                "exportedActionBlocks": [
                  {
                    "m_GUID":"b1",
                    "m_name":"Reusable block",
                    "m_actionList":[{"m_classType":"ToastAction","m_messageText":"from block"}]
                  }
                ]
              }
            }
        """.trimIndent()

        val result = MacroDroidImporter().import(ImportInput("block.macro", "application/json", json.toByteArray()))

        assertTrue(result.success)
        assertEquals(1, result.bundle.flows.size)
        assertTrue(result.bundle.automations.single().onEvent.single() is ActionNode.CallFlow)
        assertEquals("android.toast.show", (result.bundle.flows.single().actions.single() as ActionNode.Action).feature.typeId)
    }
}
