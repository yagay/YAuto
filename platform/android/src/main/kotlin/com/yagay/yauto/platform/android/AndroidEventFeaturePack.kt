package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidEventFeaturePack : FeaturePack {
    override val id: String = "android.events"

    override fun install(registry: FeatureRegistry) {
        simpleEvent(registry, "android.event.boot", "Device boot", FeatureCategory.SYSTEM)
        simpleEvent(registry, "android.event.battery_changed", "Battery changed", FeatureCategory.DEVICE)
        simpleEvent(registry, "android.event.power_save_changed", "Power saving mode changed", FeatureCategory.DEVICE)
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
        simpleEvent(registry, "android.event.time_changed", "System time changed", FeatureCategory.SYSTEM)
        simpleEvent(registry, "android.event.date_changed", "Date changed", FeatureCategory.SYSTEM)
        simpleEvent(registry, "android.event.network_available", "Network available", FeatureCategory.NETWORK)
        simpleEvent(registry, "android.event.network_lost", "Network lost", FeatureCategory.NETWORK)
        simpleEvent(registry, "android.event.network_changed", "Network changed", FeatureCategory.NETWORK)

        timeTickEvent(registry)
        clipboardEvent(registry)
        packageEvent(registry, "android.event.package_added", "App installed")
        packageEvent(registry, "android.event.package_removed", "App removed")
        packageEvent(registry, "android.event.package_replaced", "App updated")
        notificationEvent(registry, "android.event.notification_posted", "Notification posted")
        notificationEvent(registry, "android.event.notification_removed", "Notification removed")
        broadcastEvent(registry)
    }

    private fun timeTickEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.time_tick"), FeatureKind.EVENT,
                "Time / every minute", "Run on Android's minute tick and optionally match hour, minute or ISO weekday",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Number("hour", "Hour (0-23)", min = 0.0, max = 23.0),
                    FieldSchema.Number("minute", "Minute (0-59)", min = 0.0, max = 59.0),
                    FieldSchema.Text("weekdays", "Weekdays (1=Mon … 7=Sun, comma separated)"),
                ),
                keywords = setOf("time", "clock", "minute", "schedule", "时间", "定时"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.time_tick") return@registerEvent false
            val expectedHour = feature.config["hour"].numberOrNull()?.toInt()
            val expectedMinute = feature.config["minute"].numberOrNull()?.toInt()
            val actualHour = ctx.event.payload["hour"].numberOrNull()?.toInt()
            val actualMinute = ctx.event.payload["minute"].numberOrNull()?.toInt()
            val actualWeekday = ctx.event.payload["weekday"].numberOrNull()?.toInt()
            if (expectedHour != null && actualHour != expectedHour) return@registerEvent false
            if (expectedMinute != null && actualMinute != expectedMinute) return@registerEvent false
            val weekdays = feature.config.string("weekdays").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
            weekdays.isEmpty() || actualWeekday in weekdays
        }
    }

    private fun clipboardEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.clipboard_changed"), FeatureKind.EVENT,
                "Clipboard changed", "Run when the primary clipboard changes; clipboard text may be unavailable in the background on newer Android versions",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("textContains", "Text contains"),
                    FieldSchema.Choice("hasText", "Clipboard text", options = listOf("any", "has_text", "empty")),
                ),
                keywords = setOf("clipboard", "copy", "剪贴板", "复制"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.clipboard_changed") return@registerEvent false
            val text = ctx.event.payload.string("text")
            val contains = feature.config.string("textContains")
            val textMode = feature.config.string("hasText", "any")
            (contains.isBlank() || text.contains(contains, ignoreCase = true)) && when (textMode) {
                "has_text" -> text.isNotEmpty()
                "empty" -> text.isEmpty()
                else -> true
            }
        }
    }

    private fun broadcastEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId("android.event.broadcast"),
                kind = FeatureKind.EVENT,
                title = "Android broadcast",
                description = "Receive a configured Android broadcast action and optionally match simple extras",
                category = FeatureCategory.SYSTEM,
                fields = buildList {
                    add(FieldSchema.Text("action", "Intent action", true))
                    for (index in 1..3) {
                        add(FieldSchema.Text("extra${index}Key", "Extra $index key"))
                        add(FieldSchema.Text("extra${index}Value", "Extra $index exact value"))
                    }
                },
                keywords = setOf("intent", "broadcast", "receiver"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.broadcast") return@registerEvent false
            if (ctx.event.payload.string("action") != feature.config.string("action").resolveVariables(ctx.variables)) return@registerEvent false
            val extras = (ctx.event.payload["extras"] as? ConfigValue.ObjectValue)?.value.orEmpty()
            for (index in 1..3) {
                val key = feature.config.string("extra${index}Key").resolveVariables(ctx.variables).trim()
                if (key.isBlank()) continue
                val expected = feature.config.string("extra${index}Value").resolveVariables(ctx.variables)
                val actual = extras[key].simpleText()
                if (actual != expected) return@registerEvent false
            }
            true
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

    private fun notificationEvent(registry: FeatureRegistry, typeId: String, title: String) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.EVENT, title, "Match Android notifications by app and content", FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Text("channel", "Channel ID"),
                    FieldSchema.Text("titleContains", "Title contains"),
                    FieldSchema.Text("textContains", "Text contains"),
                    FieldSchema.Choice("ongoing", "Ongoing notification", options = listOf("any", "only", "exclude")),
                ),
                accessRequirements = setOf(AccessRequirement.NOTIFICATION_LISTENER),
                keywords = setOf("notification", "通知", "message"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != typeId) return@registerEvent false
            val event = ctx.event.payload
            val packageFilter = feature.config.string("package")
            val channelFilter = feature.config.string("channel")
            val titleFilter = feature.config.string("titleContains")
            val textFilter = feature.config.string("textContains")
            val ongoingMatches = when (feature.config.string("ongoing", "any")) {
                "only" -> event.boolean("ongoing")
                "exclude" -> !event.boolean("ongoing")
                else -> true
            }
            ongoingMatches &&
                (packageFilter.isBlank() || event.string("package") == packageFilter) &&
                (channelFilter.isBlank() || event.string("channel") == channelFilter) &&
                (titleFilter.isBlank() || event.string("title").contains(titleFilter, ignoreCase = true)) &&
                (textFilter.isBlank() || event.string("text").contains(textFilter, ignoreCase = true))
        }
    }
}

private fun ConfigValue?.simpleText(): String = when (this) {
    null, ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.ListValue -> value.joinToString(",") { it.simpleText() }
    is ConfigValue.ObjectValue -> value.toString()
}
