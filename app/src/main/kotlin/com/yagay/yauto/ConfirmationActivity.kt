package com.yagay.yauto

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import com.yagay.yauto.platform.android.ConfirmationRuntimeBridge

class ConfirmationActivity : Activity() {
    private var token: String = ""
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra("token").orEmpty()
        if (token.isBlank()) {
            finish()
            return
        }
        val title = intent.getStringExtra("title").orEmpty()
        val message = intent.getStringExtra("message").orEmpty()
        val positive = intent.getStringExtra("positive").orEmpty().ifBlank { "OK" }
        val negative = intent.getStringExtra("negative").orEmpty().ifBlank { "Cancel" }
        AlertDialog.Builder(this)
            .setTitle(title.takeIf(String::isNotBlank))
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> complete(true) }
            .setNegativeButton(negative) { _, _ -> complete(false) }
            .setOnCancelListener { complete(false) }
            .setOnDismissListener { if (!completed) complete(false) }
            .show()
    }

    private fun complete(value: Boolean) {
        if (completed) return
        completed = true
        ConfirmationRuntimeBridge.complete(token, value)
        finish()
    }

    override fun onDestroy() {
        if (isFinishing && !completed && token.isNotBlank()) {
            ConfirmationRuntimeBridge.complete(token, false)
        }
        super.onDestroy()
    }
}
