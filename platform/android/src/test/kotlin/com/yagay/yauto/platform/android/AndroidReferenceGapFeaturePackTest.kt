package com.yagay.yauto.platform.android

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AndroidReferenceGapFeaturePackTest {
    @Test
    fun referenceBatchContainsExactlyFiftyUniquePairs() {
        assertEquals(50, REFERENCE_APK_GAP_KEYS.size)
        assertEquals(50, REFERENCE_APK_GAP_KEYS.toSet().size)
    }

    @Test
    fun numericRangeRejectsInvalidBounds() {
        assertTrue(matchesReferenceGapNumber(60.0, 50.0, 70.0, 0.0, 100.0))
        assertFalse(matchesReferenceGapNumber(60.0, 70.0, 50.0, 0.0, 100.0))
        assertFalse(matchesReferenceGapNumber(Double.NaN, null, null, 0.0, 100.0))
        assertFalse(matchesReferenceGapNumber(101.0, null, null, 0.0, 100.0))
    }

    @Test
    fun frameworkValuesMapToStableNames() {
        assertEquals("in_communication", audioModeName(AudioManager.MODE_IN_COMMUNICATION))
        assertEquals("portrait", orientationName(Configuration.ORIENTATION_PORTRAIT))
        assertEquals("270", displayRotationName(Surface.ROTATION_270))
        assertEquals("monday", dayOfWeekName(Calendar.MONDAY))
        assertEquals("productivity", applicationCategoryName(ApplicationInfo.CATEGORY_PRODUCTIVITY))
    }
}
