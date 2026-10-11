package com.yagay.yauto

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import com.yagay.yauto.platform.root.RootShell
import com.yagay.yauto.platform.xposed.XposedBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A tile service existing on the device is not evidence it is added to the panel. */
internal data class DeviceTileEntry(
    val spec: String,
    val label: String,
    val component: String? = null,
    val isInPanel: Boolean? = null,
)

internal data class DeviceTileInventory(
    val source: String,
    val entries: List<DeviceTileEntry>,
)

/**
 * One canonical inventory:
 * 1. Live SystemUI QSHost specs over existing signature-protected LSPosed bridge.
 * 2. Root reads the saved secure setting if the host is not available.
 * 3. Installed app tile services are discovered using the public PackageManager.
 *
 * Android never gives an ordinary app a complete live panel tile-state API.
 */
internal class DeviceQuickSettingsInventory(
    context: Context,
    private val rootShell: RootShell,
    private val xposed: XposedBackend,
) {
    private val context = context.applicationContext

    suspend fun load(): DeviceTileInventory = withContext(Dispatchers.IO) {
        val live = try {
            xposed.quickSettingsTileSpecs()?.takeIf { it.isNotEmpty() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }

        val saved = if (live == null) {
            val output = rootShell.run("settings get secure sysui_qs_tiles", 4_000)
            if (!output.timedOut && output.exitCode == 0) {
                output.stdout.trim().takeIf { it.isNotBlank() && it != "null" }
                    ?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.take(80)
            } else null
        } else null
        val panelSpecs = live ?: saved
        val source = when {
            live != null -> "lsposed_systemui"
            saved != null -> "root_saved_config"
            else -> "installed_services_only"
        }

        val pm = context.packageManager
        val installed = pm.queryIntentServices(
            Intent(TileService.ACTION_QS_TILE),
            PackageManager.GET_META_DATA,
        ).mapNotNull { resolved ->
            val service = resolved.serviceInfo ?: return@mapNotNull null
            if (service.permission != Manifest.permission.BIND_QUICK_SETTINGS_TILE) {
                return@mapNotNull null
            }
            val component = ComponentName(service.packageName, service.name)
            val readable = runCatching { resolved.loadLabel(pm)?.toString().orEmpty() }
                .getOrDefault("")
                .ifBlank { service.name }
            DeviceTileEntry(
                spec = "custom(" + component.flattenToShortString() + ")",
                label = readable,
                component = component.flattenToString(),
            )
        }.distinctBy { it.component }

        // Match component identity, not literal short/full class spellings.
        fun parsedComponent(spec: String): String? =
            if (spec.startsWith("custom(") && spec.endsWith(")")) {
                ComponentName.unflattenFromString(spec.substring(7, spec.length - 1))
                    ?.flattenToString()
            } else null
        val installedByComponent = installed.mapNotNull { entry ->
            entry.component?.let { it to entry }
        }.toMap()
        val listed = panelSpecs.orEmpty().mapNotNull { spec ->
            val normalized = spec.trim().takeIf { it.length in 1..250 } ?: return@mapNotNull null
            val component = parsedComponent(normalized)
            installedByComponent[component]?.copy(
                spec = normalized, isInPanel = true,
            ) ?: DeviceTileEntry(
                spec = normalized,
                label = component?.let { ComponentName.unflattenFromString(it)?.shortClassName }
                    ?: normalized,
                component = component,
                isInPanel = true,
            )
        }
        val listedComponents = listed.mapNotNull(DeviceTileEntry::component).toSet()
        val extras = installed.filter { it.component !in listedComponents }
            .map { it.copy(isInPanel = panelSpecs?.let { false }) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        DeviceTileInventory(source, (listed + extras).distinctBy { it.spec }.take(200))
    }
}
