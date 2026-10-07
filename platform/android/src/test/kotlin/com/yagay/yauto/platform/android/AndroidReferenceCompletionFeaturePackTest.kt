package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidReferenceCompletionFeaturePackTest {
    @Test
    fun `reference completion keeps canonicalized runtime broadcasts out of descriptor list`() {
        val canonicalizedRuntimeTypes = setOf(
            "android.event.managed_profile_available",
            "android.event.managed_profile_unavailable",
            "android.event.managed_profile_unlocked",
            "android.event.bluetooth_state_changed",
            "android.event.bluetooth_bond_state_changed",
            "android.event.bluetooth_acl_connected",
            "android.event.bluetooth_acl_disconnected",
            "android.event.wifi_state_changed",
            "android.event.wifi_network_state_changed",
            "android.event.wifi_rssi_changed",
        )
        assertEquals(40, REFERENCE_COMPLETION_EVENT_SPECS.size)
        assertEquals(40, REFERENCE_COMPLETION_EVENT_SPECS.map { it.typeId }.distinct().size)
        assertEquals(50, REFERENCE_BROADCAST_EVENT_TYPES.size)
        assertEquals(
            REFERENCE_COMPLETION_EVENT_SPECS.map { it.typeId }.toSet(),
            REFERENCE_BROADCAST_EVENT_TYPES.values.toSet() - canonicalizedRuntimeTypes,
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
