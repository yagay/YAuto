package com.yagay.yauto.ui.editor

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.NodeId
import com.yagay.yauto.core.registry.FeatureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationItemOperationsTest {
    private fun data() = EditableAutomationSections(
        events = listOf(FeatureRef("event.a"), FeatureRef("event.b")),
        states = listOf(FeatureRef("state.a")),
        conditions = listOf(FeatureRef("condition.a")),
        onEvent = listOf(ActionNode.Action(NodeId("1"), FeatureRef("action.a")),
            ActionNode.Action(NodeId("2"), FeatureRef("action.b"))),
        onEnter = emptyList(),
        onExit = emptyList(),
    )

    @Test fun duplicationOfAnActionGetsNewNodeIdentity() {
        val changed = data().modify("action:EVENT", 0, AutomationItemChange.DUPLICATE)
        assertEquals(3, changed.onEvent.size)
        val first = changed.onEvent[0] as ActionNode.Action
        val copied = changed.onEvent[1] as ActionNode.Action
        assertNotEquals(first.id, copied.id)
        assertEquals(first.feature, copied.feature)
        assertEquals(first.enabled, copied.enabled)
    }

    @Test fun menuSelectionSupportsTestForAllFeatureTypes() {
        val original = data()
        assertEquals("event.a", original.feature("event", 0)?.typeId)
        assertEquals(FeatureKind.EVENT, original.kind("event"))
        assertEquals(FeatureKind.STATE, original.kind("state"))
        assertEquals(FeatureKind.CONDITION, original.kind("condition"))
        assertEquals(FeatureKind.ACTION, original.kind("action:EVENT"))
        assertEquals("action.a", original.feature("action:EVENT", 0)?.typeId)
        assertEquals(null, original.feature("action:EVENT", 10))
    }

    @Test fun moveAndDeleteStayInSelectedSection() {
        val original = data()
        val moved = original.modify("event", 1, AutomationItemChange.UP)
        assertEquals(listOf("event.b", "event.a"), moved.events.map { it.typeId })
        assertEquals(original.onEvent, moved.onEvent)
        val removed = original.modify("condition", 0, AutomationItemChange.DELETE)
        assertTrue(removed.conditions.isEmpty())
        assertEquals(original.states, removed.states)
    }

    @Test fun disabledActionRemainsInListAndCanBeRestored() {
        val disabled = data().modify("action:EVENT", 0, AutomationItemChange.TOGGLE_ENABLED)
        assertFalse((disabled.onEvent.first() as ActionNode.Action).enabled)
        val enabled = disabled.modify("action:EVENT", 0, AutomationItemChange.TOGGLE_ENABLED)
        assertTrue((enabled.onEvent.first() as ActionNode.Action).enabled)
        assertEquals(2, enabled.onEvent.size)
    }

    @Test fun invalidIndexIsNoOp() {
        val original = data()
        assertEquals(original, original.modify("event", 99, AutomationItemChange.DELETE))
        assertEquals(original, original.modify("unknown", 0, AutomationItemChange.DUPLICATE))
    }
}
