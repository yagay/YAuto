package com.yagay.yauto.platform.xposed

import org.junit.Assert.*
import org.junit.Test

class XposedPackageInitPolicyTest {
    @Test fun excludesFrameworkAndOwnApplication() {
        assertFalse(XposedPackageInitPolicy.shouldInitialize("android", true, "com.yagay.yauto"))
        assertFalse(XposedPackageInitPolicy.shouldInitialize("com.yagay.yauto", true, "com.yagay.yauto"))
        assertFalse(XposedPackageInitPolicy.shouldInitialize("", true, "com.yagay.yauto"))
    }

    @Test fun firstPackageCallbackOnly() {
        assertFalse(XposedPackageInitPolicy.shouldInitialize("com.example.app", false, "com.yagay.yauto"))
        assertTrue(XposedPackageInitPolicy.shouldInitialize("com.example.app", true, "com.yagay.yauto"))
        assertTrue(XposedPackageInitPolicy.shouldInitialize("com.android.systemui", true, "com.yagay.yauto"))
    }
}
