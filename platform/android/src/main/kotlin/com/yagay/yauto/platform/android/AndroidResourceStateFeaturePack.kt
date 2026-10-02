package com.yagay.yauto.platform.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.io.File

/** Resource-health states used directly as rule constraints without requiring intermediate variables. */
class AndroidResourceStateFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.resource_states"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        pair(
            registry,
            "storage_available",
            "Storage available space",
            "Check available bytes for an accessible filesystem path",
            FeatureCategory.FILE,
            listOf(
                FieldSchema.Text("path", "Filesystem path", true),
                FieldSchema.Number("minBytes", "Minimum available bytes", min = 0.0),
                FieldSchema.Number("maxBytes", "Maximum available bytes", min = 0.0),
            ),
            setOf("storage", "free space", "available", "disk"),
        ) { feature ->
            val path = feature.config.string("path").trim()
            if (path.isBlank()) return@pair false
            val available = runCatching { StatFs(File(path).absolutePath).availableBytes.toDouble() }.getOrNull() ?: return@pair false
            matchesNumberRange(available, feature.config["minBytes"].numberOrNull(), feature.config["maxBytes"].numberOrNull())
        }

        pair(
            registry,
            "memory_available",
            "Available memory",
            "Check Android available RAM against a byte range",
            FeatureCategory.DEVICE,
            listOf(
                FieldSchema.Number("minBytes", "Minimum available bytes", min = 0.0),
                FieldSchema.Number("maxBytes", "Maximum available bytes", min = 0.0),
            ),
            setOf("memory", "ram", "available", "free memory"),
        ) { feature ->
            val info = memoryInfo()
            matchesNumberRange(info.availMem.toDouble(), feature.config["minBytes"].numberOrNull(), feature.config["maxBytes"].numberOrNull())
        }

        pair(
            registry,
            "low_memory",
            "Low memory state",
            "Check Android ActivityManager low-memory pressure state",
            FeatureCategory.DEVICE,
            listOf(FieldSchema.Toggle("value", "Low memory")),
            setOf("memory", "low memory", "pressure", "ram"),
        ) { feature -> memoryInfo().lowMemory == feature.config.boolean("value", true) }

        pair(
            registry,
            "uptime_range",
            "Device uptime range",
            "Check elapsed Android realtime since boot against a millisecond range",
            FeatureCategory.DEVICE,
            listOf(
                FieldSchema.Duration("minMs", "Minimum uptime"),
                FieldSchema.Duration("maxMs", "Maximum uptime"),
            ),
            setOf("uptime", "boot", "elapsed realtime"),
        ) { feature ->
            matchesNumberRange(
                SystemClock.elapsedRealtime().toDouble(),
                feature.config["minMs"].numberOrNull(),
                feature.config["maxMs"].numberOrNull(),
            )
        }

        pair(
            registry,
            "thermal_level",
            "Thermal severity",
            "Check whether Android thermal severity is at or above the selected level",
            FeatureCategory.DEVICE,
            listOf(FieldSchema.Choice("minimum", "Minimum thermal level", true, listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown"))),
            setOf("thermal", "heat", "temperature", "throttling"),
        ) { feature ->
            val expected = thermalLevel(feature.config.string("minimum")) ?: return@pair false
            context.getSystemService(PowerManager::class.java).currentThermalStatus >= expected
        }

        pair(
            registry,
            "battery_health",
            "Battery health state",
            "Match Android battery health reported by the battery service",
            FeatureCategory.DEVICE,
            listOf(FieldSchema.Choice("health", "Battery health", true, listOf("good", "overheat", "dead", "over_voltage", "failure", "cold", "unknown"))),
            setOf("battery", "health", "overheat", "cold"),
        ) { feature ->
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@pair false
            val actual = batteryHealthName(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN))
            actual == feature.config.string("health", "good")
        }
    }

    private fun pair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        keywords: Set<String>,
        evaluate: (com.yagay.yauto.core.model.FeatureRef) -> Boolean,
    ) {
        val state = FeatureDescriptor(
            FeatureId("android.state.$key"), FeatureKind.STATE, title, description, category,
            fields = fields, keywords = keywords, ownerPackId = id,
        )
        val condition = state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION)
        registry.registerState(state) { feature, _ -> runCatching { evaluate(feature) }.getOrDefault(false) }
        registry.registerCondition(condition) { feature, _ -> runCatching { evaluate(feature) }.getOrDefault(false) }
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also {
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }
}

internal fun matchesNumberRange(value: Double, min: Double?, max: Double?): Boolean {
    if (!value.isFinite()) return false
    val safeMin = min?.takeIf { it.isFinite() } ?: Double.NEGATIVE_INFINITY
    val safeMax = max?.takeIf { it.isFinite() } ?: Double.POSITIVE_INFINITY
    if (safeMax < safeMin) return false
    return value in safeMin..safeMax
}

internal fun thermalLevel(name: String): Int? = when (name) {
    "none" -> PowerManager.THERMAL_STATUS_NONE
    "light" -> PowerManager.THERMAL_STATUS_LIGHT
    "moderate" -> PowerManager.THERMAL_STATUS_MODERATE
    "severe" -> PowerManager.THERMAL_STATUS_SEVERE
    "critical" -> PowerManager.THERMAL_STATUS_CRITICAL
    "emergency" -> PowerManager.THERMAL_STATUS_EMERGENCY
    "shutdown" -> PowerManager.THERMAL_STATUS_SHUTDOWN
    else -> null
}
