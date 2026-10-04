package com.yagay.yauto.platform.android

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidShortXParityFeaturePack(context: Context) : FeaturePack {
    override val id = "android.shortx.parity"
    private val context = context.applicationContext
    private val usage = this.context.getSystemService(UsageStatsManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerQuickSettingsClick(registry)
        registerStopService(registry)
        registerLastUsedApp(registry)
        registerGlobalActions(registry)
        registerInsets(registry)
        registerPackageSets(registry)
        registerPinnedIntent(registry)
        registerActivityAndTaskControls(registry)
        registerWaitForIdle(registry)
    }

    private fun registerQuickSettingsClick(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.qs_tile.click"), FeatureKind.ACTION,
                "Click Quick Settings tile",
                "Invoke an Android Quick Settings tile component through the status-bar shell service",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Text("component", "Tile component package/class", true)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("quick settings", "tile", "click tile", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val component = feature.config.string("component").resolveVariables(ctx.variables).trim()
            val parsed = ComponentName.unflattenFromString(component)
                ?: return@registerAction ActionExecutionResult(false)
            shell(ctx, "cmd statusbar click-tile " + shellArg(parsed.flattenToString()))
        }
    }

    private fun registerStopService(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.service.stop"), FeatureKind.ACTION,
                "Stop Android service",
                "Stop an explicit Android Service component through ActivityManager",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("component", "Service component package/class", true),
                    FieldSchema.Number("userId", "Android user ID", min = 0.0),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("stop service", "android service", "shortx", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val component = feature.config.string("component").resolveVariables(ctx.variables).trim()
            val parsed = ComponentName.unflattenFromString(component)
                ?: return@registerAction ActionExecutionResult(false)
            val user = (feature.config["userId"].numberOrNull() ?: 0.0).toInt().coerceAtLeast(0)
            shell(ctx, "am stopservice --user " + user + " -n " + shellArg(parsed.flattenToString()))
        }
    }

    private fun registerLastUsedApp(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.last_used.launch"), FeatureKind.ACTION,
                "Launch last used app",
                "Launch the most recently used launchable app other than YAuto and the current foreground app",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Number("lookbackHours", "Look back hours", min = 1.0, max = 720.0)),
                accessRequirements = setOf(AccessRequirement.USAGE_STATS),
                keywords = setOf("last app", "previous app", "recent app", "shortx"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val now = System.currentTimeMillis()
            val lookback = ((feature.config["lookbackHours"].numberOrNull() ?: 24.0).toLong().coerceIn(1L, 720L)) * 3_600_000L
            val candidates = runCatching {
                usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - lookback, now)
                    .asSequence()
                    .filter { it.packageName != context.packageName && it.lastTimeUsed > 0L }
                    .groupBy { it.packageName }
                    .mapNotNull { (pkg, stats) ->
                        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                        Triple(pkg, stats.maxOf { it.lastTimeUsed }, intent)
                    }
                    .sortedByDescending { it.second }
                    .toList()
            }.getOrDefault(emptyList())
            val target = candidates.firstOrNull() ?: return@registerAction ActionExecutionResult(false)
            runCatching {
                target.third.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(target.third)
                ActionExecutionResult(true, ConfigValue.StringValue(target.first))
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerGlobalActions(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.global_actions.show"), FeatureKind.ACTION,
                "Show global actions menu",
                "Open Android's global power-actions menu using Accessibility",
                FeatureCategory.SYSTEM,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                accessRequirements = setOf(AccessRequirement.ACCESSIBILITY),
                keywords = setOf("global actions", "power menu", "shortx"),
                ownerPackId = id,
            )
        ) { _, ctx ->
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.ACCESSIBILITY,
                    operationId = "accessibility.global_action",
                    payload = mapOf("action" to ConfigValue.StringValue("power_dialog")),
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private fun registerInsets(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.insets.immersive.set"), FeatureKind.ACTION,
                "Set immersive system bars",
                "Show or hide Android status/navigation bars globally through the policy_control setting",
                FeatureCategory.DISPLAY,
                fields = listOf(
                    FieldSchema.Choice("mode", "Mode", true, listOf("show_all", "hide_all", "hide_status", "hide_navigation")),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("insets", "immersive", "status bar", "navigation bar", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val value = when (feature.config.string("mode", "show_all")) {
                "hide_all" -> "immersive.full=*"
                "hide_status" -> "immersive.status=*"
                "hide_navigation" -> "immersive.navigation=*"
                else -> "null"
            }
            shell(ctx, "settings put global policy_control " + shellArg(value))
        }
    }

    private fun registerPackageSets(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.package_set.create"), FeatureKind.ACTION,
                "Create package set",
                "Create or replace a package-set variable",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("name", "Set name", true),
                    FieldSchema.Text("packages", "Packages, one per line", multiline = true),
                ),
                keywords = setOf("package set", "app set", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name").trim()
            if (name.isBlank()) return@registerAction ActionExecutionResult(false)
            val values = feature.config.string("packages").resolveVariables(ctx.variables)
                .lineSequence().map(String::trim).filter { PACKAGE.matches(it) }.distinct()
                .map { ConfigValue.StringValue(it) }.toList()
            val output = ConfigValue.ListValue(values)
            ctx.variables.set(name, output)
            ActionExecutionResult(true, output)
        }
        packageSetMutator(registry, "android.package_set.add", "Add packages to set", true)
        packageSetMutator(registry, "android.package_set.remove", "Remove packages from set", false)
    }

    private fun packageSetMutator(registry: FeatureRegistry, featureId: String, title: String, add: Boolean) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(featureId), FeatureKind.ACTION, title,
                if (add) "Add package names to a package-set variable" else "Remove package names from a package-set variable",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Variable("name", "Package-set variable", true),
                    FieldSchema.Text("packages", "Packages, one per line", true, multiline = true),
                ),
                keywords = setOf("package set", "app set", "shortx"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name").trim()
            if (name.isBlank()) return@registerAction ActionExecutionResult(false)
            val current = (ctx.variables.get(name) as? ConfigValue.ListValue)?.value.orEmpty()
                .mapNotNull { (it as? ConfigValue.StringValue)?.value }.toMutableSet()
            val values = feature.config.string("packages").resolveVariables(ctx.variables)
                .lineSequence().map(String::trim).filter { PACKAGE.matches(it) }.toSet()
            if (add) current.addAll(values) else current.removeAll(values)
            val output = ConfigValue.ListValue(current.sorted().map { ConfigValue.StringValue(it) })
            ctx.variables.set(name, output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerPinnedIntent(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.intent_uri.launch"), FeatureKind.ACTION,
                "Launch serialized Intent URI",
                "Parse and launch an Android Intent URI",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("intentUri", "Intent URI", true, multiline = true)),
                keywords = setOf("intent uri", "pinned item", "app shortcut", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val raw = feature.config.string("intentUri").resolveVariables(ctx.variables).trim()
            if (raw.isBlank() || raw.length > 100_000) return@registerAction ActionExecutionResult(false)
            runCatching {
                val intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }


    private fun registerActivityAndTaskControls(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.activity.close"), FeatureKind.ACTION,
                "Close Activity",
                "Close the current Activity with Back, or force-stop the package that owns an explicit Activity component",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Choice("mode", "Mode", true, listOf("focused", "component")),
                    FieldSchema.Text("component", "Activity component package/class"),
                ),
                capabilities = setOf(CapabilityIds.ACCESSIBILITY, CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("close activity", "finish activity", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            when (feature.config.string("mode", "focused")) {
                "component" -> {
                    val parsed = ComponentName.unflattenFromString(
                        feature.config.string("component").resolveVariables(ctx.variables).trim()
                    ) ?: return@registerAction ActionExecutionResult(false)
                    shell(ctx, "am force-stop " + shellArg(parsed.packageName))
                }
                else -> {
                    val result = ctx.capabilities.execute(
                        CapabilityRequest(
                            capability = CapabilityIds.ACCESSIBILITY,
                            operationId = "accessibility.global_action",
                            payload = mapOf("action" to ConfigValue.StringValue("back")),
                        )
                    )
                    ActionExecutionResult(result.success, result.value, result.message)
                }
            }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.tasks.remove"), FeatureKind.ACTION,
                "Remove recent tasks",
                "Remove recent ActivityManager tasks matching selected packages using Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("packages", "Packages, one per line", true, multiline = true),
                    FieldSchema.Toggle("allMatching", "Remove every matching task"),
                    FieldSchema.Variable("resultVariable", "Store removed task count"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("remove tasks", "recents", "task", "shortx", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val packages = feature.config.string("packages").resolveVariables(ctx.variables)
                .lineSequence().map(String::trim).filter { PACKAGE.matches(it) }.toSet()
            if (packages.isEmpty()) return@registerAction ActionExecutionResult(false)
            val dump = shellResult(ctx, "dumpsys activity recents")
            if (!dump.success) return@registerAction ActionExecutionResult(false, dump.value, dump.message)
            val text = stdout(dump)
            val taskRegex = Regex("""(?s)Task\{[^#]*#(\d+)[^}]*?([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/""")
            val matches = taskRegex.findAll(text)
                .mapNotNull { match ->
                    val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val pkg = match.groupValues[2]
                    if (pkg in packages) id to pkg else null
                }
                .distinctBy { it.first }
                .toList()
            if (matches.isEmpty()) {
                val output = ConfigValue.NumberValue(0.0)
                feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let { ctx.variables.set(it, output) }
                return@registerAction ActionExecutionResult(true, output)
            }
            var removed = 0
            for ((taskId, _) in matches) {
                val result = shellResult(ctx, "am task remove " + taskId)
                if (result.success) removed++
                if (!feature.config.boolean("allMatching", true)) break
            }
            val output = ConfigValue.NumberValue(removed.toDouble())
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(removed > 0, output)
        }
    }

    private fun registerWaitForIdle(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.ui.wait_for_idle"), FeatureKind.ACTION,
                "Wait for main queue idle",
                "Continue when YAuto's Android main message queue reaches an idle point",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Duration("timeoutMs", "Maximum wait")),
                keywords = setOf("wait idle", "ui idle", "shortx"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val timeoutMs = ((feature.config["timeoutMs"] as? ConfigValue.NumberValue)?.value ?: 5_000.0)
                .toLong().coerceIn(1L, 60_000L)
            val ok = waitForMainQueueIdle(timeoutMs)
            ActionExecutionResult(ok, ConfigValue.BooleanValue(ok))
        }
    }

    private suspend fun waitForMainQueueIdle(timeoutMs: Long): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                Handler(Looper.getMainLooper()).post {
                    val queue = Looper.myQueue()
                    val handler = MessageQueue.IdleHandler {
                        if (continuation.isActive) continuation.resume(true)
                        false
                    }
                    queue.addIdleHandler(handler)
                    continuation.invokeOnCancellation { queue.removeIdleHandler(handler) }
                }
            }
        } ?: false

    private suspend fun shellResult(ctx: FeatureExecutionContext, command: String) =
        ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = "system.shell.execute",
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )

    private fun stdout(result: com.yagay.yauto.core.capability.CapabilityResult): String =
        ((result.value as? ConfigValue.ObjectValue)?.value?.get("stdout") as? ConfigValue.StringValue)?.value.orEmpty()

    private suspend fun shell(ctx: FeatureExecutionContext, command: String): ActionExecutionResult {
        val result = ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = "system.shell.execute",
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )
        return ActionExecutionResult(result.success, result.value, result.message)
    }

    private fun privilegedOptions() = listOf(
        FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
    )

    private fun shellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
