package com.yagay.yauto.platform.xposed

object SystemBridgeProtocol {
    const val VERSION = 2
    const val ACTION = "com.yagay.yauto.SYSTEM_OPERATION"
    const val PERMISSION = "com.yagay.yauto.permission.SYSTEM_BRIDGE"
    const val PING = "ping"
    const val HARDWARE_KEY_CAPTURE_START = "hardware_key_capture.start"
    const val SYSTEM_EVENT_SUBSCRIPTIONS_SET = "system_event_subscriptions.set"
    const val HARDWARE_KEY_CAPTURE_TYPE = "yauto.capture.hardware_key"
    const val HOOK_ACTION = "com.yagay.yauto.LSPOSED_HOOK"
    const val HOOK_EVENT_ACTION = "com.yagay.yauto.LSPOSED_HOOK_EVENT"
    const val SYSTEM_EVENT_ACTION = "com.yagay.yauto.LSPOSED_SYSTEM_EVENT"
    const val HOOK_INSTALL_SESSION = "lsposed.hook.install_session"
}
