package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidEventFeaturePack : FeaturePack {
    override val id: String = "android.events"

    override fun install(registry: FeatureRegistry) {
        simpleEvent(registry, "android.event.screen_on", "Screen on", FeatureCategory.DISPLAY)
        simpleEvent(registry, "android.event.screen_off", "Screen off", FeatureCategory.DISPLAY)
        simpleEvent(registry, "android.event.user_present", "Device unlocked", FeatureCategory.DEVICE)
        simpleEvent(registry, "android.event.power_connected", "Power connected", FeatureCategory.DEVICE)
        simpleEvent(registry, "android.event.power_disconnected", "Power disconnected", FeatureCategory.DEVICE)
        simpleEvent(registry, "android.event.battery_low", "Battery low", FeatureCategory.DEVICE)
        simpleEvent(registry, "android.event.battery_okay", "Battery okay", FeatureCategory.DEVICE)
        simpleEvent(registry, "android.event.storage_low", "Storage low", FeatureCategory.DEVICE)
        simpleEvent(registry, "android.event.storage_okay", "Storage okay", FeatureCategory.DEVICE)
        simpleEvent(registry, "android.event.airplane_mode_changed", "Airplane mode changed", FeatureCategory.NETWORK)
        simpleEvent(registry, "android.event.locale_changed", "Locale changed", FeatureCategory.SYSTEM)
        simpleEvent(registry, "android.event.timezone_changed", "Time zone changed", FeatureCategory.SYSTEM)
        simpleEvent(registry, "android.event.network_available", "Network available", FeatureCategory.NETWORK)
        simpleEvent(registry, "android.event.network_lost", "Network lost", FeatureCategory.NETWORK)
        simpleEvent(registry, "android.event.network_changed", "Network changed", FeatureCategory.NETWORK)

        packageEvent(registry, "android.event.package_added", "App installed")
        packageEvent(registry, "android.event.package_removed", "App removed")
        packageEvent(registry, "android.event.package_replaced", "App updated")

        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId("android.event.broadcast"),
                kind = FeatureKind.EVENT,
                title = "Android broadcast",
                description = "Match a runtime Android broadcast by action name",
                category = FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Text("action", "Intent action", true)),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.broadcast" &&
                ctx.event.payload.string("action") == feature.config.string("action")
        }
    }

    private fun simpleEvent(registry: FeatureRegistry, typeId: String, title: String, category: FeatureCategory) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.EVENT, title, "Android runtime event", category,
                ownerPackId = id,
            )
        ) { _, ctx -> ctx.event.typeId == typeId }
    }

    private fun packageEvent(registry: FeatureRegistry, typeId: String, title: String) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.EVENT, title, "Android package change event", FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("package", "Package filter")),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != typeId) false
            else {
                val expected = feature.config.string("package")
                expected.isBlank() || ctx.event.payload.string("package") == expected
            }
        }
    }
}
