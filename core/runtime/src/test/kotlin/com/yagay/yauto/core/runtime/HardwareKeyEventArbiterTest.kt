package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import org.junit.Assert.*
import org.junit.Test

class HardwareKeyEventArbiterTest {
    private var now = 100L
    private val tasks = mutableListOf<() -> Unit>()
    private val published = mutableListOf<RuntimeEvent>()
    private fun arbiter() = HardwareKeyEventArbiter(
        publish = { published += it },
        schedule = { _, callback -> tasks += callback },
        clock = { now },
    )
    private fun event(source: String, code: Int = 24, action: String = "down",
                      raw: Int = 0, repeat: Int = 0) = RuntimeEvent(
        typeId = "android.event.hardware_key",
        payload = mapOf(
            "keyCode" to ConfigValue.NumberValue(code.toDouble()),
            "linuxEvKey" to ConfigValue.NumberValue(raw.toDouble()),
            "action" to ConfigValue.StringValue(action),
            "repeatCount" to ConfigValue.NumberValue(repeat.toDouble()),
        ),
        source = source,
    )
    private fun flush() {
        now += 66
        val snapshot = tasks.toList()
        tasks.clear()
        snapshot.forEach { it() }
    }

    @Test fun choosesLsposedRatherThanFirstArrival() {
        val arbiter = arbiter()
        arbiter.accept(event("accessibility.key"))
        now += 4
        arbiter.accept(event("root.input"))
        now += 4
        arbiter.accept(event("lsposed.system_server"))
        assertTrue(published.isEmpty())
        flush()
        assertEquals(listOf("lsposed.system_server"), published.map { it.source })
        now += 4
        arbiter.accept(event("accessibility.key"))
        flush()
        assertEquals(1, published.size)
    }

    @Test fun fallsBackToRootWhenLsposedMissingAndPreservesPressAndRelease() {
        val arbiter = arbiter()
        arbiter.accept(event("accessibility.key"))
        now += 5
        arbiter.accept(event("root.input"))
        arbiter.accept(event("root.input", action = "up"))
        flush()
        assertEquals(listOf("root.input", "root.input"), published.map { it.source })
        assertEquals("up", (published.last().payload["action"] as ConfigValue.StringValue).value)
    }

    @Test fun releasePrefersBackendThatSuppliedTheKeyDown() {
        val arbiter = arbiter()
        arbiter.accept(event("root.input", code = 24, action = "down"))
        flush()
        arbiter.accept(event("lsposed.system_server", code = 24, action = "up"))
        now += 3
        arbiter.accept(event("root.input", code = 24, action = "up"))
        flush()
        assertEquals(listOf("root.input", "root.input"), published.map { it.source })
    }

    @Test fun releaseQueuedBeforeDownDispatchStillUsesDownOwner() {
        val arbiter = arbiter()
        arbiter.accept(event("root.input", action = "down"))
        arbiter.accept(event("lsposed.system_server", action = "up"))
        arbiter.accept(event("root.input", action = "up"))
        flush()
        assertEquals(listOf("root.input", "root.input"), published.map { it.source })
    }

    @Test fun releaseQueuedBeforeDownDispatchFollowsLsposedSelection() {
        val arbiter = arbiter()
        arbiter.accept(event("root.input", action = "down"))
        arbiter.accept(event("lsposed.system_server", action = "down"))
        arbiter.accept(event("root.input", action = "up"))
        arbiter.accept(event("lsposed.system_server", action = "up"))
        flush()
        assertEquals(listOf("lsposed.system_server", "lsposed.system_server"), published.map { it.source })
    }

    @Test fun distinctPhysicalButtonsAndUnknownScanCodesAreNotCollapsed() {
        val arbiter = arbiter()
        arbiter.accept(event("root.input", code = 0, raw = 115))
        arbiter.accept(event("lsposed.system_server", code = 24))
        arbiter.accept(event("root.input", code = 0, raw = 114))
        flush()
        assertEquals(3, published.size)
    }

    @Test fun sameBackendRapidPressesAndRepeatEventsArePreserved() {
        val arbiter = arbiter()
        arbiter.accept(event("lsposed.system_server"))
        arbiter.accept(event("lsposed.system_server"))
        arbiter.accept(event("root.input", repeat = 1))
        flush()
        assertEquals(3, published.size)
    }

    @Test fun disabledArbiterDoesNotDeliverScheduledEvents() {
        val arbiter = arbiter()
        arbiter.accept(event("lsposed.system_server"))
        arbiter.close()
        flush()
        assertTrue(published.isEmpty())
    }
}
