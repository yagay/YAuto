package com.yagay.yauto.core.capability

/**
 * Canonical privileged operation support contract. Platform backends own execution,
 * while this contract prevents the shell and LSPosed paths from advertising
 * operations they cannot handle.
 */
object PrivilegedOperationContract {
    fun rootSystemUiSupported(operationId: String): Boolean =
        SystemOperations.shellCommand(operationId) != null

    val lsposedSystemUiOperations: Set<String> = setOf(
        SystemOperations.SLEEP,
        SystemOperations.WAKE,
        SystemOperations.EXPAND_NOTIFICATIONS,
        SystemOperations.EXPAND_QUICK_SETTINGS,
        SystemOperations.COLLAPSE_PANELS,
        SystemOperations.REBOOT,
        SystemOperations.REBOOT_RECOVERY,
        SystemOperations.REBOOT_BOOTLOADER,
        SystemOperations.SHUTDOWN,
        SystemOperations.SENSORS_OFF_ENABLE,
        SystemOperations.SENSORS_OFF_DISABLE,
        SystemOperations.SENSORS_OFF_QUERY,
    )

    fun lsposedSystemUiSupported(operationId: String): Boolean =
        operationId in lsposedSystemUiOperations
}
