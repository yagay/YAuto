package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/** Periodic trigger whose runtime scheduling is owned by [ConfiguredIntervalEventSource]. */
class AndroidIntervalFeaturePack : FeaturePack {
    override val id: String = "android.interval"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.interval"),
                FeatureKind.EVENT,
                "Interval timer",
                "Run repeatedly at a configured interval while the YAuto runtime is active",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Duration("intervalMs", "Interval", true),
                    FieldSchema.Toggle("fireImmediately", "Fire immediately when enabled"),
                ),
                keywords = setOf("interval", "timer", "periodic", "repeat", "schedule"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.interval" &&
                ctx.event.payload.string("subscription") == intervalSubscriptionKey(feature)
        }
    }
}

internal fun intervalSubscriptionKey(feature: FeatureRef): String = listOf(
    feature.typeId,
    intervalDurationMs(feature).toString(),
    feature.config.boolean("fireImmediately").toString(),
).joinToString("|")

internal fun intervalDurationMs(feature: FeatureRef): Long =
    feature.config.long("intervalMs", 60_000L).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

internal const val MIN_INTERVAL_MS = 1_000L
internal const val MAX_INTERVAL_MS = 7L * 24 * 60 * 60 * 1_000
