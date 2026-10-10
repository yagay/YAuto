package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroDroidDisabledActionSafetyTest {
    @Test fun `disabled normal action stays visible and inert with original metadata`() {
        val nodes = actions("""[
            {"m_classType":"ToastAction","m_isDisabled":true,"m_messageText":"do not show",
                "m_comment":"intentionally off","m_label":"do not activate"},
            {"m_classType":"ToastAction","m_messageText":"active"}
        ]""")
        assertEquals(2, nodes.size)
        val disabled = nodes[0] as ActionNode.Action
        assertEquals("compat.source.action", disabled.feature.typeId)
        assertFalse(disabled.enabled)
        assertEquals("intentionally off", disabled.comment)
        assertTrue((disabled.feature.config["source.raw"] as ConfigValue.StringValue).value.contains("do not show"))
        assertEquals("android.toast.show", (nodes[1] as ActionNode.Action).feature.typeId)
    }

    @Test fun `disabled IF with nested conditional and else cannot leak executable actions`() {
        val nodes = actions("""[
            {"m_classType":"IfConditionAction","m_isDisabled":true,"m_comment":"disabled block"},
            {"m_classType":"ToastAction","m_messageText":"inside if"},
            {"m_classType":"IfConditionAction"},
            {"m_classType":"ToastAction","m_messageText":"nested"},
            {"m_classType":"EndIfAction"},
            {"m_classType":"ElseAction"},
            {"m_classType":"ToastAction","m_messageText":"else"},
            {"m_classType":"EndIfAction"},
            {"m_classType":"ToastAction","m_messageText":"after"}
        ]""")
        assertEquals(2, nodes.size)
        val held = nodes[0] as ActionNode.Action
        assertFalse(held.enabled)
        assertEquals("compat.source.action", held.feature.typeId)
        val raw = (held.feature.config["source.raw"] as ConfigValue.StringValue).value
        assertTrue(raw.contains("inside if"))
        assertTrue(raw.contains("nested"))
        assertTrue(raw.contains("else"))
        assertFalse(raw.contains("after"))
        assertEquals("android.toast.show", (nodes[1] as ActionNode.Action).feature.typeId)
    }

    @Test fun `disabled loop contains nested loops and avoids single stray body action`() {
        val nodes = actions("""[
            {"m_classType":"LoopAction","m_isDisabled":true},
            {"m_classType":"ToastAction","m_messageText":"outer"},
            {"m_classType":"LoopAction"},
            {"m_classType":"ToastAction","m_messageText":"inner"},
            {"m_classType":"EndLoopAction"},
            {"m_classType":"EndLoopAction"},
            {"m_classType":"ToastAction","m_messageText":"after loop"}
        ]""")
        assertEquals(2, nodes.size)
        val held = nodes[0] as ActionNode.Action
        assertFalse(held.enabled)
        assertTrue((held.feature.config["source.raw"] as ConfigValue.StringValue).value.contains("inner"))
        assertEquals("android.toast.show", (nodes[1] as ActionNode.Action).feature.typeId)
    }

    @Test fun `truncated disabled block never runs trailing children`() {
        val nodes = actions("""[
            {"m_classType":"IfConditionAction","m_isDisabled":true},
            {"m_classType":"ToastAction","m_messageText":"unsafe without matching end"}
        ]""")
        assertEquals(1, nodes.size)
        assertFalse((nodes.single() as ActionNode.Action).enabled)
    }

    private fun actions(source: String): List<ActionNode> {
        val payload = """{"macro":{"m_GUID":"disabled-test","m_name":"Disabled",
            "m_triggerList":[],"m_actionList":$source}}"""
        val result = MacroDroidImporter().import(ImportInput("disabled.macro", "application/json", payload.toByteArray()))
        assertTrue(result.issues.joinToString { it.message }, result.success)
        return result.bundle.automations.single().onEvent
    }
}
