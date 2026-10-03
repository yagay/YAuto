package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidEventSourceManagerTest {
    @Test
    fun `duplicate source id is rejected during registration`() {
        val manager = AndroidEventSourceManager()
        assertEquals(null, manager.add("first") { FakeSource("same") })

        val failure = manager.add("second") { FakeSource("same") }

        assertNotNull(failure)
        assertEquals(EventSourcePhase.CONSTRUCT, failure?.phase)
        assertEquals(listOf("same"), manager.sourceIds())
    }

    @Test
    fun `failed source does not block later sources`() {
        val events = mutableListOf<String>()
        val manager = AndroidEventSourceManager()
        manager.add("bad") { FakeSource("bad", events, failStart = true) }
        manager.add("good") { FakeSource("good", events) }

        val failures = manager.startAll(RuntimeEventEmitter { })

        assertEquals(1, failures.size)
        assertEquals(EventSourcePhase.START, failures.single().phase)
        assertEquals(listOf("start:bad", "start:good"), events)
    }

    @Test
    fun `sources stop in reverse registration order`() {
        val events = mutableListOf<String>()
        val manager = AndroidEventSourceManager()
        manager.add("one") { FakeSource("one", events) }
        manager.add("two") { FakeSource("two", events) }
        manager.startAll(RuntimeEventEmitter { })
        events.clear()

        val failures = manager.stopAll()

        assertTrue(failures.isEmpty())
        assertEquals(listOf("stop:two", "stop:one"), events)
    }

    private class FakeSource(
        override val id: String,
        private val events: MutableList<String> = mutableListOf(),
        private val failStart: Boolean = false,
    ) : AndroidEventSource {
        override fun start(emitter: RuntimeEventEmitter) {
            events += "start:$id"
            if (failStart) error("start failed")
        }

        override fun stop() {
            events += "stop:$id"
        }
    }
}
