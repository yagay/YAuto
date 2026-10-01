package com.yagay.yauto.platform.xposed

/**
 * Stable boundary between the app/engine and future LSPosed implementation.
 * Hook code must live behind this contract; feature modules must never call Xposed APIs directly.
 */
interface XposedBridgeContract {
    val protocolVersion: Int
    fun isConnected(): Boolean
    fun supportedOperations(): Set<String>
}
