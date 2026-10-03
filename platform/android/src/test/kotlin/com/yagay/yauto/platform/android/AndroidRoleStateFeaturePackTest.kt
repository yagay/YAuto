package com.yagay.yauto.platform.android

import android.app.role.RoleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidRoleStateFeaturePackTest {
    @Test
    fun `role names map to Android role constants`() {
        assertEquals(RoleManager.ROLE_BROWSER, roleName("browser"))
        assertEquals(RoleManager.ROLE_DIALER, roleName("dialer"))
        assertEquals(RoleManager.ROLE_SMS, roleName("sms"))
        assertEquals(RoleManager.ROLE_HOME, roleName("home"))
        assertEquals(RoleManager.ROLE_ASSISTANT, roleName("assistant"))
        assertNull(roleName("unknown"))
    }

    @Test
    fun `package extraction handles flattened components`() {
        assertEquals("com.example.ime", packageFromComponent("com.example.ime/.ImeService"))
        assertEquals("com.example.ime", packageFromComponent("com.example.ime/com.example.ime.ImeService"))
        assertEquals("", packageFromComponent(""))
    }
}
