package com.yagay.yauto.platform.android

import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePickerCategory
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.macroDroidCategoriesForKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verify the real installed Android descriptors, not just synthetic string-based classifications. */
class AndroidPickerCategoryIntegrationTest {
    @Test
    fun `all reference broadcasts and ShortX observer events have valid event picker categories`() {
        val registry = FeatureRegistry()
        registry.install(AndroidReferenceCompletionEventFeaturePack())
        registry.install(AndroidShortXHookEventFeaturePack())

        val descriptors = registry.allDescriptors()
        assertTrue("Expected reference and ShortX event coverage", descriptors.size >= 80)
        assertTrue(descriptors.all { it.kind == FeatureKind.EVENT })
        val allowed = macroDroidCategoriesForKind(FeatureKind.EVENT)
        descriptors.forEach { descriptor ->
            assertTrue(
                "${descriptor.id.value} classified as ${descriptor.pickerCategory}",
                descriptor.pickerCategory in allowed,
            )
        }

        val semanticExamples = mapOf(
            "android.event.user_foreground" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.user_background" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.external_apps_available" to FeaturePickerCategory.APPLICATIONS,
            "android.event.media_mounted" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.wifi_supplicant_state_changed" to FeaturePickerCategory.CONNECTIVITY,
            "android.event.activity_manager_started" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.window_focus_changed" to FeaturePickerCategory.APPLICATIONS,
            "android.event.systemui_qs_tile_clicked" to FeaturePickerCategory.USER_INPUT,
            "android.event.notification_posted_system" to FeaturePickerCategory.DEVICE_EVENTS,
            "android.event.input_text_committed" to FeaturePickerCategory.USER_INPUT,
        )
        semanticExamples.forEach { (id, expected) ->
            val descriptor = registry.descriptor(id)
            assertNotNull("Missing Android registered event: $id", descriptor)
            assertEquals(id, expected, descriptor!!.pickerCategory)
        }
    }
}
