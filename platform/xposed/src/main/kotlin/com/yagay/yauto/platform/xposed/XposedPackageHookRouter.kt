package com.yagay.yauto.platform.xposed

/** The canonical process-to-hook routing policy shared by LSPosed package callbacks. */
internal fun routeXposedPackageHooks(
    packageName: String,
    installSystemUi: () -> Unit,
    installStatusChip: () -> Unit,
    installTileLabel: () -> Unit,
    installNfc: () -> Unit,
    installMediaProvider: () -> Unit,
    installTelephonyProvider: () -> Unit,
    installInputConnection: () -> Unit,
) {
    when {
        packageName == "com.android.systemui" -> {
            installSystemUi()
            installStatusChip()
            installTileLabel()
        }
        packageName == "com.android.nfc" -> installNfc()
        packageName.contains("providers.media") -> installMediaProvider()
        packageName == "com.android.providers.telephony" -> installTelephonyProvider()
        else -> installInputConnection()
    }
}
