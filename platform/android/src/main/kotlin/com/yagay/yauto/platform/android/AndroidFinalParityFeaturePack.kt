package com.yagay.yauto.platform.android

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AndroidFinalParityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.final_parity"
    private val context = context.applicationContext
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val roles = this.context.getSystemService(RoleManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerNotificationChannels(registry)
        registerRoleManagement(registry)
        registerTelephonyShell(registry)
        registerTaskerPluginBridge(registry)
        registerWidgetBridge(registry)
    }

    private fun registerNotificationChannels(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channels.query"), FeatureKind.ACTION,
                "Query notification channels",
                "Return notification channels owned by YAuto, including importance, sound and group",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store channel list", true)),
                keywords = setOf("notification channel", "importance", "sound", "group", "macrodroid", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = ConfigValue.ListValue(
                notifications.notificationChannels.map { channel ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "id" to ConfigValue.StringValue(channel.id),
                            "name" to ConfigValue.StringValue(channel.name?.toString().orEmpty()),
                            "description" to ConfigValue.StringValue(channel.description.orEmpty()),
                            "importance" to ConfigValue.NumberValue(channel.importance.toDouble()),
                            "group" to ConfigValue.StringValue(channel.group.orEmpty()),
                            "sound" to ConfigValue.StringValue(channel.sound?.toString().orEmpty()),
                            "vibration" to ConfigValue.BooleanValue(channel.shouldVibrate()),
                            "lights" to ConfigValue.BooleanValue(channel.shouldShowLights()),
                            "bypassDnd" to ConfigValue.BooleanValue(channel.canBypassDnd()),
                            "lockscreenVisibility" to ConfigValue.NumberValue(channel.lockscreenVisibility.toDouble()),
                        )
                    )
                }
            )
            store(feature, ctx, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channel.create"), FeatureKind.ACTION,
                "Create or update notification channel",
                "Create a YAuto notification channel or update fields Android still allows the app to change",
                FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.Text("channelId", "Channel ID", true),
                    FieldSchema.Text("name", "Name", true),
                    FieldSchema.Text("description", "Description"),
                    FieldSchema.Choice("importance", "Importance", true, listOf("none", "min", "low", "default", "high", "max")),
                    FieldSchema.Text("groupId", "Channel group ID"),
                    FieldSchema.Toggle("vibration", "Enable vibration"),
                    FieldSchema.Toggle("lights", "Enable lights"),
                ),
                keywords = setOf("notification channel", "create channel", "importance", "vibration", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val channelId = feature.config.string("channelId").resolveVariables(ctx.variables).trim()
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            if (!safeId(channelId) || name.isBlank()) return@registerAction ActionExecutionResult(false)
            val importance = when (feature.config.string("importance", "default")) {
                "none" -> NotificationManager.IMPORTANCE_NONE
                "min" -> NotificationManager.IMPORTANCE_MIN
                "low" -> NotificationManager.IMPORTANCE_LOW
                "high" -> NotificationManager.IMPORTANCE_HIGH
                "max" -> NotificationManager.IMPORTANCE_MAX
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = feature.config.string("description").resolveVariables(ctx.variables).take(1000)
                group = feature.config.string("groupId").resolveVariables(ctx.variables).trim().takeIf(::safeId)
                enableVibration(feature.config.boolean("vibration", false))
                enableLights(feature.config.boolean("lights", false))
            }
            runCatching {
                notifications.createNotificationChannel(channel)
                ActionExecutionResult(true, ConfigValue.StringValue(channelId))
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channel.delete"), FeatureKind.ACTION,
                "Delete notification channel",
                "Delete a notification channel owned by YAuto",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Text("channelId", "Channel ID", true)),
                keywords = setOf("notification channel", "delete channel", "remove"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val channelId = feature.config.string("channelId").resolveVariables(ctx.variables).trim()
            if (!safeId(channelId)) return@registerAction ActionExecutionResult(false)
            runCatching { notifications.deleteNotificationChannel(channelId); ActionExecutionResult(true) }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channel_groups.query"), FeatureKind.ACTION,
                "Query notification channel groups",
                "Return notification channel groups owned by YAuto",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store group list", true)),
                keywords = setOf("notification channel group", "channel group"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = ConfigValue.ListValue(
                notifications.notificationChannelGroups.map { group ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "id" to ConfigValue.StringValue(group.id),
                            "name" to ConfigValue.StringValue(group.name?.toString().orEmpty()),
                            "description" to ConfigValue.StringValue(group.description.orEmpty()),
                            "blocked" to ConfigValue.BooleanValue(group.isBlocked),
                            "channels" to ConfigValue.ListValue(group.channels.map { ConfigValue.StringValue(it.id) }),
                        )
                    )
                }
            )
            store(feature, ctx, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channel_group.create"), FeatureKind.ACTION,
                "Create notification channel group",
                "Create or update a notification channel group owned by YAuto",
                FeatureCategory.NOTIFICATION,
                fields = listOf(
                    FieldSchema.Text("groupId", "Group ID", true),
                    FieldSchema.Text("name", "Name", true),
                    FieldSchema.Text("description", "Description"),
                ),
                keywords = setOf("notification group", "channel group", "create"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val groupId = feature.config.string("groupId").resolveVariables(ctx.variables).trim()
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            if (!safeId(groupId) || name.isBlank()) return@registerAction ActionExecutionResult(false)
            runCatching {
                notifications.createNotificationChannelGroup(
                    NotificationChannelGroup(groupId, name).apply {
                        description = feature.config.string("description").resolveVariables(ctx.variables).take(1000)
                    }
                )
                ActionExecutionResult(true, ConfigValue.StringValue(groupId))
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.notification.channel_group.delete"), FeatureKind.ACTION,
                "Delete notification channel group",
                "Delete a notification channel group owned by YAuto",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Text("groupId", "Group ID", true)),
                keywords = setOf("notification group", "channel group", "delete"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val groupId = feature.config.string("groupId").resolveVariables(ctx.variables).trim()
            if (!safeId(groupId)) return@registerAction ActionExecutionResult(false)
            runCatching { notifications.deleteNotificationChannelGroup(groupId); ActionExecutionResult(true) }.getOrElse { failure(it) }
        }
    }

    private fun registerRoleManagement(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.role.request"), FeatureKind.ACTION,
                "Request default-app role",
                "Open Android's role confirmation UI for SMS, dialer, browser, home, assistant or call screening",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Choice("role", "Role", true, ROLE_OPTIONS)),
                keywords = setOf("default app", "role", "sms", "dialer", "browser", "home", "assistant"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val role = roleName(feature.config.string("role")) ?: return@registerAction ActionExecutionResult(false)
            if (!roles.isRoleAvailable(role)) return@registerAction ActionExecutionResult(false)
            runCatching {
                context.startActivity(roles.createRequestRoleIntent(role).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionExecutionResult(true)
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.role.holder.manage"), FeatureKind.ACTION,
                "Manage role holder",
                "Add, remove or clear an Android role holder through Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Choice("operation", "Operation", true, listOf("add", "remove", "clear")),
                    FieldSchema.Choice("role", "Role", true, ROLE_OPTIONS),
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Number("userId", "Android user ID", min = 0.0),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("role", "default app", "root", "shizuku", "sms", "dialer", "browser"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val role = roleName(feature.config.string("role")) ?: return@registerAction ActionExecutionResult(false)
            val op = feature.config.string("operation", "add")
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val user = (feature.config["userId"].numberOrNull() ?: 0.0).toInt().coerceAtLeast(0)
            val command = when (op) {
                "clear" -> "cmd role clear-role-holders --user " + user + " " + shellArg(role)
                "remove" -> {
                    if (!PACKAGE.matches(pkg)) return@registerAction ActionExecutionResult(false)
                    "cmd role remove-role-holder --user " + user + " " + shellArg(role) + " " + shellArg(pkg)
                }
                else -> {
                    if (!PACKAGE.matches(pkg)) return@registerAction ActionExecutionResult(false)
                    "cmd role add-role-holder --user " + user + " " + shellArg(role) + " " + shellArg(pkg)
                }
            }
            shell(ctx, command)
        }
    }

    private fun registerTelephonyShell(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.allowed_network_types.query"), FeatureKind.ACTION,
                "Query allowed mobile network types",
                "Query Android's user-selected mobile network-type bitmask for a SIM slot",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("slotId", "SIM slot ID", min = 0.0, max = 7.0),
                    FieldSchema.Variable("resultVariable", "Store shell result"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("network type", "5g", "lte", "nr", "sim", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = (feature.config["slotId"].numberOrNull() ?: 0.0).toInt().coerceIn(0, 7)
            val result = ctx.capabilities.execute(shellRequest("cmd phone get-allowed-network-types-for-users -s " + slot))
            val resultName = feature.config.string("resultVariable").trim()
            if (resultName.isNotBlank() && result.value != null) ctx.variables.set(resultName, result.value!!)
            ActionExecutionResult(result.success, result.value, result.message)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.allowed_network_types.set"), FeatureKind.ACTION,
                "Set allowed mobile network types",
                "Set Android's user-selected network-type bitmask for a SIM slot using the phone shell service",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("slotId", "SIM slot ID", min = 0.0, max = 7.0),
                    FieldSchema.Text("bitmask", "Binary network-type bitmask", true),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("network type", "5g", "lte", "nr", "preferred network", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = (feature.config["slotId"].numberOrNull() ?: 0.0).toInt().coerceIn(0, 7)
            val bitmask = feature.config.string("bitmask").trim()
            if (!bitmask.matches(Regex("[01]{1,64}"))) return@registerAction ActionExecutionResult(false)
            shell(ctx, "cmd phone set-allowed-network-types-for-users -s " + slot + " " + bitmask)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.ims.set"), FeatureKind.ACTION,
                "Set IMS enabled",
                "Enable or disable IMS for a SIM slot through Android's phone shell service",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("slotId", "SIM slot ID", min = 0.0, max = 7.0),
                    FieldSchema.Toggle("enabled", "IMS enabled"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("ims", "volte", "vowifi", "sim", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = (feature.config["slotId"].numberOrNull() ?: 0.0).toInt().coerceIn(0, 7)
            val command = "cmd phone ims " + (if (feature.config.boolean("enabled", true)) "enable" else "disable") + " -s " + slot
            shell(ctx, command)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sms.default_app.query"), FeatureKind.ACTION,
                "Query default SMS app",
                "Return Android's current default SMS application through the phone shell service",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("userId", "Android user ID", min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store shell result"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("sms", "default app", "messaging", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val user = (feature.config["userId"].numberOrNull() ?: 0.0).toInt().coerceAtLeast(0)
            val result = ctx.capabilities.execute(shellRequest("cmd phone sms get-default-app --user " + user))
            val resultName = feature.config.string("resultVariable").trim()
            if (resultName.isNotBlank() && result.value != null) ctx.variables.set(resultName, result.value!!)
            ActionExecutionResult(result.success, result.value, result.message)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sms.default_app.set"), FeatureKind.ACTION,
                "Set default SMS app",
                "Set Android's default SMS application through Root or Shizuku",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Number("userId", "Android user ID", min = 0.0),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("sms", "default app", "messaging", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!PACKAGE.matches(pkg)) return@registerAction ActionExecutionResult(false)
            val user = (feature.config["userId"].numberOrNull() ?: 0.0).toInt().coerceAtLeast(0)
            shell(ctx, "cmd phone sms set-default-app --user " + user + " " + shellArg(pkg))
        }
    }


        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.network_mode.set"), FeatureKind.ACTION,
                "Set mobile network mode",
                "Set a common Android allowed-network-types preset for a SIM slot",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("slotId", "SIM slot ID", min = 0.0, max = 7.0),
                    FieldSchema.Choice("mode", "Network mode", true, listOf("nr_only", "nr_lte", "all", "lte_legacy", "lte_only", "custom")),
                    FieldSchema.Text("customBitmask", "Custom binary bitmask"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("5g", "4g", "lte", "nr", "preferred network", "network mode", "sim"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = (feature.config["slotId"].numberOrNull() ?: 0.0).toInt().coerceIn(0, 7)
            val bitmask = when (feature.config.string("mode", "nr_lte")) {
                "nr_only" -> "10000000000000000000"
                "nr_lte" -> "11000001000000000000"
                "all" -> "11001111101111111111"
                "lte_legacy" -> "01001111101111111111"
                "lte_only" -> "01000001000000000000"
                "custom" -> feature.config.string("customBitmask").trim()
                else -> return@registerAction ActionExecutionResult(false)
            }
            if (!bitmask.matches(Regex("[01]{1,64}"))) return@registerAction ActionExecutionResult(false)
            shell(ctx, "cmd phone set-allowed-network-types-for-users -s " + slot + " " + bitmask)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.telephony.physical_subscription.set"), FeatureKind.ACTION,
                "Enable or disable physical SIM",
                "Enable or disable a physical subscription by subscription ID. Android restricts this shell command to Root.",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("subscriptionId", "Subscription ID", true, min = 0.0),
                    FieldSchema.Toggle("enabled", "SIM enabled"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = listOf(FeatureImplementationOption("root", setOf(AccessRequirement.ROOT))),
                keywords = setOf("sim", "physical sim", "subscription", "enable sim", "root"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val subId = feature.config["subscriptionId"].numberOrNull()?.toInt()
                ?: return@registerAction ActionExecutionResult(false)
            val verb = if (feature.config.boolean("enabled", true)) "enable-physical-subscription" else "disable-physical-subscription"
            shell(ctx, "cmd phone " + verb + " " + subId)
        }

    private fun registerTaskerPluginBridge(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.plugin.locale.fire"), FeatureKind.ACTION,
                "Fire Locale/Tasker plug-in setting",
                "Send the standard Locale/Tasker FIRE_SETTING broadcast to a plug-in component with string bundle fields",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("component", "Receiver component package/class", true),
                    FieldSchema.Text("extras", "Bundle fields, key=value per line", multiline = true),
                    FieldSchema.Text("blurb", "Blurb / display text"),
                ),
                keywords = setOf("tasker plugin", "locale plugin", "fire setting", "plugin action", "external provider"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val component = android.content.ComponentName.unflattenFromString(
                feature.config.string("component").resolveVariables(ctx.variables).trim()
            ) ?: return@registerAction ActionExecutionResult(false)
            val bundle = android.os.Bundle()
            parseExtras(feature.config.string("extras").resolveVariables(ctx.variables)).forEach { (key, value) ->
                bundle.putString(key, value)
            }
            val intent = android.content.Intent("com.twofortyfouram.locale.intent.action.FIRE_SETTING")
                .setComponent(component)
                .putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", bundle)
            runCatching {
                context.sendBroadcast(intent)
                ActionExecutionResult(true)
            }.getOrElse { failure(it) }
        }
    }


    private fun registerWidgetBridge(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.widget.configure"), FeatureKind.ACTION,
                "Configure YAuto widget",
                "Update the label and command used by all YAuto home-screen widgets",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("label", "Widget label", true),
                    FieldSchema.Text("command", "Command value"),
                ),
                fieldBehaviors = mapOf(
                    "label" to FieldBehavior(supportsVariables = true),
                    "command" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("widget", "home screen", "button", "tasker", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val label = feature.config.string("label").resolveVariables(ctx.variables).trim()
            if (label.isBlank()) return@registerAction ActionExecutionResult(false)
            val command = feature.config.string("command").resolveVariables(ctx.variables)
            val intent = Intent("com.yagay.yauto.WIDGET_CONFIGURE")
                .setPackage(context.packageName)
                .putExtra("label", label)
                .putExtra("command", command)
            runCatching {
                context.sendBroadcast(intent)
                ActionExecutionResult(true)
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.widget.refresh"), FeatureKind.ACTION,
                "Refresh YAuto widgets",
                "Force all YAuto home-screen widgets to refresh their current configuration",
                FeatureCategory.UI_AUTOMATION,
                keywords = setOf("widget", "refresh", "update"), ownerPackId = id,
            )
        ) { _, _ ->
            runCatching {
                context.sendBroadcast(Intent("com.yagay.yauto.WIDGET_REFRESH").setPackage(context.packageName))
                ActionExecutionResult(true)
            }.getOrElse { failure(it) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.widget.pin"), FeatureKind.ACTION,
                "Pin YAuto widget",
                "Ask the current launcher to pin the YAuto widget to the home screen",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(
                    FieldSchema.Text("label", "Widget label"),
                    FieldSchema.Text("command", "Command value"),
                ),
                keywords = setOf("widget", "pin", "home screen", "launcher"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val manager = context.getSystemService(AppWidgetManager::class.java)
            if (!manager.isRequestPinAppWidgetSupported) return@registerAction ActionExecutionResult(false)
            val label = feature.config.string("label").resolveVariables(ctx.variables).trim()
            val command = feature.config.string("command").resolveVariables(ctx.variables)
            if (label.isNotBlank()) {
                context.sendBroadcast(
                    Intent("com.yagay.yauto.WIDGET_CONFIGURE")
                        .setPackage(context.packageName)
                        .putExtra("label", label)
                        .putExtra("command", command)
                )
            }
            val provider = ComponentName(context.packageName, "com.yagay.yauto.YAutoWidgetProvider")
            val accepted = manager.requestPinAppWidget(provider, null, null)
            ActionExecutionResult(accepted, ConfigValue.BooleanValue(accepted))
        }
    }

    private fun roleName(key: String): String? = when (key) {
        "sms" -> RoleManager.ROLE_SMS
        "dialer" -> RoleManager.ROLE_DIALER
        "browser" -> RoleManager.ROLE_BROWSER
        "home" -> RoleManager.ROLE_HOME
        "assistant" -> RoleManager.ROLE_ASSISTANT
        "call_screening" -> RoleManager.ROLE_CALL_SCREENING
        else -> null
    }

    private suspend fun shell(ctx: FeatureExecutionContext, command: String): ActionExecutionResult {
        val result = ctx.capabilities.execute(shellRequest(command))
        return ActionExecutionResult(result.success, result.value, result.message)
    }

    private fun shellRequest(command: String) = CapabilityRequest(
        capability = CapabilityIds.PRIVILEGED_SHELL,
        operationId = "system.shell.execute",
        payload = mapOf("command" to ConfigValue.StringValue(command)),
    )

    private fun privilegedOptions() = listOf(
        FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
    )

    private fun store(feature: com.yagay.yauto.core.model.FeatureRef, ctx: FeatureExecutionContext, value: ConfigValue): ActionExecutionResult {
        val name = feature.config.string("resultVariable").trim()
        if (name.isBlank()) return ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
        ctx.variables.set(name, value)
        return ActionExecutionResult(true, value)
    }

    private fun failure(error: Throwable) =
        ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))

    private fun safeId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._-]{1,100}"))

    private fun shellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val ROLE_OPTIONS = listOf("sms", "dialer", "browser", "home", "assistant", "call_screening")
    }
}
