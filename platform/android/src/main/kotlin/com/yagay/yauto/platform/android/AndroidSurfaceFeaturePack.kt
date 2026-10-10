package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.userText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidSurfaceFeaturePack(
    internal val controller: OverlaySurfaceController,
) : FeaturePack {
    override val id = "android.surface"

    override fun install(registry: FeatureRegistry) {
        registerSurfaceCore(registry)
        registerSurfaceInteractive(registry)
        registerSurfaceEventsAndGestures(registry)
    }

    internal fun compactSurfaceFields(): List<FieldSchema> = listOf(
        FieldSchema.Text("surfaceId", "Surface ID", true),
        FieldSchema.Text("text", "Text", true),
        FieldSchema.Text("action", "Action name"),
        FieldSchema.Choice("gravity", "Position", true, listOf("center", "top", "bottom", "left", "right", "top_left", "top_right", "bottom_left", "bottom_right")),
        FieldSchema.Duration("autoHideMs", "Auto hide after"),
    )

    internal fun parseSurfaceItems(raw: String): List<Pair<String, String>> = raw.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) line to line else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
        .take(100)
        .toList()
}
