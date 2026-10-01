package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.PredicateNode
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
        val condition = (rule.activation.condition as PredicateNode.All).children.single() as PredicateNode.Condition
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
    fun `maps safe Android control actions from documented MacroDroid fields`() {
        val json = """
            {"macro":{"m_GUID":"controls","m_name":"Controls","m_triggerList":[],"m_actionList":[
              {"m_classType":"SetVolumeAction","m_streamIndexArray":[false,true,false,false,false,false,false,false],"m_streamVolumeArray":[0,42,0,0,0,0,0,0],"m_variables":[null,null,null,null,null,null,null,null],"setInForeground":true},
              {"m_classType":"SetBrightnessAction","setAutoBrightness":false,"setBrightnessValue":true,"m_brightnessPercent":65},
              {"m_classType":"SetBrightnessAction","setAutoBrightness":true,"autoBrightnessOn":true,"setBrightnessValue":false},
              {"m_classType":"SendIntentAction","m_target":"Broadcast","m_action":"com.example.ACTION","m_packageName":"com.example","m_extra1Name":"mode","m_extra1Value":"fast","m_extra1Type":1,"m_flags":32},
              {"m_classType":"NotificationAction","m_notificationSubject":"Title","m_notificationText":"Body","notificatonId":77,"notificationChannelName":"Imported"}
            ]}}
        """.trimIndent()

        val result = MacroDroidImporter().import(ImportInput("controls.macro", null, json.toByteArray()))
        assertTrue(result.success)
        val features = result.bundle.automations.single().onEvent.map { (it as ActionNode.Action).feature }
        assertEquals(
            listOf(
                "android.audio.media_volume.set",
                "android.display.brightness.set",
                "android.display.brightness.set",
                "android.intent.send",
                "android.notification.show",
            ),
            features.map { it.typeId },
        )
        assertEquals(42.0, (features[0].config["percent"] as ConfigValue.NumberValue).value, 0.0)
        assertEquals(ConfigValue.BooleanValue(true), features[0].config["showUi"])
        assertEquals(ConfigValue.StringValue("manual"), features[1].config["mode"])
        assertEquals(65.0, (features[1].config["percent"] as ConfigValue.NumberValue).value, 0.0)
        assertEquals(ConfigValue.StringValue("auto"), features[2].config["mode"])
        assertEquals(ConfigValue.StringValue("broadcast"), features[3].config["target"])
        assertEquals(ConfigValue.StringValue("mode"), features[3].config["extra1Key"])
        assertEquals(ConfigValue.NumberValue(77.0), features[4].config["id"])
    }

    @Test
    fun `maps notification trigger and display constraints conservatively`() {
        val json = """
            {"macro":{"m_GUID":"contexts-2","m_name":"Contexts","m_triggerList":[
              {"m_classType":"NotificationTrigger","m_option":0,"m_packageNameList":["com.example.chat"],"separateTitleAndMessage":true,"titleContent":"Alert","matchOptionTitle":2,"messageContent":"Ready","matchOptionMessage":2,"ignoreCase":true,"m_ignoreOngoing":true}
            ],"m_constraintList":[
              {"m_classType":"VolumeLevelConstraint","m_streamIndexArray":[false,true,false,false,false,false,false],"m_volume":35,"m_comparison":1},
              {"m_classType":"BrightnessConstraint","m_equals":true,"m_isAutoBrightness":false,"m_brightness":60},
              {"m_classType":"BrightnessConstraint","m_isAutoBrightness":true,"m_brightness":0}
            ],"m_actionList":[]}}
        """.trimIndent()
        val result = MacroDroidImporter().import(ImportInput("contexts-2.macro", null, json.toByteArray()))
        assertTrue(result.success)
        val automation = result.bundle.automations.single()
        val event = automation.activation.events.single()
        assertEquals("android.event.notification_posted", event.typeId)
        assertEquals(ConfigValue.StringValue("com.example.chat"), event.config["package"])
        assertEquals(ConfigValue.StringValue("Alert"), event.config["titleContains"])
        assertEquals(ConfigValue.StringValue("Ready"), event.config["textContains"])
        assertEquals(ConfigValue.StringValue("exclude"), event.config["ongoing"])

        val conditions = (automation.activation.condition as PredicateNode.All).children.map { (it as PredicateNode.Condition).feature }
        assertEquals(listOf("android.condition.media_volume", "android.condition.brightness", "android.condition.brightness"), conditions.map { it.typeId })
        assertEquals(ConfigValue.StringValue(">"), conditions[0].config["operator"])
        assertEquals(ConfigValue.NumberValue(35.0), conditions[0].config["value"])
        assertEquals(ConfigValue.StringValue("=="), conditions[1].config["operator"])
        assertEquals(ConfigValue.StringValue("auto"), conditions[2].config["mode"])
        assertEquals(ConfigValue.BooleanValue(false), conditions[2].config["compareLevel"])
    }

    @Test
    fun `keeps unsupported notification trigger variants as compatibility event`() {
        val json = """{"macro":{"m_GUID":"notify-unsafe","m_triggerList":[{"m_classType":"NotificationTrigger","m_packageNameList":["a","b"],"m_excludeApps":true,"enableRegex":true}],"m_actionList":[]}}"""
        val result = MacroDroidImporter().import(ImportInput("notify-unsafe.macro", null, json.toByteArray()))
        assertTrue(result.success)
        assertEquals("compat.source.event", result.bundle.automations.single().activation.events.single().typeId)
    }

    @Test
    fun `keeps unsafe MacroDroid control variants as compatibility actions`() {
        val json = """
            {"macro":{"m_GUID":"unsafe","m_name":"Unsafe","m_triggerList":[],"m_actionList":[
              {"m_classType":"SetVolumeAction","m_streamIndexArray":[true,true,false,false,false,false,false,false],"m_streamVolumeArray":[20,30,0,0,0,0,0,0]},
              {"m_classType":"SetBrightnessAction","setAutoBrightness":true,"autoBrightnessOn":true,"setBrightnessValue":true,"m_brightnessPercent":50},
              {"m_classType":"SendIntentAction","m_target":"Broadcast","m_action":"x","m_extra1Name":"enabled","m_extra1Value":"true","m_extra1Type":2},
              {"m_classType":"NotificationAction","m_notificationSubject":"Tap me","m_notificationText":"Body","m_runMacroWhenPressed":true,"m_macroGUIDToRun":123}
            ]}}
        """.trimIndent()
        val result = MacroDroidImporter().import(ImportInput("unsafe.macro", null, json.toByteArray()))
        assertTrue(result.success)
        assertTrue(result.bundle.automations.single().onEvent.all { (it as ActionNode.Action).feature.typeId == "compat.source.action" })
        assertTrue(result.issues.size >= 4)
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
