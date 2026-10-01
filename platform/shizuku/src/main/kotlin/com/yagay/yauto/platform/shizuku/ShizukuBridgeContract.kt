package com.yagay.yauto.platform.shizuku

/** SDK-neutral boundary. A future Shizuku SDK adapter implements this without leaking SDK types into core. */
interface ShizukuBridgeContract {
    fun isAvailable(): Boolean
    fun hasPermission(): Boolean
}
