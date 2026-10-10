package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class XposedPackageHookRouterTest {
    private fun routes(packageName: String): List<String> {
        val installed = mutableListOf<String>()
        routeXposedPackageHooks(
            packageName,
            installSystemUi = { installed += "systemui" },
            installStatusChip = { installed += "chip" },
            installTileLabel = { installed += "tile" },
            installNfc = { installed += "nfc" },
            installMediaProvider = { installed += "media" },
            installTelephonyProvider = { installed += "telephony" },
            installInputConnection = { installed += "input" },
        )
        return installed
    }

    @Test fun systemUiInstallsItsThreeHookFamiliesOnce() {
        assertEquals(listOf("systemui", "chip", "tile"), routes("com.android.systemui"))
    }

    @Test fun privilegedProviderRoutesRemainIsolated() {
        assertEquals(listOf("nfc"), routes("com.android.nfc"))
        assertEquals(listOf("media"), routes("com.android.providers.media.module"))
        assertEquals(listOf("telephony"), routes("com.android.providers.telephony"))
    }

    @Test fun ordinaryPackagesOnlyReceiveInputHooks() {
        assertEquals(listOf("input"), routes("com.example.application"))
    }
}
