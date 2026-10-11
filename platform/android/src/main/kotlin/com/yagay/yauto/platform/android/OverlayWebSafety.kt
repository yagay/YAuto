package com.yagay.yauto.platform.android

import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI

/** Reject non-network schemes, malformed URLs and embedded credentials. */
internal object OverlayWebUrlPolicy {
    fun accepts(url: String): Boolean = runCatching {
        val uri = URI(url)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}

internal fun configureOverlayWebView(web: WebView, javaScript: Boolean) {
    web.settings.apply {
        javaScriptEnabled = javaScript
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        safeBrowsingEnabled = true
    }
}

internal class OverlayWebNavigationClient(
    private val onFinished: (String) -> Unit = {},
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
        !OverlayWebUrlPolicy.accepts(request.url.toString())

    override fun onPageFinished(view: WebView?, url: String?) {
        onFinished(url.orEmpty())
    }
}
