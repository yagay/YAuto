package com.yagay.yauto.platform.accessibility

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.capability.SystemOperations
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AccessibilityFeaturePack(
    internal val fallbackForeground: (() -> AccessibilityWindowSnapshot?)? = null,
) : FeaturePack {
    override val id: String = "accessibility.actions"

    override fun install(registry: FeatureRegistry) {
        registerAccessibilityActions(registry)
        registerAccessibilityEventsAndConditions(registry)
    }

    internal fun foregroundEvent(registry: FeatureRegistry, typeId: String, title: String) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.EVENT, title,
                "Match application foreground transitions from Accessibility with Usage Access fallback",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Text("classContains", "Activity / class contains")),
                keywords = setOf("foreground", "background", "app", "activity", "usage stats"), ownerPackId = id,
                implementationOptions = foregroundImplementationOptions(),
            )
        ) { feature, ctx ->
            ctx.event.typeId == typeId &&
                foregroundSourceMatches(feature, ctx.event.source) &&
                matchForeground(feature, ctx.event.payload.string("package"), ctx.event.payload.string("class"))
        }
    }

    internal fun uiEvent(registry: FeatureRegistry, typeId: String, title: String, eventName: String) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId),
                FeatureKind.EVENT,
                title,
                "Trigger on Accessibility UI interaction events and optionally filter app, text, content description or View ID",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Choice("textMode", "Text match", options = listOf("any", "contains", "exact", "regex")),
                    FieldSchema.Text("text", "Text / pattern"),
                    FieldSchema.Text("descriptionContains", "Content description contains"),
                    FieldSchema.Text("viewIdContains", "View ID contains"),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("ui click", "accessibility event", "view", eventName, "shortx", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != typeId) return@registerEvent false
            matchUiEvent(feature, ctx.event.payload)
        }
    }

    internal fun screenContentEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.screen_content_changed"),
                FeatureKind.EVENT,
                "Screen content matched",
                "Trigger when Accessibility reports changed screen content matching text or a regular expression",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Choice("mode", "Match mode", true, listOf("contains", "regex")),
                    FieldSchema.Text("text", "Text / pattern", true),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("screen content", "text appeared", "regex", "accessibility", "macrodroid", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.screen_content_changed") return@registerEvent false
            val pkg = feature.config.string("package")
            if (pkg.isNotBlank() && ctx.event.payload.string("package") != pkg) return@registerEvent false
            matchesText(
                actual = ctx.event.payload.string("screenText"),
                expected = feature.config.string("text"),
                mode = feature.config.string("mode", "contains"),
                ignoreCase = feature.config.boolean("ignoreCase", true),
            )
        }
    }

    internal fun screenTextAppearedEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.screen_text_appeared"), FeatureKind.EVENT,
                "Screen text appeared", "Trigger when changed Accessibility screen content contains matching text",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Choice("mode", "Match mode", true, listOf("contains", "exact", "regex")),
                    FieldSchema.Text("text", "Text / pattern", true),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("screen text", "appeared", "content", "accessibility", "visual"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.screen_content_changed") return@registerEvent false
            val pkg = feature.config.string("package")
            if (pkg.isNotBlank() && ctx.event.payload.string("package") != pkg) return@registerEvent false
            matchesText(
                actual = ctx.event.payload.string("screenText"),
                expected = feature.config.string("text"),
                mode = feature.config.string("mode", "contains"),
                ignoreCase = feature.config.boolean("ignoreCase", true),
            )
        }
    }

    internal fun fingerprintGestureEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.fingerprint_gesture"), FeatureKind.EVENT,
                "Fingerprint gesture", "Run when Accessibility detects a fingerprint-sensor swipe gesture",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.Choice("gesture", "Gesture", true, listOf("any", "swipe_up", "swipe_down", "swipe_left", "swipe_right")),
                ),
                keywords = setOf("fingerprint", "gesture", "swipe", "accessibility"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.fingerprint_gesture") return@registerEvent false
            val expected = feature.config.string("gesture", "any")
            expected == "any" || ctx.event.payload.string("gesture") == expected
        }
    }

    internal fun fingerprintGestureState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Fingerprint gestures available", "Check whether Accessibility can currently receive fingerprint gestures",
            FeatureCategory.UI_AUTOMATION,
            capabilities = setOf(CapabilityIds.ACCESSIBILITY),
            fields = listOf(FieldSchema.Toggle("value", "Available")),
            keywords = setOf("fingerprint", "gesture", "available"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            AccessibilityRuntimeBridge.isFingerprintGestureAvailable() == feature.config.boolean("value", true)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator)
        else registry.registerCondition(descriptor, evaluator)
    }

    internal fun matchUiEvent(feature: com.yagay.yauto.core.model.FeatureRef, payload: Map<String, ConfigValue>): Boolean {
        val pkg = feature.config.string("package")
        if (pkg.isNotBlank() && payload.string("package") != pkg) return false
        val ignoreCase = feature.config.boolean("ignoreCase", true)
        val mode = feature.config.string("textMode", "any")
        if (mode != "any" && !matchesText(payload.string("text"), feature.config.string("text"), mode, ignoreCase)) return false
        val description = feature.config.string("descriptionContains")
        if (description.isNotBlank() && !payload.string("description").contains(description, ignoreCase)) return false
        val viewId = feature.config.string("viewIdContains")
        if (viewId.isNotBlank() && !payload.string("viewId").contains(viewId, ignoreCase)) return false
        return true
    }

    internal fun keyboardState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId),
            kind,
            "Software keyboard visible",
            "Check whether Accessibility currently exposes an input-method window",
            FeatureCategory.UI_AUTOMATION,
            capabilities = setOf(CapabilityIds.ACCESSIBILITY),
            fields = listOf(FieldSchema.Toggle("value", "Visible")),
            keywords = setOf("keyboard", "ime", "input method", "soft keyboard", "macrodroid"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val result = ctx.capabilities.execute(
                CapabilityRequest(CapabilityIds.ACCESSIBILITY, AccessibilityOperations.KEYBOARD_VISIBLE)
            )
            result.success &&
                ((result.value as? ConfigValue.BooleanValue)?.value == feature.config.boolean("value", true))
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator)
        else registry.registerCondition(descriptor, evaluator)
    }

    internal fun foregroundState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind, "App in foreground",
            "Check the current foreground application using Accessibility with Usage Access fallback",
            FeatureCategory.APP,
            fields = listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Text("classContains", "Activity / class contains")),
            keywords = setOf("foreground", "current app", "activity", "usage stats"), ownerPackId = id,
            implementationOptions = foregroundImplementationOptions(),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val current = currentForeground(feature) ?: return@ConditionEvaluator false
            matchForeground(feature, current.packageName, current.className.orEmpty())
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    internal fun foregroundImplementationOptions(): List<FeatureImplementationOption> = listOf(
        FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
        FeatureImplementationOption("usage_stats", setOf(AccessRequirement.USAGE_STATS)),
    )

    internal fun currentForeground(feature: com.yagay.yauto.core.model.FeatureRef): AccessibilityWindowSnapshot? =
        when (feature.preferredBackendId()) {
            "accessibility" -> AccessibilityRuntimeBridge.currentWindow()
            "usage_stats" -> fallbackForeground?.invoke()
            else -> AccessibilityRuntimeBridge.currentWindow() ?: fallbackForeground?.invoke()
        }

    internal fun foregroundSourceMatches(feature: com.yagay.yauto.core.model.FeatureRef, source: String): Boolean =
        when (feature.preferredBackendId()) {
            "accessibility" -> source.isBlank() || source == ACCESSIBILITY_WINDOW_SOURCE
            "usage_stats" -> source == USAGE_STATS_SOURCE
            else -> true
        }

    internal fun matchForeground(feature: com.yagay.yauto.core.model.FeatureRef, pkg: String, className: String): Boolean {
        val expectedPackage = feature.config.string("package").trim()
        val classContains = feature.config.string("classContains").trim()
        return (expectedPackage.isBlank() || pkg == expectedPackage) &&
            (classContains.isBlank() || className.contains(classContains, ignoreCase = true))
    }

    internal fun registerDualMethodGlobalAction(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(AccessibilityOperations.GLOBAL_ACTION),
                FeatureKind.ACTION,
                "Global UI action",
                "No-root Android Accessibility/Shizuku or Root/LSPosed; required permissions vary by implementation",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY, CapabilityIds.SYSTEM_UI, CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = listOf(
                    FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
                    FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED), restartRequired = true),
                    FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                    FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
                ),
                fields = listOf(FieldSchema.Choice(
                    "action", "Action", true,
                    listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen"),
                )),
                keywords = setOf("macro", "macrodroid", "shortx", "root", "lsposed", "back", "home", "quick settings"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val action = feature.config.string("action")
            if (action !in setOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen")) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.dual_method_invalid_action"))
            }
            if (!feature.methodBackendIsCompatible()) {
                return@registerAction ActionExecutionResult(false,
                    message = userText("feature.dual_method_backend_mismatch"))
            }
            val backend = feature.preferredBackendId()
            val rootSupported = action != "power_dialog"
            val preferredMethod = if (!rootSupported && feature.preferredMethod() == FeatureMethod.AUTO)
                FeatureMethod.NO_ROOT else feature.preferredMethod()
            val result = routeFeatureMethod(
                method = preferredMethod,
                macroSupported = true,
                macrodroid = {
                    routeBackendCandidates(
                        backend,
                        if (action == "power_dialog") listOf("accessibility")
                            else listOf("accessibility", "shizuku"),
                        execute = { selected ->
                            if (selected == "accessibility") {
                                ctx.executeCapability(feature.typeId, CapabilityRequest(
                                    capability = CapabilityIds.ACCESSIBILITY,
                                    operationId = AccessibilityOperations.GLOBAL_ACTION,
                                    payload = mapOf("action" to ConfigValue.StringValue(action)),
                                    preferredBackendId = "accessibility", allowFallback = false,
                                ))
                            } else {
                                shortXGlobalActionRequest(action, "shizuku")?.let {
                                    ctx.executeCapability(feature.typeId, it)
                                } ?: CapabilityResult(false,
                                    message = userText("feature.dual_method_no_shortx", action))
                            }
                        },
                        succeeded = { it.success },
                    ) ?: CapabilityResult(false,
                        message = userText("feature.dual_method_backend_not_supported", action))
                },
                shortx = {
                    routeBackendCandidates(
                        backend,
                        if (action in setOf("lock_screen", "notifications", "quick_settings"))
                            listOf("lsposed", "root")
                        else if (rootSupported) listOf("root") else emptyList(),
                        execute = { selected ->
                            shortXGlobalActionRequest(action, selected)?.let {
                                ctx.executeCapability(feature.typeId, it)
                            } ?: CapabilityResult(false,
                                message = userText("feature.dual_method_no_shortx", action))
                        },
                        succeeded = { it.success },
                    ) ?: CapabilityResult(false,
                        message = userText("feature.dual_method_backend_not_supported", action))
                },
                succeeded = { it.success },
            )
            ActionExecutionResult(
                result?.success == true,
                result?.value ?: ConfigValue.NullValue,
                result?.message ?: if (result == null) userText("feature.dual_method_no_macro", action) else null,
            )
        }
    }

    internal fun shortXGlobalActionRequest(action: String, preferredBackend: String?): CapabilityRequest? {
        val operation = when (action) {
            "lock_screen" -> SystemOperations.SLEEP
            "notifications" -> SystemOperations.EXPAND_NOTIFICATIONS
            "quick_settings" -> SystemOperations.EXPAND_QUICK_SETTINGS
            else -> null
        }
        if (operation != null) {
            return CapabilityRequest(
                capability = CapabilityIds.SYSTEM_UI,
                operationId = operation,
                preferredBackendId = preferredBackend,
                allowFallback = preferredBackend == null,
            )
        }
        // These public input key codes have a Root/Shizuku path but no verified
        // system_server LSPosed injection route on every OEM.
        val code = when (action) {
            "back" -> 4
            "home" -> 3
            "recents" -> 187
            else -> return null
        }
        return CapabilityRequest(
            capability = CapabilityIds.PRIVILEGED_SHELL,
            operationId = "android.input.keyevent",
            payload = mapOf("command" to ConfigValue.StringValue("input keyevent $code")),
            preferredBackendId = preferredBackend,
            allowFallback = preferredBackend == null,
        )
    }

    internal fun action(registry: FeatureRegistry, typeId: String, title: String, description: String, fields: List<FieldSchema>, keywords: Set<String>) {
        registry.registerAction(
            FeatureDescriptor(id = FeatureId(typeId), kind = FeatureKind.ACTION, title = title, description = description, category = FeatureCategory.UI_AUTOMATION, capabilities = setOf(CapabilityIds.ACCESSIBILITY), fields = fields, keywords = keywords, ownerPackId = id)
        ) { feature, ctx ->
            val result = ctx.capabilities.execute(CapabilityRequest(CapabilityIds.ACCESSIBILITY, typeId, feature.config))
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    internal fun resultAction(registry: FeatureRegistry, typeId: String, title: String, description: String, fields: List<FieldSchema>, keywords: Set<String>) {
        registry.registerAction(
            FeatureDescriptor(id = FeatureId(typeId), kind = FeatureKind.ACTION, title = title, description = description, category = FeatureCategory.UI_AUTOMATION, capabilities = setOf(CapabilityIds.ACCESSIBILITY), fields = fields, keywords = keywords, ownerPackId = id)
        ) { feature, ctx ->
            val result = ctx.capabilities.execute(CapabilityRequest(CapabilityIds.ACCESSIBILITY, typeId, feature.config))
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, result.value) }
            }
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    internal fun condition(registry: FeatureRegistry, typeId: String, operationId: String, title: String, description: String, fields: List<FieldSchema>, keywords: Set<String>) {
        registry.registerCondition(
            FeatureDescriptor(id = FeatureId(typeId), kind = FeatureKind.CONDITION, title = title, description = description, category = FeatureCategory.UI_AUTOMATION, capabilities = setOf(CapabilityIds.ACCESSIBILITY), fields = fields, keywords = keywords, ownerPackId = id)
        ) { feature, ctx ->
            val result = ctx.capabilities.execute(CapabilityRequest(CapabilityIds.ACCESSIBILITY, operationId, feature.config))
            result.success && (result.value as? ConfigValue.BooleanValue)?.value == true
        }
    }

    internal fun matchesText(actual: String, expected: String, mode: String, ignoreCase: Boolean): Boolean {
        if (expected.isBlank()) return false
        return when (mode) {
            "exact" -> actual.equals(expected, ignoreCase)
            "regex" -> runCatching {
                Regex(expected, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(actual)
            }.getOrDefault(false)
            else -> actual.contains(expected, ignoreCase)
        }
    }

    private companion object {
        const val ACCESSIBILITY_WINDOW_SOURCE = "accessibility.window"
        const val USAGE_STATS_SOURCE = "android.usage.foreground"
    }
}
