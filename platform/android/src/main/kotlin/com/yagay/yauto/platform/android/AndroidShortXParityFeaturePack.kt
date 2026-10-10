package com.yagay.yauto.platform.android

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

class AndroidShortXParityFeaturePack(context: Context) : FeaturePack {
    override val id = "android.shortx.parity"
    private val context = context.applicationContext
    private val usage = this.context.getSystemService(UsageStatsManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerQuickSettingsClick(registry)
        registerServiceControl(registry)
        registerLastUsedApp(registry)
        registerRecentAppNavigation(registry)
        registerGlobalActions(registry)
        registerInsets(registry)
        registerPackageSets(registry)
        registerPinnedIntent(registry)
        registerActivityAndTaskControls(registry)
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

    /**
     * One operation picker for Android services. The historical android.service.stop ID
     * remains a resolvable alias with an explicit 'stop' default.
     */
    private fun registerServiceControl(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.service.control"), FeatureKind.ACTION,
                "Control Android service",
                "Start, start as foreground service, or stop an explicit Android Service component through a privileged shell",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Choice("mode", "Operation", true, listOf("stop", "start", "start_foreground")),
                    FieldSchema.Text("component", "Service component package/class"),
                    FieldSchema.Text("components", "Additional services to stop, one per line", multiline = true),
                    FieldSchema.Number("userId", "Android user ID", min = 0.0, max = 999.0),
                    FieldSchema.Text("intentAction", "Start Intent action"),
                    FieldSchema.Text("dataUri", "Start Intent data URI"),
                    FieldSchema.Number("intentFlags", "Intent flags", min = 0.0, max = 4294967295.0),
                    FieldSchema.Text("intentExtrasJson", "Typed Intent extras JSON (array of key/type/value)", multiline = true),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("start service", "stop service", "foreground service", "shortx", "root", "shizuku"),
                ownerPackId = id,
                aliases = setOf("android.service.stop"),
                aliasConfigDefaults = mapOf(
                    "android.service.stop" to mapOf("mode" to ConfigValue.StringValue("stop")),
                ),
            )
        ) { feature, ctx ->
            val mode = feature.config.string("mode", "stop")
            val rawUserId = feature.config["userId"].numberOrNull() ?: 0.0
            if (!rawUserId.isFinite() || rawUserId % 1.0 != 0.0 || rawUserId !in 0.0..999.0) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.service_invalid_user"))
            }
            val user = rawUserId.toInt()
            val primary = feature.config.string("component").resolveVariables(ctx.variables).trim()
            val extra = feature.config.string("components").resolveVariables(ctx.variables).lineSequence()
                .map(String::trim).filter(String::isNotEmpty).toList()
            val names = (listOf(primary).filter(String::isNotEmpty) + extra).distinct()
            if (names.isEmpty() || names.size > 32 || (mode != "stop" && names.size != 1)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.service_invalid_component"))
            }
            val intentAction = feature.config.string("intentAction").resolveVariables(ctx.variables).trim()
            val dataUri = feature.config.string("dataUri").resolveVariables(ctx.variables).trim()
            val rawFlags = feature.config["intentFlags"].numberOrNull() ?: 0.0
            if (!rawFlags.isFinite() || rawFlags < 0.0 || rawFlags > 4294967295.0 || rawFlags % 1.0 != 0.0) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.service_invalid_command"))
            }
            val extrasJson = feature.config.string("intentExtrasJson").resolveVariables(ctx.variables)
            val commands = names.map { name ->
                serviceControlCommand(name, user, mode, intentAction, dataUri, rawFlags.toLong(), extrasJson)
                    ?: return@registerAction ActionExecutionResult(false, message = userText("feature.service_invalid_command"))
            }
            var completed = 0
            var lastValue: ConfigValue = ConfigValue.NullValue
            for (command in commands) {
                val result = shellResult(ctx, command)
                val stdoutText = stdout(result)
                val stderrText = ((result.value as? ConfigValue.ObjectValue)?.value?.get("stderr") as? ConfigValue.StringValue)?.value.orEmpty()
                val output = (stdoutText + "\n" + stderrText).trim()
                val ok = result.success && !serviceControlFailed(output)
                if (!ok) {
                    return@registerAction ActionExecutionResult(
                        false, result.value,
                        output.take(300).takeIf { it.isNotBlank() }?.let { userText("feature.operation_failed", it) }
                            ?: userText("feature.service_command_failed"),
                    )
                }
                completed++
                lastValue = result.value
            }
            ActionExecutionResult(true, if (names.size == 1) lastValue else ConfigValue.NumberValue(completed.toDouble()))
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

    private fun registerRecentAppNavigation(registry: FeatureRegistry) {
        registerRecentAppAction(
            registry = registry,
            featureId = "android.app.previous.launch",
            title = "Launch previous app",
            description = "Launch the most recently used launchable app other than YAuto",
            offset = 0,
            keywords = setOf("previous app", "last app", "recents", "shortx"),
        )
        registerRecentAppAction(
            registry = registry,
            featureId = "android.app.next.launch",
            title = "Launch next recent app",
            description = "Launch the next older launchable app from recent usage history",
            offset = 1,
            keywords = setOf("next app", "recents", "switch app", "shortx"),
        )
    }

    private fun registerRecentAppAction(
        registry: FeatureRegistry,
        featureId: String,
        title: String,
        description: String,
        offset: Int,
        keywords: Set<String>,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(featureId),
                FeatureKind.ACTION,
                title,
                description,
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Number("lookbackHours", "Look back hours", min = 1.0, max = 720.0)),
                accessRequirements = setOf(AccessRequirement.USAGE_STATS),
                keywords = keywords,
                ownerPackId = id,
            )
        ) { feature, _ ->
            val now = System.currentTimeMillis()
            val lookback = ((feature.config["lookbackHours"].numberOrNull() ?: 24.0).toLong().coerceIn(1L, 720L)) * 3_600_000L
            val candidates = recentLaunchableApps(now, lookback)
            val target = candidates.getOrNull(offset) ?: return@registerAction ActionExecutionResult(false)
            runCatching {
                target.third.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(target.third)
                ActionExecutionResult(true, ConfigValue.StringValue(target.first))
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun recentLaunchableApps(now: Long, lookback: Long): List<Triple<String, Long, Intent>> = runCatching {
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
                fields = listOf(
                    FieldSchema.Text("intentUri", "Intent URI", true, multiline = true),
                    FieldSchema.Toggle("urlSchemeMode", "Open URL scheme directly"),
                ),
                keywords = setOf("intent uri", "pinned item", "app shortcut", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val raw = feature.config.string("intentUri").resolveVariables(ctx.variables).trim()
            if (raw.isBlank() || raw.length > 100_000) return@registerAction ActionExecutionResult(false)
            runCatching {
                val intent = if (feature.config.boolean("urlSchemeMode")) {
                    val uri = Uri.parse(raw)
                    require(!uri.scheme.isNullOrBlank()) { "Missing URL scheme" }
                    Intent(Intent.ACTION_VIEW, uri)
                } else {
                    Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
                }
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

/** Restrict privileged commands to an explicit Android component and numeric user ID. */
internal fun serviceControlCommand(
    component: String, userId: Int, mode: String, action: String = "", dataUri: String = "",
    flags: Long = 0, extrasJson: String = "",
): String? {
    if (userId !in 0..999) return null
    if (!Regex("""[A-Za-z_][A-Za-z0-9_.]*\/[A-Za-z_.$][A-Za-z0-9_.$]*""").matches(component)) return null
    val verb = when (mode) {
        "stop" -> "stopservice"
        "start" -> "startservice"
        "start_foreground" -> "start-foreground-service"
        else -> return null
    }
    if (mode == "stop" && (action.isNotBlank() || dataUri.isNotBlank() || flags != 0L || extrasJson.isNotBlank())) return null
    if (flags !in 0L..4294967295L) return null
    val extras = serviceExtrasCommand(extrasJson) ?: return null
    if (action.isNotBlank() && !Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*").matches(action)) return null
    if (dataUri.isNotBlank() && (!Regex("[A-Za-z][A-Za-z0-9+.-]*:.*").matches(dataUri) || dataUri.length > 2_048)) return null
    val options = buildString {
        if (action.isNotBlank()) append(" -a " + shellServiceArg(action))
        if (dataUri.isNotBlank()) append(" -d " + shellServiceArg(dataUri))
        if (flags != 0L) append(" -f 0x" + flags.toString(16))
        append(extras)
    }
    return "am " + verb + " --user " + userId + " -n " + shellServiceArg(component) + options
}

/** 'am' can exit 0 yet report failure text. */
internal fun serviceControlFailed(output: String): Boolean {
    val normalized = output.lowercase()
    return listOf("error:", "securityexception", "permission denial", "not allowed",
        "not found", "does not exist", "unable to start", "exception occurred").any(normalized::contains)
}

private fun shellServiceArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** The Android am shell supports explicit typed Intent extras. Invalid values fail closed. */
internal fun serviceExtrasCommand(json: String): String? {
    if (json.isBlank()) return ""
    val data = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return null
    if (data.size > 24) return null
    val keys = mutableSetOf<String>()
    val args = ArrayList<String>(data.size)
    for (item in data) {
        val obj = item as? JsonObject ?: return null
        if (obj.keys != setOf("key", "type", "value")) return null
        val key = (obj["key"] as? JsonPrimitive)?.contentOrNull ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.intOrNull ?: return null
        val value = (obj["value"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (!Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}").matches(key) || !keys.add(key) ||
            value.length > 4096 || value.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
        val flag: String
        val normalized: String
        when (type) {
            0 -> { flag = "--ei"; normalized = value.toIntOrNull()?.toString() ?: return null }
            1 -> { flag = "--el"; normalized = value.toLongOrNull()?.toString() ?: return null }
            2 -> { flag = "--es"; normalized = value }
            3 -> { flag = "--ez"; normalized = value.takeIf { it == "true" || it == "false" } ?: return null }
            4 -> {
                flag = "--ef"
                val number = value.toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
                normalized = number.toString()
            }
            5 -> {
                flag = "--ed"
                val number = value.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
                normalized = number.toString()
            }
            else -> return null
        }
        args += " " + flag + " " + shellServiceArg(key) + " " + shellServiceArg(normalized)
    }
    return args.joinToString("")
}
