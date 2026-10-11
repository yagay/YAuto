package com.yagay.yauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yagay.yauto.platform.android.ExternalCommandTokenStore

/** Authenticate on receipt, then let the foreground service own execution. */
class ExternalCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return
        if (!ExternalCommandTokenStore(context).validate(intent.getStringExtra("token").orEmpty())) return
        AutomationRuntimeService.startExternalCommand(
            context,
            intent.getStringExtra("name").orEmpty(),
            intent.getStringExtra("payload").orEmpty(),
            intent.getStringExtra("senderPackage").orEmpty(),
        )
    }
    companion object { const val ACTION_COMMAND = "com.yagay.yauto.COMMAND" }
}
