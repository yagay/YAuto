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
    @Test fun composedDispatcherMaintainsProcessHookOrder() {
        val installed = mutableListOf<String>()
        val dispatcher = XposedPackageHookDispatcher(
            systemUi = { installed += "systemui" },
            statusChip = { installed += "chip" },
            tileLabel = { installed += "tile" },
            nfc = { installed += "nfc" },
            mediaProvider = { installed += "media" },
            telephonyProvider = { installed += "telephony" },
            inputConnection = { installed += "input" },
        )
        dispatcher.install("com.android.systemui")
        assertEquals(listOf("systemui", "chip", "tile"), installed)
        installed.clear()
        dispatcher.install("com.android.nfc")
        assertEquals(listOf("nfc"), installed)
    }

    @Test fun installationStateIsIsolatedByModuleInstance() {
        val first = XposedInstallationState()
        val second = XposedInstallationState()
        first.systemRegistered.set(true)
        first.subscribedSystemEvents.set(setOf("android.event.boot"))
        first.enabledPackageBehaviors.set(setOf("test.behavior"))
        first.appReceivers.add("com.example")
        assertEquals(false, second.systemRegistered.get())
        assertEquals(emptySet<String>(), second.subscribedSystemEvents.get())
        assertEquals(emptySet<String>(), second.enabledPackageBehaviors.get())
        assertEquals(false, second.appReceivers.contains("com.example"))
    }

}
