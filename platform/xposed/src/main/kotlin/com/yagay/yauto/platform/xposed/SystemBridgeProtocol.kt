package com.yagay.yauto.platform.xposed

object SystemBridgeProtocol {
    const val VERSION = 3
    const val ACTION = "com.yagay.yauto.SYSTEM_OPERATION"
    const val PERMISSION = "com.yagay.yauto.permission.SYSTEM_BRIDGE"
    const val PING = "ping"
    const val APP_PROCESS_START = "app_process.start"
    const val CHIP_ACTION = "com.yagay.yauto.SYSTEM_UI_CHIP"
    const val STATUS_CHIP_SHOW = "status_chip.show"
    const val STATUS_CHIP_HIDE = "status_chip.hide"
    const val STATUS_ICON_SET = "status_icon.set"
    const val STATUS_ICON_REMOVE = "status_icon.remove"
    const val HARDWARE_KEY_CAPTURE_START = "hardware_key_capture.start"
    const val SYSTEM_EVENT_SUBSCRIPTIONS_SET = "system_event_subscriptions.set"
    const val SHORTX_BEHAVIOR_SET = "shortx.behavior.set"
    const val SHORTX_BEHAVIOR_ACCESSIBILITY = "accessibility_access"
    const val SHORTX_BEHAVIOR_CLIPBOARD = "clipboard_access"
    const val SHORTX_BEHAVIOR_PERMISSION = "permission_bridge"
    const val SHORTX_PACKAGE_BEHAVIOR_SET = "shortx.package_behavior.set"
    const val SHORTX_PACKAGE_BEHAVIOR_RENDERNODE_GUARD = "rendernode_guard"
    const val HARDWARE_KEY_CAPTURE_TYPE = "yauto.capture.hardware_key"
    const val HOOK_ACTION = "com.yagay.yauto.LSPOSED_HOOK"
    const val HOOK_EVENT_ACTION = "com.yagay.yauto.LSPOSED_HOOK_EVENT"
    const val SYSTEM_EVENT_ACTION = "com.yagay.yauto.LSPOSED_SYSTEM_EVENT"
    const val HOOK_INSTALL_SESSION = "lsposed.hook.install_session"
}
