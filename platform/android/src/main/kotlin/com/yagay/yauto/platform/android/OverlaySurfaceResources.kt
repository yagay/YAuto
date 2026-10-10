package com.yagay.yauto.platform.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity

/** Stateless overlay placement and URI-backed image decoding shared by surface types. */
internal fun gravityValue(gravity: String): Int = when (gravity) {
        "top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        "left" -> Gravity.CENTER_VERTICAL or Gravity.START
        "right" -> Gravity.CENTER_VERTICAL or Gravity.END
        "top_left" -> Gravity.TOP or Gravity.START
        "top_right" -> Gravity.TOP or Gravity.END
        "bottom_left" -> Gravity.BOTTOM or Gravity.START
        "bottom_right" -> Gravity.BOTTOM or Gravity.END
        else -> Gravity.CENTER
    }

 
internal fun loadOverlayBitmap(context: android.content.Context, source: String): Bitmap? = runCatching {
        when {
            source.startsWith("content://") -> context.contentResolver.openInputStream(android.net.Uri.parse(source))?.use(BitmapFactory::decodeStream)
            source.startsWith("file://") -> BitmapFactory.decodeFile(android.net.Uri.parse(source).path)
            else -> BitmapFactory.decodeFile(source)
        }
    }.getOrNull()

