package com.yagay.yauto

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGrantCenterTest {
    @Test fun legacyAndModernPermissionGroupsRespectSdk() {
        val modern = permissionGrantGroups(35)
        assertEquals(modern.map { it.id }.distinct().size, modern.size)
        assertTrue(modern.any { it.id == "notifications" })
        assertTrue(modern.any { it.id == "wifi" })
        assertTrue(modern.any { it.id == "location_background" })
        val media = modern.single { it.id == "media" }
        assertTrue(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in media.permissions)
        val older = permissionGrantGroups(32)
        assertFalse(older.any { it.id == "notifications" })
        assertFalse(older.any { it.id == "wifi" })
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            older.single { it.id == "media" }.permissions)
    }

    @Test fun runtimeGroupOnlyRequestsMissingPermissions() {
        val contacts = permissionGrantGroups(35).single { it.id == "contacts" }
        assertEquals(listOf(Manifest.permission.READ_CONTACTS),
            missingRuntimePermissions(contacts) { false })
        assertEquals(emptyList<String>(),
            missingRuntimePermissions(contacts) { true })
        val phone = permissionGrantGroups(35).single { it.id == "phone" }
        val missing = missingRuntimePermissions(phone) {
            it == Manifest.permission.READ_PHONE_STATE
        }
        assertFalse(Manifest.permission.READ_PHONE_STATE in missing)
        assertTrue(Manifest.permission.CALL_PHONE in missing)
    }

    @Test fun backgroundLocationCannotBeBatchRequested() {
        val bg = permissionGrantGroups(35).single { it.id == "location_background" }
        assertTrue(bg.requiresForegroundLocation)
        assertEquals(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), bg.permissions)
    }
}
