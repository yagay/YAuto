package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortXObserverSubscriptionTest {
    @Test fun noSubscriptions() {
        assertTrue(ShortXCompatHookCatalog.subscribedObservers(emptySet()).isEmpty())
        assertTrue(ShortXCompatHookCatalog.subscribedCoreRuntimeHooks(emptySet()).isEmpty())
    }

    @Test fun notificationDoesNotInstallUnrelatedObservers() {
        val selected = ShortXCompatHookCatalog.subscribedObservers(
            setOf("android.event.notification_posted_system")
        )
        assertEquals(listOf("notification-posted"), selected.map { it.id })
        assertFalse(selected.any { it.eventType == "android.event.app_process_started" })
        assertTrue(ShortXCompatHookCatalog.subscribedCoreRuntimeHooks(
            setOf("android.event.notification_posted_system")
        ).isEmpty())
    }

    @Test fun distinctShortXEventsSelectDistinctObservers() {
        val selected = ShortXCompatHookCatalog.subscribedObservers(
            setOf("android.event.vpn_state_changed", "android.event.notification_posted_system")
        )
        assertEquals(setOf("vpn-state", "notification-posted"), selected.map { it.id }.toSet())
        assertEquals(2, selected.size)
    }

    @Test fun coreRuntimeHooksHaveExactEventSubscriptions() {
        assertEquals(
            setOf(ShortXCoreRuntimeHook.BACK_NAVIGATION, ShortXCoreRuntimeHook.ASSISTANT),
            ShortXCompatHookCatalog.subscribedCoreRuntimeHooks(
                setOf("android.event.back_navigation_finished", "android.event.assistant_activated")
            ),
        )
        assertEquals(
            setOf(ShortXCoreRuntimeHook.PROCESS_DEATH, ShortXCoreRuntimeHook.TASK_REMOVAL),
            ShortXCompatHookCatalog.subscribedCoreRuntimeHooks(
                setOf("android.event.app_process_stopped", "android.event.task_removed")
            ),
        )
    }
}
