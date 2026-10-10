package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.storage.WorkspaceData
import org.junit.Assert.*
import org.junit.Test

class RuntimeSelectionPolicyTest {
    @Test fun categoryRulesTreatBlankAsUnrestricted() {
        assertTrue(RuntimeSelectionPolicy.categoryEnabled(null, setOf("work")))
        assertTrue(RuntimeSelectionPolicy.categoryEnabled(" ", setOf("work")))
        assertFalse(RuntimeSelectionPolicy.categoryEnabled(" work ", setOf("work")))
        assertTrue(RuntimeSelectionPolicy.categoryEnabled("personal", setOf("work")))
    }
}
