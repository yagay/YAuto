package com.yagay.yauto.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AndroidReferenceCompletionFeaturePackTest {
    @Test
    fun `reference completion exposes exactly fifty broadcast triggers`() {
        assertEquals(50, REFERENCE_COMPLETION_EVENT_SPECS.size)
        assertEquals(50, REFERENCE_COMPLETION_EVENT_SPECS.map { it.typeId }.distinct().size)
        assertEquals(50, REFERENCE_BROADCAST_EVENT_TYPES.size)
        assertEquals(
            REFERENCE_COMPLETION_EVENT_SPECS.map { it.typeId }.toSet(),
            REFERENCE_BROADCAST_EVENT_TYPES.values.toSet(),
        )
    }

    @Test
    fun `every completion broadcast action resolves to a trigger id`() {
        val actions = REFERENCE_SYSTEM_ACTIONS + REFERENCE_PACKAGE_ACTIONS + REFERENCE_MEDIA_ACTIONS
        assertEquals(50, actions.size)
        assertEquals(50, actions.distinct().size)
        actions.forEach { action -> assertNotNull(eventTypeForReferenceBroadcast(action), action) }
    }
}
