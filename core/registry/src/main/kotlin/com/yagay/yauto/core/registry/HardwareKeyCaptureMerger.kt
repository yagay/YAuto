package com.yagay.yauto.core.registry

/**
 * Resolve one physical key release from independent observers.
 * Never use a time window alone as proof of a shared hardware identity.
 */
fun mergeHardwareKeyCaptures(
    captures: List<HardwareKeyCaptureResult>,
): HardwareKeyCaptureResult? {
    if (captures.isEmpty()) return null
    // Rank observations, but only merge identities proved compatible. A
    // 240-ms time window alone does not prove two rapid presses are the same.
    val ranked = captures.sortedWith(compareByDescending<HardwareKeyCaptureResult> {
        when {
            "lsposed" in it.sources -> 40
            "root.input" in it.sources -> 30
            "shizuku" in it.sources -> 20
            "accessibility" in it.sources -> 10
            else -> 0
        }
    }.thenByDescending { if (it.keyCode > 0) 2 else if (it.scanCode > 0) 1 else 0 })
    val primary = ranked.first()
    val samePress = ranked.filter { candidate ->
        if (candidate === primary) true
        else if (candidate.deviceId >= 0 && primary.deviceId >= 0 &&
            candidate.deviceId != primary.deviceId
        ) false
        else if (candidate.deviceDescriptor.startsWith("/dev/input/") &&
            primary.deviceDescriptor.startsWith("/dev/input/") &&
            candidate.deviceDescriptor != primary.deviceDescriptor
        ) false
        else when {
            primary.keyCode > 0 && candidate.keyCode > 0 ->
                primary.keyCode == candidate.keyCode
            primary.linuxEvKey > 0 && candidate.linuxEvKey > 0 ->
                primary.linuxEvKey == candidate.linuxEvKey
            primary.scanCode > 0 && candidate.scanCode > 0 ->
                primary.scanCode == candidate.scanCode
            else -> false
        }
    }
    val android = samePress.firstOrNull { it.keyCode > 0 || it.scanCode > 0 }
    val raw = samePress.firstOrNull { it.linuxEvKey > 0 || it.mscScan != 0L }
    return HardwareKeyCaptureResult(
        keyCode = android?.keyCode ?: primary.keyCode,
        scanCode = android?.scanCode ?: primary.scanCode,
        deviceId = android?.deviceId ?: primary.deviceId,
        action = primary.action,
        linuxEvKey = raw?.linuxEvKey ?: primary.linuxEvKey,
        mscScan = raw?.mscScan ?: primary.mscScan,
        deviceName = android?.deviceName.orEmpty().ifBlank { primary.deviceName },
        deviceDescriptor = raw?.deviceDescriptor.orEmpty().ifBlank {
            android?.deviceDescriptor.orEmpty().ifBlank { primary.deviceDescriptor }
        },
        vendorId = android?.vendorId?.takeIf { it > 0 } ?: primary.vendorId,
        productId = android?.productId?.takeIf { it > 0 } ?: primary.productId,
        sources = samePress.flatMapTo(linkedSetOf()) { it.sources },
    )
}
