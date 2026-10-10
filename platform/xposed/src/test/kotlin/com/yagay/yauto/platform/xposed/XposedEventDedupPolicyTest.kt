package com.yagay.yauto.platform.xposed

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedEventDedupPolicyTest {
    @Test fun identicalEventsAreSuppressedOnlyWithinTheirWindow() {
        val recent = ConcurrentHashMap<String, Long>()
        assertTrue(XposedEventDedupPolicy.accept(recent, "key", 1000L, 150L))
        assertFalse(XposedEventDedupPolicy.accept(recent, "key", 1050L, 150L))
        assertTrue(XposedEventDedupPolicy.accept(recent, "other", 1050L, 150L))
        assertTrue(XposedEventDedupPolicy.accept(recent, "key", 1150L, 150L))
    }

    @Test fun clockResetDoesNotPermanentlySuppressEvents() {
        val recent = ConcurrentHashMap<String, Long>()
        assertTrue(XposedEventDedupPolicy.accept(recent, "key", 5000L, 1000L))
        assertTrue(XposedEventDedupPolicy.accept(recent, "key", 1000L, 1000L))
        assertEquals(1000L, recent["key"])
    }

    @Test fun concurrentCallbacksPublishOnlyOnceForSameKey() {
        val recent = ConcurrentHashMap<String, Long>()
        val threads = 8
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(threads)
        val accepted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    try {
                        ready.countDown()
                        start.await()
                        if (XposedEventDedupPolicy.accept(recent, "same", 3000L, 250L)) {
                            accepted.incrementAndGet()
                        }
                    } finally {
                        finished.countDown()
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, accepted.get())
        } finally {
            pool.shutdownNow()
        }
    }
}
