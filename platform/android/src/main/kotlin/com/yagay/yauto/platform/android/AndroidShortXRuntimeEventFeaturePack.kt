package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

private fun shortXScreenRuleKey(feature: com.yagay.yauto.core.model.FeatureRef): String =
    feature.config.string("from", "screen_on") + ":" + (feature.config["seconds"].numberOrNull() ?: 0.0)

class AndroidShortXRuntimeEventFeaturePack : FeaturePack {
    override val id: String = "android.shortx.runtime_events"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.screen_on_duration"), FeatureKind.EVENT,
                "Screen-on duration reached",
                "Run once when the selected screen/system-ready duration reaches the configured threshold",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("seconds", "Duration seconds", true, min = 0.0, max = 2_592_000.0),
                    FieldSchema.Choice("from", "Measure from", options = listOf("screen_on", "system_ready")),
                ),
                keywords = setOf("screen on time", "duration", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.screen_on_duration") return@registerEvent false
            val wantedFrom = feature.config.string("from", "screen_on")
            val threshold = feature.config["seconds"].numberOrNull() ?: 0.0
            ctx.event.payload.string("from") == wantedFrom &&
                ctx.event.payload.string("ruleKey") == shortXScreenRuleKey(feature) &&
                (ctx.event.payload["seconds"].numberOrNull() ?: 0.0) >= threshold
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.cpu_availability"), FeatureKind.EVENT,
                "CPU availability",
                "Run on sampled CPU availability while the latest and rolling-average percentages match thresholds",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("latestMinPercent", "Latest availability at least %", min = 0.0, max = 100.0),
                    FieldSchema.Number("averageMinPercent", "Rolling average at least %", min = 0.0, max = 100.0),
                    FieldSchema.Duration("pastWindowMs", "Rolling-average window"),
                ),
                keywords = setOf("cpu", "availability", "idle", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.cpu_availability") return@registerEvent false
            val latest = ctx.event.payload["latestPercent"].numberOrNull() ?: return@registerEvent false
            val average = ctx.event.payload["averagePercent"].numberOrNull() ?: return@registerEvent false
            latest >= (feature.config["latestMinPercent"].numberOrNull() ?: 0.0) &&
                average >= (feature.config["averageMinPercent"].numberOrNull() ?: 0.0)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.sound_level"), FeatureKind.EVENT,
                "Sound level",
                "Run when a microphone RMS/dBFS sample is inside the configured range",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Number("minDbfs", "Minimum dBFS", min = -120.0, max = 0.0),
                    FieldSchema.Number("maxDbfs", "Maximum dBFS", min = -120.0, max = 0.0),
                    FieldSchema.Duration("intervalMs", "Sampling interval"),
                    FieldSchema.Duration("sampleMs", "Sample duration"),
                ),
                accessRequirements = setOf(AccessRequirement.RECORD_AUDIO),
                keywords = setOf("sound", "microphone", "dbfs", "noise", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.sound_level") return@registerEvent false
            if (ctx.event.payload.string("ruleKey") != soundRuleKey(feature)) return@registerEvent false
            val value = ctx.event.payload["dbfs"].numberOrNull() ?: return@registerEvent false
            val min = feature.config["minDbfs"].numberOrNull() ?: -120.0
            val max = feature.config["maxDbfs"].numberOrNull() ?: 0.0
            min <= max && value in min..max
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.user_present_first_after_boot"), FeatureKind.EVENT,
                "First unlock after boot",
                "Run only on the first USER_PRESENT event in the current Android boot",
                FeatureCategory.SYSTEM,
                keywords = setOf("first unlock", "user present", "boot", "shortx"),
                ownerPackId = id,
            )
        ) { _, ctx -> ctx.event.typeId == "android.event.user_present_first_after_boot" }
    }
}
