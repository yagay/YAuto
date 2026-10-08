package com.yagay.yauto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable

/**
 * Share-sheet entrypoint used as a real automation trigger, matching the Share Text / Share File
 * trigger family exposed by MacroDroid and ShortX.
 */
class ShareDispatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatch(intent)
        finish()
    }

    private fun dispatch(source: Intent) {
        if (source.action != Intent.ACTION_SEND && source.action != Intent.ACTION_SEND_MULTIPLE) return
        val text = source.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val subject = source.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString().orEmpty()
        val uris = linkedSetOf<String>()

        source.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.toString()?.takeIf { it.isNotBlank() }?.let(uris::add)
            }
        }
        if (source.action == Intent.ACTION_SEND) {
            parcelableUri(source, Intent.EXTRA_STREAM)?.toString()?.takeIf { it.isNotBlank() }?.let(uris::add)
        } else {
            parcelableUriList(source, Intent.EXTRA_STREAM).map(Uri::toString).filter(String::isNotBlank).forEach(uris::add)
        }

        AutomationRuntimeService.startShareDispatch(
            context = this,
            text = text,
            subject = subject,
            mimeType = source.type.orEmpty(),
            uris = uris.toList(),
        )
    }

    @Suppress("DEPRECATION")
    private fun parcelableUri(intent: Intent, key: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Uri::class.java)
        else intent.getParcelableExtra<Parcelable>(key) as? Uri

    @Suppress("DEPRECATION")
    private fun parcelableUriList(intent: Intent, key: String): List<Uri> =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Parcelable>(key).orEmpty().mapNotNull { it as? Uri }
        }
}
