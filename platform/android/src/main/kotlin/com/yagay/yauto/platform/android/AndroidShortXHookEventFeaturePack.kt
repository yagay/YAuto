package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

/**
 * User-facing events backed by the ShortX-compatible LSPosed observer layer.
 * A shared filter schema keeps the catalog maintainable while preserving stable event IDs.
 */
class AndroidShortXHookEventFeaturePack : FeaturePack {
    override val id: String = "android.shortx_hook_events"

    override fun install(registry: FeatureRegistry) {
        event(registry, "android.event.input_filter_state_changed", "Input filter state changed", "Run when Android changes the native input-filter state", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.activity_manager_started", "Activity manager started", "Run when ActivityManagerService starts", FeatureCategory.SYSTEM)
        event(registry, "android.event.activity_manager_ready", "Activity manager ready", "Run when ActivityManagerService finishes systemReady", FeatureCategory.SYSTEM)
        event(registry, "android.event.activity_manager_shell_command", "Activity manager shell command", "Run when ActivityManagerShellCommand receives a command", FeatureCategory.SYSTEM)
        event(registry, "android.event.input_manager_started", "Input manager started", "Run when InputManagerService starts", FeatureCategory.SYSTEM)
        event(registry, "android.event.window_manager_ready", "Window manager ready", "Run when WindowManagerService finishes systemReady", FeatureCategory.SYSTEM)
        event(registry, "android.event.activity_started", "Activity started by system", "Run when ActivityTaskSupervisor starts an Activity", FeatureCategory.APP)
        event(registry, "android.event.task_cleanup", "Task cleanup", "Run when ActivityTaskSupervisor cleans up a removed task", FeatureCategory.APP)
        event(registry, "android.event.activity_resumed", "Activity resumed by system", "Run when system_server reports an Activity resumed", FeatureCategory.APP)
        event(registry, "android.event.activity_state_changed", "Activity state changed", "Run when ActivityRecord changes lifecycle state", FeatureCategory.APP)
        event(registry, "android.event.activity_stopped", "Activity stopped by system", "Run when system_server reports an Activity stopped", FeatureCategory.APP)
        event(registry, "android.event.activity_start_requested", "Activity start requested", "Run when ActivityStarter receives a start request", FeatureCategory.APP)
        event(registry, "android.event.activity_launched", "Activity launch observed", "Run when ActivityMetricsLogger reports an Activity launch", FeatureCategory.APP)
        event(registry, "android.event.notification_posted_system", "System notification posted", "Run when NotificationUsageStats records a posted notification", FeatureCategory.NOTIFICATION)
        event(registry, "android.event.notification_updated_system", "System notification updated", "Run when NotificationUsageStats records an updated notification", FeatureCategory.NOTIFICATION)
        event(registry, "android.event.notification_removed_system", "System notification removed", "Run when NotificationUsageStats records a removed notification", FeatureCategory.NOTIFICATION)
        event(registry, "android.event.notification_dismissed_system", "System notification dismissed", "Run when NotificationUsageStats records a user-dismissed notification", FeatureCategory.NOTIFICATION)
        event(registry, "android.event.clipboard_read_system", "Clipboard read by system client", "Run when ClipboardService serves a primary-clip request", FeatureCategory.SYSTEM)
        event(registry, "android.event.vpn_state_changed", "VPN state changed by system", "Run when the system VPN implementation changes state", FeatureCategory.NETWORK)
        event(registry, "android.event.accessibility_user_state_created", "Accessibility user state created", "Run when Android creates an AccessibilityUserState instance", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.accessibility_display_list_queried", "Accessibility display list queried", "Run when AccessibilityWindowManager reads the accessible display list", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.accessibility_windows_queried", "Accessibility windows queried", "Run when an accessibility service connection requests windows", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.accessibility_ui_automation_checked", "Accessibility UI automation checked", "Run when UiAutomationManager checks interactive-window access or suppression state", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.ime_shown", "IME shown by system", "Run when InputMethodManagerService shows the current input method", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.ime_hidden", "IME hidden by system", "Run when InputMethodManagerService hides the current input method", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.ime_input_started", "IME input started", "Run when InputMethodManagerService starts input or gains a focused window", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.window_focus_changed", "Window focus changed by system", "Run when WindowManager reports a focus change", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.window_added", "Window added by display policy", "Run when DisplayPolicy adds a window", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.rotation_proposed", "Display rotation proposed", "Run when Android proposes a new display rotation", FeatureCategory.DISPLAY)
        event(registry, "android.event.widget_host_listening", "Widget host started listening", "Run when AppWidgetService starts a host listening session", FeatureCategory.APP)
        event(registry, "android.event.shortcut_query", "Shortcut query observed", "Run when Android queries application shortcuts", FeatureCategory.APP)
        event(registry, "android.event.shortcut_pin_requested", "Shortcut pin requested", "Run when ShortcutService receives a pin request", FeatureCategory.APP)
        event(registry, "android.event.shortcut_started", "Shortcut started by system", "Run when Android starts an app shortcut", FeatureCategory.APP)
        event(registry, "android.event.back_pressed_system", "System back press observed", "Run when system_server handles a task or activity back press", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.package_query_system", "System package query", "Run when PackageManager computer queries services or activities", FeatureCategory.APP)
        event(registry, "android.event.status_bar_icon_changed", "Status bar icon changed", "Run when StatusBarManagerService updates an icon", FeatureCategory.SYSTEM)
        event(registry, "android.event.service_screen_state_changed", "Service screen state changed", "Run when ActiveServices receives a screen-state update", FeatureCategory.SYSTEM)
        event(registry, "android.event.systemui_app_ready", "SystemUI application ready", "Run after the SystemUI application initializes", FeatureCategory.SYSTEM)
        event(registry, "android.event.systemui_qs_host_ready", "SystemUI QS host ready", "Run when a Quick Settings host or host adapter is created", FeatureCategory.SYSTEM)
        event(registry, "android.event.systemui_auto_hide_ready", "SystemUI auto-hide controller ready", "Run when the SystemUI auto-hide controller is created", FeatureCategory.SYSTEM)
        event(registry, "android.event.systemui_tile_label_queried", "SystemUI tile label queried", "Run when SystemUI requests a custom tile label", FeatureCategory.SYSTEM)
        event(registry, "android.event.systemui_qs_tile_clicked", "SystemUI tile clicked", "Run when SystemUI handles a Quick Settings tile click", FeatureCategory.SYSTEM)
        event(registry, "android.event.systemui_icon_dark_changed", "SystemUI icon darkness changed", "Run when SystemUI changes light or dark status-bar icons", FeatureCategory.SYSTEM)
        event(registry, "android.event.systemui_tile_discovered", "SystemUI tile discovered", "Run when the Quick Settings customizer discovers a tile", FeatureCategory.SYSTEM)
        event(registry, "android.event.systemui_status_bar_ready", "SystemUI status bar ready", "Run when the status-bar view finishes inflation", FeatureCategory.SYSTEM)
        event(registry, "android.event.process_uncaught_exception", "Process uncaught exception", "Run when RuntimeInit handles an uncaught exception in a scoped process", FeatureCategory.APP)
        event(registry, "android.event.rendernode_crash_suppressed", "RenderNode crash suppressed", "Run when the ShortX-compatible RenderNode guard suppresses an animator crash", FeatureCategory.APP)
        event(registry, "android.event.input_text_committed", "Input text committed", "Run when a hooked input connection commits text", FeatureCategory.UI_AUTOMATION)
        event(registry, "android.event.sms_provider_ready", "SMS provider ready", "Run after the telephony SMS provider initializes", FeatureCategory.APP)
        event(registry, "android.event.sms_provider_changed", "SMS provider changed", "Run when the telephony SMS provider inserts, updates or deletes rows", FeatureCategory.APP)
        event(registry, "android.event.nfc_tag_system", "NFC tag from system service", "Run when the NFC service dispatches a tag endpoint before app routing", FeatureCategory.DEVICE)
        event(registry, "android.event.media_provider_ready", "Media provider ready", "Run after the MediaProvider initializes", FeatureCategory.FILE)
        event(registry, "android.event.media_provider_changed", "Media provider changed", "Run when the MediaProvider inserts, updates or deletes content", FeatureCategory.FILE)
    }

    private fun event(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
    ) {
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(typeId),
                kind = FeatureKind.EVENT,
                title = title,
                description = description,
                category = category,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package"),
                    FieldSchema.Text("classContains", "Class contains"),
                    FieldSchema.Text("methodContains", "Hook method contains"),
                    FieldSchema.Text("detailContains", "Detail contains"),
                ),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("shortx", "lsposed", "system hook", "system_server"),
                ownerPackId = id,
                domain = shortXHookDomain(typeId, category),
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != typeId) return@registerEvent false
            val packageName = feature.config.string("package").trim()
            val classContains = feature.config.string("classContains").trim()
            val methodContains = feature.config.string("methodContains").trim()
            val detailContains = feature.config.string("detailContains").trim()
            (packageName.isBlank() || ctx.event.payload.string("package") == packageName) &&
                (classContains.isBlank() || ctx.event.payload.string("className").contains(classContains, true)) &&
                (methodContains.isBlank() || ctx.event.payload.string("method").contains(methodContains, true)) &&
                (detailContains.isBlank() || ctx.event.payload.values.any { value ->
                    value.toString().contains(detailContains, true)
                })
        }
    }
}


internal fun shortXHookDomain(typeId: String, category: FeatureCategory): FeatureDomain {
    val key = typeId.lowercase()
    return when {
        key.contains("notification_") -> FeatureDomain.NOTIFICATIONS
        key.contains("sms_provider") -> FeatureDomain.COMMUNICATION
        key.contains("media_provider") -> FeatureDomain.FILES_STORAGE
        key.contains("nfc_tag") || key.contains("vpn_state") -> FeatureDomain.CONNECTIVITY
        key.contains("rotation_") -> FeatureDomain.DISPLAY
        key.contains("clipboard_") -> FeatureDomain.DATA
        key.contains("accessibility_") ||
            key.contains("input_") ||
            key.contains("ime_") ||
            key.contains("window_") ||
            key.contains("back_pressed_system") ||
            key.contains("systemui_") ||
            key.contains("status_bar_") ||
            key.contains("service_screen_state") -> FeatureDomain.USER_INPUT
        key.contains("activity_") ||
            key.contains("task_cleanup") ||
            key.contains("process_uncaught") ||
            key.contains("rendernode_") ||
            key.contains("widget_host") ||
            key.contains("shortcut_") ||
            key.contains("package_query") -> FeatureDomain.APPLICATIONS
        else -> inferFeatureDomain(typeId, category)
    }
}
