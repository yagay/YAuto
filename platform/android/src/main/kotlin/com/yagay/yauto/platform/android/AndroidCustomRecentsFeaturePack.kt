package com.yagay.yauto.platform.android

import android.app.usage.UsageStatsManager
import android.content.Context
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidCustomRecentsFeaturePack(
    context: Context,
    private val surfaces: OverlaySurfaceController,
) : FeaturePack {
    override val id: String = "android.custom_recents"
    private val context = context.applicationContext
    private val usage = this.context.getSystemService(UsageStatsManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("surface.custom_recents.show"), FeatureKind.ACTION,
                "Show custom recent apps",
                "Show a YAuto recent-apps panel built from Usage Stats and launch the selected application",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("surfaceId", "Surface ID", true),
                    FieldSchema.Text("title", "Title"),
                    FieldSchema.Number("maxApps", "Maximum apps", min = 1.0, max = 120.0),
                    FieldSchema.Number("lookbackHours", "Look back hours", min = 1.0, max = 720.0),
                    FieldSchema.Number("columns", "Grid columns", min = 1.0, max = 6.0),
                    FieldSchema.Choice("gravity", "Position", options = listOf("center", "top", "bottom")),
                    FieldSchema.Duration("autoHideMs", "Auto hide after"),
                ),
                accessRequirements = setOf(AccessRequirement.USAGE_STATS, AccessRequirement.OVERLAY),
                keywords = setOf("custom recents", "recent apps", "usage stats", "shortx", "launcher"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val now = System.currentTimeMillis()
            val lookback = ((feature.config["lookbackHours"].numberOrNull() ?: 24.0).toLong().coerceIn(1L, 720L)) * 3_600_000L
            val limit = (feature.config["maxApps"].numberOrNull() ?: 24.0).toInt().coerceIn(1, 120)
            val apps = runCatching {
                usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - lookback, now)
                    .asSequence()
                    .filter { it.lastTimeUsed > 0L && it.packageName != context.packageName }
                    .groupBy { it.packageName }
                    .mapNotNull { (pkg, stats) ->
                        val lastUsed = stats.maxOfOrNull { it.lastTimeUsed } ?: return@mapNotNull null
                        context.packageManager.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                        val label = runCatching {
                            val info = context.packageManager.getApplicationInfo(pkg, 0)
                            context.packageManager.getApplicationLabel(info).toString()
                        }.getOrDefault(pkg)
                        Triple(label, pkg, lastUsed)
                    }
                    .sortedByDescending { it.third }
                    .take(limit)
                    .map { it.first to it.second }
                    .toList()
            }.getOrDefault(emptyList())
            if (apps.isEmpty()) return@registerAction ActionExecutionResult(false)
            ActionExecutionResult(
                surfaces.showAppLauncherList(
                    id = feature.config.string("surfaceId").resolveVariables(ctx.variables).trim(),
                    title = feature.config.string("title").resolveVariables(ctx.variables).ifBlank { "Recent apps" },
                    apps = apps,
                    columns = (feature.config["columns"].numberOrNull() ?: 2.0).toInt(),
                    gravity = feature.config.string("gravity", "center"),
                    autoHideMs = feature.config.long("autoHideMs", 0L),
                )
            )
        }
    }
}
