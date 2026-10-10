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


internal fun AccessibilityFeaturePack.registerAccessibilityEventsAndConditions(registry: FeatureRegistry) {
        condition(registry, "accessibility.condition.text_present", AccessibilityOperations.FIND_TEXT, "Text on screen", "Check whether text/content description is currently visible", listOf(FieldSchema.Text("text", "Text", true), FieldSchema.Toggle("exact", "Exact text match")), setOf("text present", "screen text", "ui", "accessibility"))
        condition(registry, "accessibility.condition.text_matches", AccessibilityOperations.FIND_TEXT_ADVANCED, "Screen text matches", "Check visible text using contains, exact or regular-expression matching", listOf(FieldSchema.Text("text", "Text / pattern", true), FieldSchema.Choice("mode", "Match mode", true, listOf("contains", "exact", "regex")), FieldSchema.Toggle("ignoreCase", "Ignore case")), setOf("text present", "regex", "screen content", "shortx"))
        condition(registry, "accessibility.condition.view_id_present", AccessibilityOperations.FIND_VIEW_ID, "View ID on screen", "Check whether a resource ID exists in the active window", listOf(FieldSchema.Text("viewId", "View ID", true)), setOf("view id", "resource id", "exists", "ui"))

        foregroundEvent(registry, "android.event.app_foreground", "App became foreground")
        foregroundEvent(registry, "android.event.app_background", "App left foreground")
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.window_changed"), FeatureKind.EVENT, "Foreground window changed",
                "Run whenever Accessibility reports a different foreground package or Activity", FeatureCategory.APP,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(FieldSchema.AppPicker("package", "App / package"), FieldSchema.Text("classContains", "Activity / class contains")),
                keywords = setOf("foreground", "activity", "window", "app"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.window_changed") return@registerEvent false
            matchForeground(feature, ctx.event.payload.string("package"), ctx.event.payload.string("class"))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.toast_shown"), FeatureKind.EVENT,
                "Toast / transient message shown",
                "Trigger when Accessibility reports a transient notification-state message such as an app Toast",
                FeatureCategory.UI_AUTOMATION,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Choice("mode", "Text match", options = listOf("any", "contains", "exact", "regex")),
                    FieldSchema.Text("text", "Text / pattern"),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("toast", "transient", "message", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.toast_shown") return@registerEvent false
            val pkg = feature.config.string("package")
            if (pkg.isNotBlank() && ctx.event.payload.string("package") != pkg) return@registerEvent false
            val mode = feature.config.string("mode", "any")
            mode == "any" || matchesText(
                ctx.event.payload.string("text"),
                feature.config.string("text"),
                mode,
                feature.config.boolean("ignoreCase", true),
            )
        }

        uiEvent(registry, "android.event.ui_click", "UI element clicked", "click")
        uiEvent(registry, "android.event.ui_long_click", "UI element long-clicked", "long_click")
        uiEvent(registry, "android.event.ui_text_changed", "UI text changed", "text_changed")
        uiEvent(registry, "android.event.ui_focused", "UI element focused", "focused")
        uiEvent(registry, "android.event.ui_scrolled", "UI content scrolled", "scrolled")
        screenContentEvent(registry)
        screenTextAppearedEvent(registry)
        fingerprintGestureEvent(registry)
        fingerprintGestureState(registry, FeatureKind.STATE, "android.state.fingerprint_gesture_available")
        fingerprintGestureState(registry, FeatureKind.CONDITION, "android.condition.fingerprint_gesture_available")

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("accessibility.current_window.get"), FeatureKind.ACTION,
                "Get current app / Activity",
                "Return the current Accessibility foreground package and Activity/class name",
                FeatureCategory.APP,
                capabilities = setOf(CapabilityIds.ACCESSIBILITY),
                fields = listOf(FieldSchema.Variable("resultVariable", "Store window object", true)),
                keywords = setOf("current activity", "current app", "foreground", "shortx", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val current = AccessibilityRuntimeBridge.currentWindow()
                ?: return@registerAction ActionExecutionResult(false)
            val value = ConfigValue.ObjectValue(
                mapOf(
                    "package" to ConfigValue.StringValue(current.packageName),
                    "class" to ConfigValue.StringValue(current.className.orEmpty()),
                    "timestamp" to ConfigValue.NumberValue(current.timestampEpochMs.toDouble()),
                )
            )
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, value) }
            ActionExecutionResult(true, value)
        }

        foregroundState(registry, FeatureKind.STATE, "android.state.app_foreground")
        foregroundState(registry, FeatureKind.CONDITION, "android.condition.app_foreground")
        keyboardState(registry, FeatureKind.STATE, "android.state.keyboard_visible")
        keyboardState(registry, FeatureKind.CONDITION, "android.condition.keyboard_visible")
}
