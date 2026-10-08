package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.listOrEmpty
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.stringOrNull
import com.yagay.yauto.core.registry.*

/** 50 concrete Android broadcast triggers backed by [ReferenceCompletionBroadcastEventSource]. */
class AndroidReferenceCompletionEventFeaturePack : FeaturePack {
    override val id: String = "android.reference_completion.events"

    override fun install(registry: FeatureRegistry) {
        REFERENCE_COMPLETION_EVENT_SPECS.forEach { spec ->
            val fields = when (spec.filter) {
                CompletionEventFilter.PACKAGE -> listOf(FieldSchema.Text("packageContains", "Package contains"))
                CompletionEventFilter.PATH -> listOf(FieldSchema.Text("pathContains", "Path / URI contains"))
                CompletionEventFilter.NONE -> emptyList()
            }
            registry.registerEvent(
                FeatureDescriptor(
                    id = FeatureId(spec.typeId),
                    kind = FeatureKind.EVENT,
                    title = spec.title,
                    description = spec.description,
                    category = spec.category,
                    fields = fields,
                    keywords = spec.keywords,
                    ownerPackId = id,
                )
            ) { feature, ctx ->
                if (ctx.event.typeId != spec.typeId) return@registerEvent false
                when (spec.filter) {
                    CompletionEventFilter.NONE -> true
                    CompletionEventFilter.PACKAGE -> {
                        val expected = feature.config.string("packageContains").resolveVariables(ctx.variables).trim()
                        if (expected.isBlank()) true else {
                            val direct = ctx.event.payload["package"].stringOrNull().orEmpty()
                            val listed = ctx.event.payload["packages"].listOrEmpty().mapNotNull { it.stringOrNull() }
                            direct.contains(expected, ignoreCase = true) || listed.any { it.contains(expected, ignoreCase = true) }
                        }
                    }
                    CompletionEventFilter.PATH -> {
                        val expected = feature.config.string("pathContains").resolveVariables(ctx.variables).trim()
                        expected.isBlank() || ctx.event.payload["data"].stringOrNull().orEmpty().contains(expected, ignoreCase = true)
                    }
                }
            }
        }
    }
}

internal enum class CompletionEventFilter { NONE, PACKAGE, PATH }

internal data class ReferenceCompletionEventSpec(
    val typeId: String,
    val title: String,
    val description: String,
    val category: FeatureCategory,
    val filter: CompletionEventFilter = CompletionEventFilter.NONE,
    val keywords: Set<String> = emptySet(),
)

internal val REFERENCE_COMPLETION_EVENT_SPECS: List<ReferenceCompletionEventSpec> = listOf(
    ReferenceCompletionEventSpec("android.event.shutdown", "Device shutting down", "Trigger when Android begins a normal shutdown", FeatureCategory.SYSTEM, keywords = setOf("shutdown", "power off")),
    ReferenceCompletionEventSpec("android.event.user_unlocked", "User unlocked after boot", "Trigger when credential-encrypted storage becomes available after boot", FeatureCategory.SYSTEM, keywords = setOf("unlock", "boot", "user")),
    ReferenceCompletionEventSpec("android.event.wallpaper_changed", "Wallpaper changed", "Trigger when the Android wallpaper changes", FeatureCategory.DISPLAY, keywords = setOf("wallpaper", "background")),
    ReferenceCompletionEventSpec("android.event.dock_changed", "Dock state changed", "Trigger when the physical dock state changes", FeatureCategory.DEVICE, keywords = setOf("dock", "desk", "car")),
    ReferenceCompletionEventSpec("android.event.dreaming_started", "Screen saver started", "Trigger when Android dreaming/screensaver mode starts", FeatureCategory.DISPLAY, keywords = setOf("dream", "screensaver")),
    ReferenceCompletionEventSpec("android.event.dreaming_stopped", "Screen saver stopped", "Trigger when Android dreaming/screensaver mode stops", FeatureCategory.DISPLAY, keywords = setOf("dream", "screensaver")),
    ReferenceCompletionEventSpec("android.event.device_idle_mode_changed", "Device idle mode changed", "Trigger when Android Doze/device-idle mode changes", FeatureCategory.DEVICE, keywords = setOf("doze", "idle", "battery")),
    ReferenceCompletionEventSpec("android.event.light_device_idle_mode_changed", "Light device idle changed", "Trigger when Android light-idle mode changes", FeatureCategory.DEVICE, keywords = setOf("doze", "light idle", "battery")),
    ReferenceCompletionEventSpec("android.event.next_alarm_changed", "Next alarm changed", "Trigger when Android's next scheduled alarm clock changes", FeatureCategory.SYSTEM, keywords = setOf("alarm", "clock")),
    ReferenceCompletionEventSpec("android.event.audio_becoming_noisy", "Audio becoming noisy", "Trigger before audio routing changes in a way that may become noisy", FeatureCategory.AUDIO, keywords = setOf("audio", "headphones", "noisy")),
    ReferenceCompletionEventSpec("android.event.ringer_mode_changed", "Ringer mode changed", "Trigger when Android ringer mode changes", FeatureCategory.AUDIO, keywords = setOf("ringer", "silent", "vibrate")),
    ReferenceCompletionEventSpec("android.event.bluetooth_connection_state_changed", "Bluetooth connection state changed", "Trigger when aggregate Bluetooth adapter connection state changes", FeatureCategory.NETWORK, keywords = setOf("bluetooth", "connection")),
    ReferenceCompletionEventSpec("android.event.bluetooth_discovery_started", "Bluetooth discovery started", "Trigger when Bluetooth device discovery starts", FeatureCategory.NETWORK, keywords = setOf("bluetooth", "discovery", "scan")),
    ReferenceCompletionEventSpec("android.event.bluetooth_discovery_finished", "Bluetooth discovery finished", "Trigger when Bluetooth device discovery finishes", FeatureCategory.NETWORK, keywords = setOf("bluetooth", "discovery", "scan")),
    ReferenceCompletionEventSpec("android.event.bluetooth_acl_disconnect_requested", "Bluetooth ACL disconnect requested", "Trigger when a Bluetooth ACL disconnect is requested", FeatureCategory.NETWORK, keywords = setOf("bluetooth", "acl", "disconnect")),
    ReferenceCompletionEventSpec("android.event.wifi_supplicant_state_changed", "Wi-Fi supplicant state changed", "Trigger when Wi-Fi supplicant state changes", FeatureCategory.NETWORK, keywords = setOf("wifi", "supplicant", "state")),
    ReferenceCompletionEventSpec("android.event.wifi_supplicant_connection_changed", "Wi-Fi supplicant connection changed", "Trigger when the Wi-Fi supplicant connects or disconnects", FeatureCategory.NETWORK, keywords = setOf("wifi", "supplicant", "connection")),
    ReferenceCompletionEventSpec("android.event.package_changed", "Application package changed", "Trigger when components in an installed application package change", FeatureCategory.APP, CompletionEventFilter.PACKAGE, setOf("package", "app", "changed")),
    ReferenceCompletionEventSpec("android.event.package_fully_removed", "Application fully removed", "Trigger after an application package and its data are fully removed", FeatureCategory.APP, CompletionEventFilter.PACKAGE, setOf("package", "app", "removed")),
    ReferenceCompletionEventSpec("android.event.package_data_cleared", "Application data cleared", "Trigger when user data for an application package is cleared", FeatureCategory.APP, CompletionEventFilter.PACKAGE, setOf("package", "app", "clear data")),
    ReferenceCompletionEventSpec("android.event.package_restarted", "Application package restarted", "Trigger when Android reports an application package restart", FeatureCategory.APP, CompletionEventFilter.PACKAGE, setOf("package", "app", "restart")),
    ReferenceCompletionEventSpec("android.event.packages_suspended", "Applications suspended", "Trigger when one or more application packages are suspended", FeatureCategory.APP, CompletionEventFilter.PACKAGE, setOf("package", "app", "suspend")),
    ReferenceCompletionEventSpec("android.event.packages_unsuspended", "Applications unsuspended", "Trigger when one or more application packages are unsuspended", FeatureCategory.APP, CompletionEventFilter.PACKAGE, setOf("package", "app", "unsuspend")),
    ReferenceCompletionEventSpec("android.event.external_apps_available", "External applications available", "Trigger when applications on external storage become available", FeatureCategory.APP, CompletionEventFilter.PACKAGE, setOf("external", "package", "available")),
    ReferenceCompletionEventSpec("android.event.external_apps_unavailable", "External applications unavailable", "Trigger when applications on external storage become unavailable", FeatureCategory.APP, CompletionEventFilter.PACKAGE, setOf("external", "package", "unavailable")),
    ReferenceCompletionEventSpec("android.event.media_mounted", "Storage media mounted", "Trigger when external storage media is mounted", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "storage", "mounted")),
    ReferenceCompletionEventSpec("android.event.media_unmounted", "Storage media unmounted", "Trigger when external storage media is present but unmounted", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "storage", "unmounted")),
    ReferenceCompletionEventSpec("android.event.media_eject", "Storage media eject requested", "Trigger when Android asks applications to release external storage media", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "storage", "eject")),
    ReferenceCompletionEventSpec("android.event.media_removed", "Storage media removed", "Trigger when external storage media is removed", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "storage", "removed")),
    ReferenceCompletionEventSpec("android.event.media_bad_removal", "Storage media bad removal", "Trigger when external storage media is removed without being unmounted", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "storage", "bad removal")),
    ReferenceCompletionEventSpec("android.event.media_checking", "Storage media checking", "Trigger while Android checks newly attached storage media", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "storage", "checking")),
    ReferenceCompletionEventSpec("android.event.media_nofs", "Storage media has no filesystem", "Trigger when attached storage media has no supported filesystem", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "storage", "filesystem")),
    ReferenceCompletionEventSpec("android.event.media_unmountable", "Storage media unmountable", "Trigger when attached storage media cannot be mounted", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "storage", "unmountable")),
    ReferenceCompletionEventSpec("android.event.media_shared", "Storage media shared", "Trigger when external storage is shared through another transport", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "storage", "shared")),
    ReferenceCompletionEventSpec("android.event.media_scanner_started", "Media scan started", "Trigger when the system media scanner begins scanning a volume", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "scanner", "started")),
    ReferenceCompletionEventSpec("android.event.media_scanner_finished", "Media scan finished", "Trigger when the system media scanner finishes scanning a volume", FeatureCategory.FILE, CompletionEventFilter.PATH, setOf("media", "scanner", "finished")),
    ReferenceCompletionEventSpec("android.event.configuration_changed", "Device configuration changed", "Trigger on general Android configuration changes such as locale, display or input configuration", FeatureCategory.SYSTEM, keywords = setOf("configuration", "system", "change")),
    ReferenceCompletionEventSpec("android.event.user_background", "Android user moved to background", "Trigger when the current process receives an Android user-background transition", FeatureCategory.SYSTEM, keywords = setOf("user", "background", "profile")),
    ReferenceCompletionEventSpec("android.event.user_foreground", "Android user moved to foreground", "Trigger when the current process receives an Android user-foreground transition", FeatureCategory.SYSTEM, keywords = setOf("user", "foreground", "profile")),
    ReferenceCompletionEventSpec("android.event.my_package_replaced", "YAuto updated", "Trigger after the installed YAuto package is replaced by an update", FeatureCategory.APP, keywords = setOf("update", "package", "yauto")),
)
