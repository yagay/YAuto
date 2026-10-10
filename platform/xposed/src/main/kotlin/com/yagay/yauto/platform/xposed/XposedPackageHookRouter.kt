package com.yagay.yauto.platform.xposed

/** Compose independent Hook installers without using class inheritance for routing.
 * Every callback runs in its target process and keeps the existing install order.
 */
internal class XposedPackageHookDispatcher(
    private val systemUi: () -> Unit,
    private val statusChip: () -> Unit,
    private val tileLabel: () -> Unit,
    private val nfc: () -> Unit,
    private val mediaProvider: () -> Unit,
    private val telephonyProvider: () -> Unit,
    private val inputConnection: () -> Unit,
) {
    fun install(packageName: String) {
        when {
            packageName == "com.android.systemui" -> {
                systemUi()
                statusChip()
                tileLabel()
            }
            packageName == "com.android.nfc" -> nfc()
            packageName.contains("providers.media") -> mediaProvider()
            packageName == "com.android.providers.telephony" -> telephonyProvider()
            else -> inputConnection()
        }
    }
}

/** Compatibility entry for existing callers and tests; delegates to the composed dispatcher. */
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
    XposedPackageHookDispatcher(
        installSystemUi, installStatusChip, installTileLabel, installNfc,
        installMediaProvider, installTelephonyProvider, installInputConnection,
    ).install(packageName)
}
