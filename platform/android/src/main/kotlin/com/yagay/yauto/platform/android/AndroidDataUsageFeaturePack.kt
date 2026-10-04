package com.yagay.yauto.platform.android

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidDataUsageFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.data_usage"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.data_usage.query"), FeatureKind.ACTION,
                "Query app data usage", "Query an app's Wi-Fi/mobile received and transmitted bytes for a time window",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Choice("network", "Network", true, listOf("all", "wifi", "mobile")),
                    FieldSchema.Number("pastHours", "Past hours", min = 0.01, max = 8760.0),
                    FieldSchema.Variable("resultVariable", "Store usage object", true),
                ),
                accessRequirements = setOf(AccessRequirement.USAGE_STATS),
                keywords = setOf("data usage", "network stats", "traffic", "bytes", "wifi", "mobile"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
                ?: return@registerAction ActionExecutionResult(false)
            val hours = (feature.config["pastHours"].numberOrNull() ?: 24.0).coerceIn(0.01, 8760.0)
            val end = System.currentTimeMillis()
            val usage = queryUidUsage(
                context, uid, end - (hours * 3_600_000.0).toLong(), end,
                feature.config.string("network", "all"),
            )
            val output = usage.toConfig()
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.data_usage_threshold"), FeatureKind.EVENT,
                "Data usage threshold", "Run when an app reaches a configured network-usage threshold",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Choice("network", "Network", true, listOf("all", "wifi", "mobile")),
                    FieldSchema.Number("pastHours", "Past hours", min = 0.01, max = 8760.0),
                    FieldSchema.Choice("direction", "Direction", true, listOf("total", "received", "transmitted")),
                    FieldSchema.Number("thresholdBytes", "Threshold bytes", true, min = 1.0),
                ),
                accessRequirements = setOf(AccessRequirement.USAGE_STATS),
                keywords = setOf("data usage", "trigger", "threshold", "traffic", "bytes"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.data_usage_threshold") return@registerEvent false
            val pkg = feature.config.string("package")
            pkg.isBlank() || ctx.event.payload.string("package") == pkg
        }

        val fields = listOf(
            FieldSchema.AppPicker("package", "App / package", true),
            FieldSchema.Choice("network", "Network", true, listOf("all", "wifi", "mobile")),
            FieldSchema.Number("pastHours", "Past hours", min = 0.01, max = 8760.0),
            FieldSchema.Choice("direction", "Direction", true, listOf("total", "received", "transmitted")),
            FieldSchema.Number("minBytes", "Minimum bytes", min = 0.0),
            FieldSchema.Number("maxBytes", "Maximum bytes", min = 0.0),
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
                ?: return@ConditionEvaluator false
            val hours = (feature.config["pastHours"].numberOrNull() ?: 24.0).coerceIn(0.01, 8760.0)
            val end = System.currentTimeMillis()
            val usage = queryUidUsage(
                context, uid, end - (hours * 3_600_000.0).toLong(), end,
                feature.config.string("network", "all"),
            )
            val value = when (feature.config.string("direction", "total")) {
                "received" -> usage.rxBytes
                "transmitted" -> usage.txBytes
                else -> usage.rxBytes + usage.txBytes
            }.toDouble()
            val min = feature.config["minBytes"].numberOrNull() ?: 0.0
            val max = feature.config["maxBytes"].numberOrNull() ?: Double.MAX_VALUE
            min <= max && value in min..max
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.data_usage"), FeatureKind.STATE,
            "App data usage", "Compare an app's network data usage in a time window",
            FeatureCategory.NETWORK,
            fields = fields,
            accessRequirements = setOf(AccessRequirement.USAGE_STATS),
            keywords = setOf("data usage", "traffic", "bytes", "network stats"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.data_usage"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }
}

internal data class Usage(val rxBytes: Long, val txBytes: Long) {
    fun toConfig(): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
        mapOf(
            "receivedBytes" to ConfigValue.NumberValue(rxBytes.toDouble()),
            "transmittedBytes" to ConfigValue.NumberValue(txBytes.toDouble()),
            "totalBytes" to ConfigValue.NumberValue((rxBytes + txBytes).toDouble()),
        )
    )
}

internal fun queryUidUsage(context: Context, uid: Int, start: Long, end: Long, network: String): Usage {
    val stats = context.getSystemService(NetworkStatsManager::class.java)
    var rx = 0L
    var tx = 0L
    val types = when (network) {
        "wifi" -> listOf(ConnectivityManager.TYPE_WIFI)
        "mobile" -> listOf(ConnectivityManager.TYPE_MOBILE)
        else -> listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)
    }
    types.forEach { type ->
        runCatching {
            stats.queryDetailsForUid(type, null, start, end, uid).use { networkStats ->
                val bucket = NetworkStats.Bucket()
                while (networkStats.hasNextBucket()) {
                    networkStats.getNextBucket(bucket)
                    rx += bucket.rxBytes
                    tx += bucket.txBytes
                }
            }
        }
    }
    return Usage(rx, tx)
}
