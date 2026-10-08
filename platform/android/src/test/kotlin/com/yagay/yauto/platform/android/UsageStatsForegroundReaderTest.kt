package com.yagay.yauto.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageStatsForegroundReaderTest {
    @Test
    fun latestResumedActivityWins() {
        val result = resolveForegroundApp(
            listOf(
                record("a.app", "A", 100, UsageActivityTransition.RESUMED),
                record("b.app", "B", 200, UsageActivityTransition.RESUMED),
            )
        )

        assertEquals("b.app", result?.packageName)
        assertEquals("B", result?.className)
    }

    @Test
    fun pausedActivityIsRemoved() {
        val result = resolveForegroundApp(
            listOf(
                record("a.app", "A", 100, UsageActivityTransition.RESUMED),
                record("a.app", "A", 200, UsageActivityTransition.PAUSED),
            )
        )

        assertNull(result)
    }

    @Test
    fun pausingOldActivityDoesNotRemoveNewActivityInSamePackage() {
        val result = resolveForegroundApp(
            listOf(
                record("a.app", "FirstActivity", 100, UsageActivityTransition.RESUMED),
                record("a.app", "SecondActivity", 200, UsageActivityTransition.RESUMED),
                record("a.app", "FirstActivity", 300, UsageActivityTransition.PAUSED),
            )
        )

        assertEquals("a.app", result?.packageName)
        assertEquals("SecondActivity", result?.className)
    }

    @Test
    fun classlessStopClearsPackageActivities() {
        val result = resolveForegroundApp(
            listOf(
                record("a.app", "FirstActivity", 100, UsageActivityTransition.RESUMED),
                record("a.app", "SecondActivity", 200, UsageActivityTransition.RESUMED),
                record("a.app", null, 300, UsageActivityTransition.STOPPED),
            )
        )

        assertNull(result)
    }

    @Test
    fun cachedForegroundSurvivesAnEmptyIncrementalQuery() {
        val seed = snapshot("a.app", "A", 100)
        val result = resolveForegroundApp(emptyList(), seed)

        assertEquals(seed, result)
    }

    @Test
    fun laterPauseClearsCachedForeground() {
        val seed = snapshot("a.app", "A", 100)
        val result = resolveForegroundApp(
            listOf(record("a.app", "A", 200, UsageActivityTransition.PAUSED)),
            seed,
        )

        assertNull(result)
    }

    @Test
    fun transitionTrackerSeedsWithoutTriggeringAndOnlyEmitsPackageChanges() {
        val tracker = ForegroundAppTransitionTracker()
        val first = snapshot("a.app", "A", 100)
        val samePackage = snapshot("a.app", "B", 200)
        val next = snapshot("b.app", "C", 300)

        assertNull(tracker.sample(first))
        assertNull(tracker.sample(samePackage))
        val transition = tracker.sample(next)
        assertEquals("a.app", transition?.previous?.packageName)
        assertEquals("b.app", transition?.current?.packageName)

        tracker.reset()
        assertNull(tracker.sample(snapshot("c.app", "D", 400)))
    }

    private fun record(
        packageName: String,
        className: String?,
        timestamp: Long,
        transition: UsageActivityTransition,
    ) = UsageActivityRecord(packageName, className, timestamp, transition)

    private fun snapshot(packageName: String, className: String?, timestamp: Long) =
        ForegroundAppSnapshot(packageName, className, timestamp)
}
