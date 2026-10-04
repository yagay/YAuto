package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroDroidReferenceControlExpansionTest {
    @Test
    fun `maps display audio home and keyboard controls natively`() {
        val json = """
            {"macro":{"m_GUID":"control-expansion","m_name":"Control expansion","m_triggerList":[],"m_actionList":[
              {"m_classType":"DisplayDensityAction","scalePercent":85},
              {"m_classType":"FontScaleAction","scalePercent":120},
              {"m_classType":"ImmersiveModeAction","m_option":3},
              {"m_classType":"MuteMicrophoneAction","m_state":2},
              {"m_classType":"SpeakerPhoneAction","m_state":0},
              {"m_classType":"ShowVolumePopupAction","audioStream":5},
              {"m_classType":"LaunchHomeScreenAction","useAccessibilityService":false},
              {"m_classType":"LaunchHomeScreenAction","useAccessibilityService":true},
              {"m_classType":"SetKeyboardAction"}
            ]}}
        """.trimIndent()

        val result = MacroDroidImporter().import(ImportInput("control-expansion.macro", null, json.toByteArray()))
        assertTrue(result.success)
        val features = result.bundle.automations.single().onEvent.map { (it as ActionNode.Action).feature }

        assertEquals(
            listOf(
                "android.display.density.set",
                "android.display.font_scale.set",
                "android.display.immersive.set",
                "android.audio.microphone_mute.set",
                "android.audio.speakerphone.set",
                "android.audio.volume_ui.show",
                "android.home.launch",
                "accessibility.global_action",
                "android.ime.picker.show",
            ),
            features.map { it.typeId },
        )
        assertEquals(ConfigValue.NumberValue(85.0), features[0].config["scalePercent"])
        assertEquals(ConfigValue.NumberValue(1.2), features[1].config["scale"])
        assertEquals(ConfigValue.StringValue("full"), features[2].config["mode"])
        assertEquals(ConfigValue.StringValue("toggle"), features[3].config["mode"])
        assertEquals(ConfigValue.StringValue("enable"), features[4].config["mode"])
        assertEquals(ConfigValue.StringValue("voice_call"), features[5].config["stream"])
        assertEquals(ConfigValue.StringValue("home"), features[7].config["action"])
    }

    @Test
    fun `keeps variable-backed density and font scale source compatible`() {
        val json = """
            {"macro":{"m_GUID":"dynamic-scale","m_name":"Dynamic scale","m_triggerList":[],"m_actionList":[
              {"m_classType":"DisplayDensityAction","scalePercent":100,"variable":{"m_name":"dpi"}},
              {"m_classType":"FontScaleAction","scalePercent":100,"variable":{"m_name":"font"}}
            ]}}
        """.trimIndent()

        val result = MacroDroidImporter().import(ImportInput("dynamic-scale.macro", null, json.toByteArray()))
        assertTrue(result.success)
        val features = result.bundle.automations.single().onEvent.map { (it as ActionNode.Action).feature }
        assertEquals(listOf("compat.source.action", "compat.source.action"), features.map { it.typeId })
    }
}
