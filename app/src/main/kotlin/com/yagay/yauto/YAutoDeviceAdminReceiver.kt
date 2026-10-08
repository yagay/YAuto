package com.yagay.yauto

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class YAutoDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent) {
        AutomationRuntimeService.startSecurityEvent(context, "android.event.failed_unlock")
    }
}
