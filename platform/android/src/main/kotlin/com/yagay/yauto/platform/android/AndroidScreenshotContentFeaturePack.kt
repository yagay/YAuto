package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/** React to text detected in actual screenshots, not Accessibility node text changes. */
class AndroidScreenshotContentFeaturePack : FeaturePack {
    override val id = "android.screenshot_content"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.screenshot_content"),
                FeatureKind.EVENT,
                "Screenshot contains text",
                "Run once when specified text appears in an on-device screenshot OCR scan; checks about every 10 seconds while this trigger is enabled",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("textContains", "Text to detect", true),
                    FieldSchema.Toggle("ignoreCase", "Ignore letter case"),
                ),
                accessRequirements = setOf(AccessRequirement.ACCESSIBILITY),
                keywords = setOf("screen screenshot OCR", "image text changed", "macrodroid", "screenshot content"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.screenshot_content") return@registerEvent false
            val expected = feature.config.string("textContains").trim()
            if (expected.isBlank()) return@registerEvent false
            val ignoreCase = feature.config.boolean("ignoreCase", true)
            screenshotOcrMatchIsForTrigger(
                expected, ignoreCase,
                ctx.event.payload.string("match"),
                ctx.event.payload.boolean("ignoreCase", true),
            )
        }
    }
}

internal fun screenshotOcrMatchIsForTrigger(
    requested: String,
    requestedIgnoreCase: Boolean,
    eventText: String,
    eventIgnoreCase: Boolean,
): Boolean = requested.isNotBlank() &&
    requestedIgnoreCase == eventIgnoreCase &&
    requested.equals(eventText, ignoreCase = requestedIgnoreCase)
