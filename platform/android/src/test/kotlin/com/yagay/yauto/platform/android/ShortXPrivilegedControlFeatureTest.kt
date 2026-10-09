package com.yagay.yauto.platform.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortXPrivilegedControlFeatureTest {
    @Test fun `only ordinary package names can be sent to the process manager`() {
        assertTrue(processPackageNameValid("com.example.application"))
        assertTrue(processPackageNameValid("org.example_app.work"))
        assertFalse(processPackageNameValid("android"))
        assertFalse(processPackageNameValid("com.example;reboot"))
        assertFalse(processPackageNameValid("com.example/name"))
        assertFalse(processPackageNameValid(""))
    }

    @Test fun `chip identifiers and framework drawables are restricted`() {
        assertTrue(statusChipIdValid("shortx"))
        assertTrue(statusChipIdValid("my_chip_2"))
        assertFalse(statusChipIdValid("wifi:system"))
        assertFalse(statusChipIdValid("../status"))
        assertFalse(statusChipIdValid("UPPER"))
        assertTrue(statusChipDrawableValid("ic_dialog_info"))
        assertFalse(statusChipDrawableValid("android:drawable/ic_dialog_info"))
        assertFalse(statusFrameworkDrawableValid("../../some/other"))
    }

    @Test fun `invalid or excessive png payloads are rejected before cross-process dispatch`() {
        assertFalse(statusChipImageValid(byteArrayOf()))
        assertFalse(statusChipImageValid(ByteArray(70_000)))
        assertFalse(statusChipImageValid("not a png".toByteArray()))
    }
}
