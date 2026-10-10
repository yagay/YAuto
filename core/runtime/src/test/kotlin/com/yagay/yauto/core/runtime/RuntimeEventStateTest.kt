package com.yagay.yauto.core.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeEventStateTest {
    @Test fun resetOneAutomationDoesNotClearOtherStates() {
        val state = RuntimeEventState()
        state.setActive("first", true)
        state.setActive("second", true)
        state.reset("first")
        assertFalse(state.isActive("first"))
        assertTrue(state.isActive("second"))
        state.reset()
        assertFalse(state.isActive("second"))
    }
}
