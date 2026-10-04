package com.yagay.yauto.platform.xposed

object SystemBridgeProtocol {
    const val VERSION = 2
    const val ACTION = "com.yagay.yauto.SYSTEM_OPERATION"
    const val PERMISSION = "com.yagay.yauto.permission.SYSTEM_BRIDGE"
    const val PING = "ping"
    const val HOOK_ACTION = "com.yagay.yauto.LSPOSED_HOOK"
    const val HOOK_EVENT_ACTION = "com.yagay.yauto.LSPOSED_HOOK_EVENT"
    const val HOOK_INSTALL_SESSION = "lsposed.hook.install_session"
}
