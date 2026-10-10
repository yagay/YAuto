package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.engine.AutomationPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeEventDispatchPolicyTest {
    @Test fun statefulTransitionIsDeterministic() {
        assertEquals(listOf(AutomationPhase.ENTER, AutomationPhase.EVENT),
            RuntimeEventDispatchPolicy.phases(true, true, false, true, true))
        assertEquals(listOf(AutomationPhase.EXIT),
            RuntimeEventDispatchPolicy.phases(true, false, true, false, false))
        assertEquals(emptyList<AutomationPhase>(),
            RuntimeEventDispatchPolicy.phases(true, false, false, false, false))
        assertEquals(listOf(AutomationPhase.EVENT),
            RuntimeEventDispatchPolicy.phases(false, false, false, true, true))
    }
}
