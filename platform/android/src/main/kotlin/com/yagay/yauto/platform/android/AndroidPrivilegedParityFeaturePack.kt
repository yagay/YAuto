package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AndroidPrivilegedParityFeaturePack : FeaturePack {
    override val id: String = "android.privileged_parity"

    override fun install(registry: FeatureRegistry) {
        registerTether(registry)
        registerWirelessAdb(registry)
        registerApkInstall(registry)
        registerRefreshRate(registry)
        registerAppLocale(registry)
        registerIdleWhitelist(registry)
        registerBackgroundRestriction(registry)
        registerUserList(registry)
        registerUserSwitch(registry)
        registerUserCreate(registry)
        registerManagedProfileCreate(registry)
        registerUserRemove(registry)
        registerProfileRunning(registry)
        registerOverlay(registry)
        registerNotificationListenerAccess(registry)
        registerDndAccess(registry)
        registerProcessList(registry)
        registerProcessKill(registry)
        registerNetworkAccess(registry)
        registerMockLocationApp(registry)
        registerPowerMenu(registry)
    }

    private fun registerTether(registry: FeatureRegistry) {
        action(
            registry, "android.network.tether.set", "Set network tethering",
            "Start or stop Wi-Fi, USB or Bluetooth tethering through the Android connectivity shell service",
            FeatureCategory.NETWORK,
            listOf(
                FieldSchema.Choice("type", "Tethering type", true, listOf("wifi", "usb", "bluetooth")),
                FieldSchema.Toggle("enabled", "Enabled"),
            ),
            setOf("tethering", "hotspot", "wifi hotspot", "usb tether", "bluetooth tether", "root"),
        ) { feature, ctx ->
            val type = feature.config.string("type")
            if (type !in setOf("wifi", "usb", "bluetooth")) return@action invalid()
            val enabled = feature.config.boolean("enabled", true)
            val verb = if (enabled) "start" else "stop"
            execute(
                "android.network.tether.set",
                "cmd connectivity tether $verb $type || cmd connectivity tether $verb ${type.uppercase()}",
                ctx,
            )
        }
    }

    private fun registerWirelessAdb(registry: FeatureRegistry) {
        action(
            registry, "android.adb.wireless.set", "Set wireless ADB",
            "Enable or disable Android wireless debugging, with optional classic TCP/IP adbd port control",
            FeatureCategory.SYSTEM,
            listOf(
                FieldSchema.Toggle("enabled", "Enabled"),
                FieldSchema.Number("tcpPort", "Classic ADB TCP port (0 = only Wireless debugging)", min = 0.0, max = 65535.0),
            ),
            setOf("adb wifi", "wireless debugging", "adbd", "tcpip", "root"),
        ) { feature, ctx ->
            val enabled = feature.config.boolean("enabled", true)
            val port = (feature.config["tcpPort"].numberOrNull() ?: 0.0).toInt()
            if (port !in 0..65535) return@action invalid()
            val command = if (enabled) {
                if (port > 0) "settings put global adb_wifi_enabled 1; setprop service.adb.tcp.port $port; stop adbd; start adbd"
                else "settings put global adb_wifi_enabled 1"
            } else {
                "settings put global adb_wifi_enabled 0; setprop service.adb.tcp.port -1; stop adbd; start adbd"
            }
            execute("android.adb.wireless.set", command, ctx)
        }
    }

    private fun registerApkInstall(registry: FeatureRegistry) {
        action(
            registry, "android.app.apk.install", "Install APK",
            "Silently install an APK path through package manager using Root or Shizuku",
            FeatureCategory.APP,
            listOf(
                FieldSchema.Text("path", "APK path", true),
                FieldSchema.Toggle("replace", "Replace existing app"),
                FieldSchema.Toggle("grantRuntimePermissions", "Grant requested runtime permissions"),
                FieldSchema.Toggle("allowDowngrade", "Allow version downgrade"),
            ),
            setOf("apk", "install", "package manager", "silent install", "root", "shizuku"),
        ) { feature, ctx ->
            val path = feature.config.string("path").resolveVariables(ctx.variables).trim()
            if (!safeFilePath(path) || !path.endsWith(".apk", ignoreCase = true)) return@action invalid()
            val flags = buildString {
                if (feature.config.boolean("replace", true)) append(" -r")
                if (feature.config.boolean("grantRuntimePermissions")) append(" -g")
                if (feature.config.boolean("allowDowngrade")) append(" -d")
            }
            execute("android.app.apk.install", "pm install --user current$flags ${shellQuote(path)}", ctx, 180_000L)
        }
    }

    private fun registerRefreshRate(registry: FeatureRegistry) {
        action(
            registry, "android.display.refresh_rate.set", "Set display refresh rate",
            "Set or reset Android minimum and peak refresh-rate settings",
            FeatureCategory.DISPLAY,
            listOf(
                FieldSchema.Toggle("reset", "Reset refresh-rate overrides"),
                FieldSchema.Number("minHz", "Minimum refresh rate", min = 1.0, max = 1000.0),
                FieldSchema.Number("maxHz", "Peak refresh rate", min = 1.0, max = 1000.0),
            ),
            setOf("refresh rate", "hz", "peak refresh", "minimum refresh", "display"),
        ) { feature, ctx ->
            if (feature.config.boolean("reset")) {
                execute(
                    "android.display.refresh_rate.set",
                    "settings delete system min_refresh_rate; settings delete system peak_refresh_rate",
                    ctx,
                )
            } else {
                val min = feature.config["minHz"].numberOrNull()
                val max = feature.config["maxHz"].numberOrNull()
                if (min == null || max == null || !min.isFinite() || !max.isFinite() || min <= 0 || max < min) return@action invalid()
                execute(
                    "android.display.refresh_rate.set",
                    "settings put system min_refresh_rate $min; settings put system peak_refresh_rate $max",
                    ctx,
                )
            }
        }
    }

    private fun registerAppLocale(registry: FeatureRegistry) {
        action(
            registry, "android.locale.app.set", "Set per-app language",
            "Set Android application locales using BCP-47 language tags, or clear them to follow the system",
            FeatureCategory.APP,
            listOf(
                FieldSchema.AppPicker("package", "App / package", true),
                FieldSchema.Text("languageTags", "Language tags (for example en-GB, zh-CN)"),
                FieldSchema.Toggle("restartApp", "Force-stop app after change"),
            ),
            setOf("locale", "language", "per app language", "BCP47", "root", "shizuku"),
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val tags = feature.config.string("languageTags").resolveVariables(ctx.variables).trim()
            if (!PACKAGE_NAME.matches(pkg) || (tags.isNotBlank() && !LOCALE_TAGS.matches(tags))) return@action invalid()
            val restart = if (feature.config.boolean("restartApp", true)) "; am force-stop ${shellQuote(pkg)}" else ""
            execute(
                "android.locale.app.set",
                "cmd locale set-app-locales ${shellQuote(pkg)} --user current --locales ${shellQuote(tags)}$restart",
                ctx,
            )
        }
    }

    private fun registerIdleWhitelist(registry: FeatureRegistry) {
        action(
            registry, "android.power.idle_whitelist.set", "Set Doze allowlist",
            "Add or remove an app from Android DeviceIdle power whitelist",
            FeatureCategory.APP,
            listOf(
                FieldSchema.AppPicker("package", "App / package", true),
                FieldSchema.Toggle("allowed", "Allowed during Doze"),
            ),
            setOf("doze", "whitelist", "battery optimization", "deviceidle", "background"),
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!PACKAGE_NAME.matches(pkg)) return@action invalid()
            val prefix = if (feature.config.boolean("allowed", true)) "+" else "-"
            execute("android.power.idle_whitelist.set", "dumpsys deviceidle whitelist $prefix${shellQuote(pkg)}", ctx)
        }
    }

    private fun registerBackgroundRestriction(registry: FeatureRegistry) {
        action(
            registry, "android.app.background_restriction.set", "Set app background restriction",
            "Allow or restrict an app's background execution AppOps",
            FeatureCategory.APP,
            listOf(
                FieldSchema.AppPicker("package", "App / package", true),
                FieldSchema.Toggle("restricted", "Restricted"),
            ),
            setOf("background restriction", "appops", "run in background", "battery", "app"),
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!PACKAGE_NAME.matches(pkg)) return@action invalid()
            val mode = if (feature.config.boolean("restricted", true)) "ignore" else "allow"
            execute(
                "android.app.background_restriction.set",
                "cmd appops set --user current ${shellQuote(pkg)} RUN_IN_BACKGROUND $mode; " +
                    "cmd appops set --user current ${shellQuote(pkg)} RUN_ANY_IN_BACKGROUND $mode",
                ctx,
            )
        }
    }

    private fun registerUserList(registry: FeatureRegistry) {
        registry.registerAction(
            descriptor(
                "android.user.list", "List Android users",
                "List Android users and profiles through package manager",
                FeatureCategory.SYSTEM,
                listOf(FieldSchema.Variable("resultVariable", "Store raw user list", true)),
                setOf("users", "profiles", "work profile", "multi user"),
            )
        ) { feature, ctx ->
            val result = shell("android.user.list", "pm list users", ctx)
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val output = ConfigValue.StringValue(shellStdout(result.value).trim())
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerUserSwitch(registry: FeatureRegistry) {
        action(
            registry, "android.user.switch", "Switch Android user",
            "Switch the foreground Android user",
            FeatureCategory.SYSTEM,
            listOf(FieldSchema.Number("userId", "User ID", true, min = 0.0, max = 9999.0)),
            setOf("user", "profile", "switch user", "multi user"),
        ) { feature, ctx ->
            val userId = feature.config["userId"].numberOrNull()?.toInt() ?: return@action invalid()
            execute("android.user.switch", "am switch-user $userId", ctx)
        }
    }

    private fun registerUserCreate(registry: FeatureRegistry) {
        action(
            registry, "android.user.create", "Create Android user",
            "Create a secondary Android user",
            FeatureCategory.SYSTEM,
            listOf(FieldSchema.Text("name", "User name", true)),
            setOf("user", "create user", "multi user"),
        ) { feature, ctx ->
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            if (!safeLabel(name)) return@action invalid()
            execute("android.user.create", "pm create-user ${shellQuote(name)}", ctx)
        }
    }

    private fun registerManagedProfileCreate(registry: FeatureRegistry) {
        action(
            registry, "android.user.managed_profile.create", "Create managed profile",
            "Create a managed/work profile under an Android parent user",
            FeatureCategory.SYSTEM,
            listOf(
                FieldSchema.Text("name", "Profile name", true),
                FieldSchema.Number("parentUserId", "Parent user ID", min = 0.0, max = 9999.0),
            ),
            setOf("work profile", "managed profile", "create profile", "user"),
        ) { feature, ctx ->
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            val parent = (feature.config["parentUserId"].numberOrNull() ?: 0.0).toInt()
            if (!safeLabel(name) || parent !in 0..9999) return@action invalid()
            execute("android.user.managed_profile.create", "pm create-user --profileOf $parent --managed ${shellQuote(name)}", ctx)
        }
    }

    private fun registerUserRemove(registry: FeatureRegistry) {
        action(
            registry, "android.user.remove", "Remove Android user or profile",
            "Remove a non-primary Android user or managed profile by ID",
            FeatureCategory.SYSTEM,
            listOf(FieldSchema.Number("userId", "User ID", true, min = 1.0, max = 9999.0)),
            setOf("user", "remove user", "work profile", "managed profile"),
        ) { feature, ctx ->
            val userId = feature.config["userId"].numberOrNull()?.toInt() ?: return@action invalid()
            if (userId <= 0) return@action invalid()
            execute("android.user.remove", "pm remove-user $userId", ctx)
        }
    }

    private fun registerProfileRunning(registry: FeatureRegistry) {
        action(
            registry, "android.user.profile_running.set", "Start or stop user/profile",
            "Start a stopped Android user or work profile, or stop it in the background",
            FeatureCategory.SYSTEM,
            listOf(
                FieldSchema.Number("userId", "User/profile ID", true, min = 1.0, max = 9999.0),
                FieldSchema.Toggle("running", "Running"),
            ),
            setOf("work profile", "managed profile", "start user", "stop user", "profile"),
        ) { feature, ctx ->
            val userId = feature.config["userId"].numberOrNull()?.toInt() ?: return@action invalid()
            if (userId <= 0) return@action invalid()
            val command = if (feature.config.boolean("running", true)) {
                "am start-user -w $userId"
            } else {
                "am stop-user -w -f $userId"
            }
            execute("android.user.profile_running.set", command, ctx, 60_000L)
        }
    }

    private fun registerOverlay(registry: FeatureRegistry) {
        action(
            registry, "android.overlay.set", "Enable or disable runtime overlay",
            "Enable or disable an installed Runtime Resource Overlay for the current user",
            FeatureCategory.SYSTEM,
            listOf(
                FieldSchema.Text("package", "Overlay package", true),
                FieldSchema.Toggle("enabled", "Enabled"),
            ),
            setOf("overlay", "RRO", "theme", "cmd overlay"),
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!PACKAGE_NAME.matches(pkg)) return@action invalid()
            val verb = if (feature.config.boolean("enabled", true)) "enable" else "disable"
            execute("android.overlay.set", "cmd overlay $verb --user current ${shellQuote(pkg)}", ctx)
        }
    }

    private fun registerNotificationListenerAccess(registry: FeatureRegistry) {
        action(
            registry, "android.notification.listener_access.set", "Set notification listener access",
            "Grant or revoke notification-listener access for an Android component",
            FeatureCategory.SYSTEM,
            listOf(
                FieldSchema.Text("component", "Component package/class", true),
                FieldSchema.Toggle("allowed", "Allowed"),
            ),
            setOf("notification listener", "listener access", "grant", "revoke"),
        ) { feature, ctx ->
            val component = feature.config.string("component").resolveVariables(ctx.variables).trim()
            if (!COMPONENT.matches(component)) return@action invalid()
            val verb = if (feature.config.boolean("allowed", true)) "allow_listener" else "disallow_listener"
            execute("android.notification.listener_access.set", "cmd notification $verb ${shellQuote(component)}", ctx)
        }
    }

    private fun registerDndAccess(registry: FeatureRegistry) {
        action(
            registry, "android.notification.dnd_access.set", "Set Do Not Disturb access",
            "Grant or revoke notification-policy access for an app",
            FeatureCategory.SYSTEM,
            listOf(
                FieldSchema.AppPicker("package", "App / package", true),
                FieldSchema.Toggle("allowed", "Allowed"),
            ),
            setOf("dnd", "do not disturb access", "notification policy", "grant"),
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!PACKAGE_NAME.matches(pkg)) return@action invalid()
            val verb = if (feature.config.boolean("allowed", true)) "allow_dnd" else "disallow_dnd"
            execute("android.notification.dnd_access.set", "cmd notification $verb ${shellQuote(pkg)}", ctx)
        }
    }

    private fun registerProcessList(registry: FeatureRegistry) {
        registry.registerAction(
            descriptor(
                "android.process.list", "List processes",
                "Read the Android process table through a privileged shell",
                FeatureCategory.SYSTEM,
                listOf(FieldSchema.Variable("resultVariable", "Store process table", true)),
                setOf("process", "ps", "pid", "root", "diagnostics"),
            )
        ) { feature, ctx ->
            val result = shell("android.process.list", "ps -A -o USER,PID,PPID,NAME,ARGS 2>/dev/null || ps -A", ctx)
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val output = ConfigValue.StringValue(shellStdout(result.value).trim())
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerProcessKill(registry: FeatureRegistry) {
        action(
            registry, "android.process.kill_by_name", "Kill process by name",
            "Terminate processes whose exact Android process name matches the configured value",
            FeatureCategory.SYSTEM,
            listOf(
                FieldSchema.Text("processName", "Process name", true),
                FieldSchema.Choice("signal", "Signal", true, listOf("term", "kill")),
            ),
            setOf("process", "kill", "pidof", "ShortX", "root"),
        ) { feature, ctx ->
            val name = feature.config.string("processName").resolveVariables(ctx.variables).trim()
            if (!PROCESS_NAME.matches(name)) return@action invalid()
            val signal = if (feature.config.string("signal", "term") == "kill") "-9" else "-15"
            execute(
                "android.process.kill_by_name",
                "pids=$(pidof ${shellQuote(name)}); if [ -n \"\$pids\" ]; then kill $signal \$pids; fi",
                ctx,
            )
        }
    }

    private fun registerNetworkAccess(registry: FeatureRegistry) {
        action(
            registry, "android.network.access.set", "Set app network access",
            "Allow or block an app's Wi-Fi, mobile-data or all network output using UID-based firewall rules",
            FeatureCategory.NETWORK,
            listOf(
                FieldSchema.AppPicker("package", "App / package", true),
                FieldSchema.Choice("network", "Network", true, listOf("all", "wifi", "mobile")),
                FieldSchema.Toggle("allowed", "Allowed"),
            ),
            setOf("network access", "firewall", "block internet", "wifi", "mobile data", "Tasker", "root"),
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val network = feature.config.string("network", "all")
            if (!PACKAGE_NAME.matches(pkg) || network !in setOf("all", "wifi", "mobile")) return@action invalid()
            val allowed = feature.config.boolean("allowed", true)
            val iface = when (network) {
                "wifi" -> "wlan+"
                "mobile" -> "rmnet+"
                else -> ""
            }
            val comment = "YAuto-${pkg.takeLast(48)}-$network"
            val match = if (iface.isBlank()) "" else " -o $iface"
            val cleanup =
                "uid=$(cmd package list packages -U ${shellQuote(pkg)} | sed -n 's/.* uid://p' | head -n1); " +
                "[ -n \"\$uid\" ] || exit 2; " +
                "while iptables -C OUTPUT -m owner --uid-owner \$uid$match -m comment --comment ${shellQuote(comment)} -j REJECT 2>/dev/null; do " +
                "iptables -D OUTPUT -m owner --uid-owner \$uid$match -m comment --comment ${shellQuote(comment)} -j REJECT; done; " +
                "while ip6tables -C OUTPUT -m owner --uid-owner \$uid$match -m comment --comment ${shellQuote(comment)} -j REJECT 2>/dev/null; do " +
                "ip6tables -D OUTPUT -m owner --uid-owner \$uid$match -m comment --comment ${shellQuote(comment)} -j REJECT; done"
            val apply = if (allowed) "" else
                "; iptables -I OUTPUT 1 -m owner --uid-owner \$uid$match -m comment --comment ${shellQuote(comment)} -j REJECT" +
                "; ip6tables -I OUTPUT 1 -m owner --uid-owner \$uid$match -m comment --comment ${shellQuote(comment)} -j REJECT"
            execute("android.network.access.set", cleanup + apply, ctx)
        }
    }

    private fun registerMockLocationApp(registry: FeatureRegistry) {
        action(
            registry, "android.location.mock_app.set", "Set mock-location app access",
            "Grant or revoke Android mock-location AppOps access for an app",
            FeatureCategory.SYSTEM,
            listOf(
                FieldSchema.AppPicker("package", "App / package", true),
                FieldSchema.Toggle("allowed", "Allowed"),
            ),
            setOf("mock location", "fake gps", "appops", "location", "developer"),
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!PACKAGE_NAME.matches(pkg)) return@action invalid()
            val mode = if (feature.config.boolean("allowed", true)) "allow" else "deny"
            execute(
                "android.location.mock_app.set",
                "cmd appops set --user current ${shellQuote(pkg)} android:mock_location $mode || appops set ${shellQuote(pkg)} android:mock_location $mode",
                ctx,
            )
        }
    }

    private fun registerPowerMenu(registry: FeatureRegistry) {
        action(
            registry, "android.device.power_menu.show", "Show power menu",
            "Open the Android global power menu through privileged input injection",
            FeatureCategory.SYSTEM,
            emptyList(),
            setOf("power menu", "global actions", "shutdown dialog", "root", "Tasker"),
        ) { _, ctx ->
            execute("android.device.power_menu.show", "input keyevent --longpress KEYCODE_POWER", ctx)
        }
    }

    private fun action(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        keywords: Set<String>,
        block: suspend (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> ActionExecutionResult,
    ) = registry.registerAction(
        descriptor(typeId, title, description, category, fields, keywords),
        ActionExecutor(block),
    )

    private fun descriptor(
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        keywords: Set<String>,
    ) = FeatureDescriptor(
        FeatureId(typeId), FeatureKind.ACTION, title, description, category,
        fields = fields,
        capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
        keywords = keywords,
        ownerPackId = id,
    )

    private suspend fun execute(
        operationId: String,
        command: String,
        ctx: FeatureExecutionContext,
        timeoutMs: Long? = null,
    ): ActionExecutionResult {
        val result = shell(operationId, command, ctx, timeoutMs)
        return ActionExecutionResult(result.success, result.value, result.message)
    }

    private suspend fun shell(
        operationId: String,
        command: String,
        ctx: FeatureExecutionContext,
        timeoutMs: Long? = null,
    ) = ctx.capabilities.execute(
        CapabilityRequest(
            capability = CapabilityIds.PRIVILEGED_SHELL,
            operationId = operationId,
            payload = buildMap {
                put("command", ConfigValue.StringValue(command))
                timeoutMs?.let { put("timeoutMs", ConfigValue.NumberValue(it.toDouble())) }
            },
            allowFallback = true,
        )
    )

    private fun invalid() =
        ActionExecutionResult(false, message = userText("feature.privileged_input_invalid"))

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
        val LOCALE_TAGS = Regex("[A-Za-z0-9,-]{0,128}")
        val PROCESS_NAME = Regex("[A-Za-z0-9_.:-]{1,160}")
    }
}

private fun safeFilePath(path: String): Boolean =
    path.startsWith("/") && path.length <= 1024 && path.none { it == '\n' || it == '\r' || it == '\u0000' }

private fun safeLabel(value: String): Boolean =
    value.isNotBlank() && value.length <= 64 && value.none { it == '\n' || it == '\r' || it == '\u0000' }
