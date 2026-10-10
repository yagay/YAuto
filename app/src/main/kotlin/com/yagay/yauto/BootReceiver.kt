package com.yagay.yauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            RuntimeSettingsPreferences.startAtBoot(context)) {
            AutomationRuntimeService.start(context, boot = true)
        }
    }
}
