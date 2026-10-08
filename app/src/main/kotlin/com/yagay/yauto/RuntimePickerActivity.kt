package com.yagay.yauto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.yagay.yauto.platform.android.PickerRuntimeBridge

class RuntimePickerActivity : Activity() {
    private var token: String = ""
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (token.isBlank()) {
            finish()
            return
        }
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val multiple = intent.getBooleanExtra(EXTRA_MULTIPLE, false)
        val mime = when (mode) {
            MODE_PHOTO -> "image/*"
            else -> intent.getStringExtra(EXTRA_MIME).orEmpty().ifBlank { "*/*" }
        }
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(picker, REQUEST_PICK) }
            .onFailure {
                complete(null)
            }
    }

    @Deprecated("Activity result compatibility path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK) return
        if (resultCode != RESULT_OK || data == null) {
            complete(null)
            return
        }
        val values = LinkedHashSet<String>()
        data.data?.let { uri ->
            takeReadGrant(data, uri)
            values += uri.toString()
        }
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                val uri = clip.getItemAt(index).uri ?: continue
                takeReadGrant(data, uri)
                values += uri.toString()
            }
        }
        complete(values.toList().takeIf { it.isNotEmpty() })
    }

    private fun takeReadGrant(data: Intent, uri: android.net.Uri) {
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching {
            contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun complete(values: List<String>?) {
        if (completed) return
        completed = true
        PickerRuntimeBridge.complete(token, values)
        finish()
    }

    override fun onDestroy() {
        if (isFinishing && !completed && token.isNotBlank()) {
            PickerRuntimeBridge.complete(token, null)
        }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_PICK = 9127
        const val EXTRA_TOKEN = "token"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MULTIPLE = "multiple"
        const val EXTRA_MIME = "mime"
        const val MODE_FILE = "file"
        const val MODE_PHOTO = "photo"
    }
}
