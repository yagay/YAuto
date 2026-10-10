package com.yagay.yauto.platform.xposed

/**
 * Explicit owner for process-specific installers. Keeps process routing independent
 * of the Xposed module inheritance hierarchy and preserves hook installation order.
 */
internal class XposedPackageHookCoordinator(
    private val systemUi: () -> Unit,
    private val statusChip: () -> Unit,
    private val tileLabel: () -> Unit,
    private val nfc: () -> Unit,
    private val mediaProvider: () -> Unit,
    private val telephonyProvider: () -> Unit,
    private val inputConnection: () -> Unit,
) {
    fun install(packageName: String) = XposedPackageHookDispatcher(
        systemUi = systemUi,
        statusChip = statusChip,
        tileLabel = tileLabel,
        nfc = nfc,
        mediaProvider = mediaProvider,
        telephonyProvider = telephonyProvider,
        inputConnection = inputConnection,
    ).install(packageName)
}
