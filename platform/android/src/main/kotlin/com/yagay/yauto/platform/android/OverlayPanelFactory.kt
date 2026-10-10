package com.yagay.yauto.platform.android

import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

/** Shared overlay panel visuals, independent from surface lifecycle and gestures. */
internal fun createOverlayBasePanel(context: Context, title: String, density: Float): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(0xEE202124.toInt())
        }
        if (title.isNotBlank()) addView(TextView(context).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
    }

