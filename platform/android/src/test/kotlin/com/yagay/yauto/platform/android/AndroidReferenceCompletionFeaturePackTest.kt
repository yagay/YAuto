package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidReferenceCompletionFeaturePackTest {
    @Test
    fun `reference completion keeps managed-profile runtime broadcasts as aliases`() {
        val managedProfileAliasTypes = setOf(
            "android.event.managed_profile_available",
            "android.event.managed_profile_unavailable",
            "android.event.managed_profile_unlocked",
        )
        assertEquals(47, REFERENCE_COMPLETION_EVENT_SPECS.size)
        assertEquals(47, REFERENCE_COMPLETION_EVENT_SPECS.map { it.typeId }.distinct().size)
        assertEquals(50, REFERENCE_BROADCAST_EVENT_TYPES.size)
        assertEquals(
            REFERENCE_COMPLETION_EVENT_SPECS.map { it.typeId }.toSet(),
            REFERENCE_BROADCAST_EVENT_TYPES.values.toSet() - managedProfileAliasTypes,
        )
    }

    @Test
    fun `every completion broadcast action resolves to a trigger id`() {
        val actions = REFERENCE_SYSTEM_ACTIONS + REFERENCE_PACKAGE_ACTIONS + REFERENCE_MEDIA_ACTIONS
        assertEquals(50, actions.size)
        assertEquals(50, actions.distinct().size)
        actions.forEach { action -> assertNotNull(action, eventTypeForReferenceBroadcast(action)) }
    }
}
