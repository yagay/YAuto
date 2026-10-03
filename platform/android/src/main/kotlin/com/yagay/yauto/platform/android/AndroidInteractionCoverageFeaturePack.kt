package com.yagay.yauto.platform.android

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.UserManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.yagay.yauto.core.capability.CapabilityId
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** Additional interaction and device-management primitives derived from gaps found in reference apps. */
class AndroidInteractionCoverageFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.interaction_coverage"
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    override fun install(registry: FeatureRegistry) {
        registerImePicker(registry)
        registerImeSet(registry)
        registerImeSettings(registry)
        registerWake(registry)
        registerReboot(registry)
        registerScreenshot(registry)
        registerKeyEvent(registry)

        textPair(
            registry, "default_ime", "Default input method", "Match the currently selected Android input method",
            FeatureCategory.SYSTEM, "idContains", "IME ID contains"
        ) { Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty() }
        textPair(
            registry, "enabled_ime", "Enabled input method", "Check the enabled Android input-method list",
            FeatureCategory.SYSTEM, "idContains", "IME ID contains"
        ) { Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty() }
        booleanPair(registry, "hardware_keyboard", "Hardware keyboard", "Check whether Android reports a hardware keyboard", FeatureCategory.DEVICE) {
            context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
        }
        booleanPair(registry, "user_unlocked", "User unlocked", "Check whether Android credential-encrypted user storage is unlocked", FeatureCategory.DEVICE) {
            context.getSystemService(UserManager::class.java).isUserUnlocked
        }
        booleanPair(registry, "developer_options", "Developer options", "Check whether Android developer options are enabled", FeatureCategory.SYSTEM) {
            Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        }
        booleanPair(registry, "adb_enabled", "ADB enabled", "Check whether Android ADB debugging is enabled", FeatureCategory.SYSTEM) {
            Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1
        }
        booleanPair(registry, "overlay_access", "Overlay access", "Check whether YAuto may draw over other apps", FeatureCategory.SYSTEM) {
            Settings.canDrawOverlays(context)
        }
        booleanPair(registry, "write_settings_access", "Modify system settings access", "Check whether YAuto may modify Android system settings", FeatureCategory.SYSTEM) {
            Settings.System.canWrite(context)
        }
        booleanPair(registry, "dnd_access", "Do Not Disturb access", "Check whether YAuto has notification-policy access", FeatureCategory.NOTIFICATION) {
            context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        }
        booleanPair(registry, "exact_alarm_access", "Exact alarm access", "Check whether YAuto may schedule exact alarms", FeatureCategory.SYSTEM) {
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        }
        defaultLauncherPair(registry)
        processRunningPair(registry)
    }

    private fun registerImePicker(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.ime.picker.show"), FeatureKind.ACTION,
                "Show input-method picker", "Open Android's input-method picker",
                FeatureCategory.SYSTEM,
                keywords = setOf("ime", "keyboard", "input method", "picker"), ownerPackId = id,
            )
        ) { _, _ ->
            runCatching {
                context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerImeSet(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.ime.default.set"), FeatureKind.ACTION,
                "Set default input method", "Select an enabled Android input method through the privileged ime command",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Text("imeId", "Input-method ID", true)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("ime", "keyboard", "input method", "default"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val command = imeSetCommand(feature.config.string("imeId").resolveVariables(ctx.variables))
                ?: return@registerAction invalidInput()
            executeShell("android.ime.default.set", command, ctx)
        }
    }

    private fun registerImeSettings(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.ime.settings.open"), FeatureKind.ACTION,
                "Open input-method settings", "Open Android's keyboard and input-method settings page",
                FeatureCategory.SYSTEM,
                keywords = setOf("ime", "keyboard", "settings", "input method"), ownerPackId = id,
            )
        ) { _, _ ->
            runCatching {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerWake(registry: FeatureRegistry) {
        shellAction(
            registry, "android.screen.wake", "Wake device", "Wake the display with Android's WAKEUP key event",
            FeatureCategory.DISPLAY, "input keyevent 224", setOf("wake", "screen", "display", "keyevent")
        )
    }

    private fun registerReboot(registry: FeatureRegistry) {
        shellAction(
            registry, "android.device.reboot", "Reboot device", "Request a normal Android reboot through the power service",
            FeatureCategory.SYSTEM, "svc power reboot", setOf("reboot", "restart", "power")
        )
    }

    private fun registerScreenshot(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.screen.screenshot"), FeatureKind.ACTION,
                "Capture screenshot", "Capture the current display to a PNG file through an authorized privileged shell backend",
                FeatureCategory.DISPLAY,
                fields = listOf(
                    FieldSchema.Text("path", "Output PNG path", true),
                    FieldSchema.Variable("resultVariable", "Store output path in variable"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("screenshot", "screen capture", "png", "display"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val path = feature.config.string("path").resolveVariables(ctx.variables).trim()
            val command = screenshotCommand(path) ?: return@registerAction invalidInput()
            val result = executeShell("android.screen.screenshot", command, ctx)
            if (!result.success) return@registerAction result
            val output = ConfigValue.StringValue(path)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output, result.message)
        }
    }

    private fun registerKeyEvent(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.input.keyevent"), FeatureKind.ACTION,
                "Send key event", "Send one Android key code through the privileged input command",
                FeatureCategory.UI_AUTOMATION,
                fields = listOf(FieldSchema.Number("keyCode", "Android key code", true, min = 0.0, max = 1000.0)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("keyevent", "key code", "input", "button"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val command = keyEventCommand(feature.config["keyCode"].numberOrNull()?.toInt())
                ?: return@registerAction invalidInput()
            executeShell("android.input.keyevent", command, ctx)
        }
    }

    private fun defaultLauncherPair(registry: FeatureRegistry) {
        pair(
            registry, "default_launcher", "Default launcher", "Match Android's current default home application",
            FeatureCategory.APP, listOf(FieldSchema.AppPicker("package", "App / package", true))
        ) { feature, ctx ->
            val expected = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val actual = context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName.orEmpty()
            expected.isNotBlank() && actual == expected
        }
    }

    private fun processRunningPair(registry: FeatureRegistry) {
        pair(
            registry, "process_running", "App process running", "Check whether pidof reports a running process for an application",
            FeatureCategory.APP,
            listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Toggle("value", "Running")),
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
        ) { feature, ctx ->
            val command = processRunningCommand(feature.config.string("package").resolveVariables(ctx.variables))
                ?: return@pair false
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "android.process.running",
                    payload = mapOf("command" to ConfigValue.StringValue(command)),
                )
            )
            val running = result.success && shellStdout(result.value).trim().equals("true", ignoreCase = true)
            running == feature.config.boolean("value", true)
        }
    }

    private fun booleanPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        query: () -> Boolean,
    ) = pair(
        registry, key, title, description, category, listOf(FieldSchema.Toggle("value", "Enabled / true"))
    ) { feature, _ -> query() == feature.config.boolean("value", true) }

    private fun textPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fieldKey: String,
        fieldLabel: String,
        query: () -> String,
    ) = pair(
        registry, key, title, description, category,
        listOf(FieldSchema.Text(fieldKey, fieldLabel, true), FieldSchema.Toggle("exact", "Exact match"))
    ) { feature, ctx ->
        val expected = feature.config.string(fieldKey).resolveVariables(ctx.variables)
        val actual = query()
        if (feature.config.boolean("exact")) actual.equals(expected, ignoreCase = true)
        else actual.contains(expected, ignoreCase = true)
    }

    private fun pair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        capabilities: Set<CapabilityId> = emptySet(),
        evaluate: suspend (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> Boolean,
    ) {
        val evaluator = ConditionEvaluator { feature, ctx -> runCatching { evaluate(feature, ctx) }.getOrDefault(false) }
        val state = FeatureDescriptor(
            FeatureId("android.state.$key"), FeatureKind.STATE, title, description, category,
            fields = fields, capabilities = capabilities, ownerPackId = id,
        )
        val condition = state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION)
        registry.registerState(state, evaluator)
        registry.registerCondition(condition, evaluator)
    }

    private fun shellAction(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        command: String,
        keywords: Set<String>,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.ACTION, title, description, category,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL), keywords = keywords, ownerPackId = id,
            )
        ) { _, ctx -> executeShell(typeId, command, ctx) }
    }

    private fun invalidInput() = ActionExecutionResult(false, message = userText("feature.privileged_input_invalid"))

    private suspend fun executeShell(operationId: String, command: String, ctx: FeatureExecutionContext): ActionExecutionResult {
        val result = ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = operationId,
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )
        return ActionExecutionResult(result.success, result.value, result.message)
    }
}

internal fun imeSetCommand(rawImeId: String): String? {
    val imeId = rawImeId.trim()
    if (!IME_ID.matches(imeId)) return null
    return "ime set ${shellQuote(imeId)}"
}

internal fun screenshotCommand(rawPath: String): String? {
    val path = rawPath.trim()
    if (path.isBlank() || !path.lowercase().endsWith(".png") || path.indexOf('\u0000') >= 0) return null
    return "screencap -p ${shellQuote(path)}"
}

internal fun keyEventCommand(code: Int?): String? = code?.takeIf { it in 0..1000 }?.let { "input keyevent $it" }

internal fun processRunningCommand(rawPackage: String): String? {
    val packageName = rawPackage.trim()
    if (!isValidPackageName(packageName)) return null
    return "if pidof $packageName >/dev/null 2>&1; then echo true; else echo false; fi"
}

private val IME_ID = Regex("[A-Za-z0-9_.]+/(?:\\.[A-Za-z0-9_$]+|[A-Za-z0-9_.$]+)")
