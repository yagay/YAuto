package com.yagay.yauto

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

        // Check authorization before calling su. Loading a picker must not run
        // a privileged command merely because Root happens to be installed.
        val rootGranted = try {
            rootShell.isAvailable()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { false }

        val saved = if (live == null && rootGranted) {
            val output = rootShell.run("settings get secure sysui_qs_tiles", 4_000)
            if (!output.timedOut && output.exitCode == 0) {
                output.stdout.trim().takeIf { it.isNotBlank() && it != "null" }
                    ?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.take(80)
            } else null
        } else null
        val panelSpecs = live ?: saved
        // Android's app-visibility rules and disabled packages may hide valid
        // third-party TileServices from an ordinary PackageManager query.
        // Root enumerates service components only; it never toggles those tiles.
        val rootComponents = if (rootGranted) {
            val cmd = "cmd package query-services --components --user current -a " + TileService.ACTION_QS_TILE
            val output = rootShell.run(cmd, 6_000)
            if (output.exitCode == 0 && !output.timedOut) parseRootTileComponents(output.stdout)
            else emptyList()
        } else emptyList()

        val source = when {
            live != null -> "lsposed_systemui"
            saved != null -> "root_saved_config"
            rootComponents.isNotEmpty() -> "root_service_inventory"
            else -> "installed_services_only"
        }

        val pm = context.packageManager
        val installed = pm.queryIntentServices(
            Intent(TileService.ACTION_QS_TILE),
            PackageManager.GET_META_DATA,
        ).mapNotNull { resolved ->
            val service = resolved.serviceInfo ?: return@mapNotNull null
            if (service.permission != "android.permission.BIND_QUICK_SETTINGS_TILE") {
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

        val rootInstalled = rootComponents.mapNotNull { component ->
            // When metadata is visible, reject services lacking the required
            // binding permission. Root-enumerated components can still be shown
            // if Android hides their package metadata from the YAuto UID.
            val service = runCatching {
                pm.getServiceInfo(
                    component,
                    PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS,
                )
            }.getOrNull()
            if (service != null &&
                service.permission != "android.permission.BIND_QUICK_SETTINGS_TILE"
            ) return@mapNotNull null
            val label = service?.let { runCatching { it.loadLabel(pm)?.toString() }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: component.shortClassName
            DeviceTileEntry(
                spec = "custom(" + component.flattenToShortString() + ")",
                label = label,
                component = component.flattenToString(),
            )
        }
        val allInstalled = (installed + rootInstalled).distinctBy { it.component }

        // Match component identity, not literal short/full class spellings.
        fun parsedComponent(spec: String): String? =
            if (spec.startsWith("custom(") && spec.endsWith(")")) {
                ComponentName.unflattenFromString(spec.substring(7, spec.length - 1))
                    ?.flattenToString()
            } else null
        val installedByComponent = allInstalled.mapNotNull { entry ->
            entry.component?.let { it to entry }
        }.toMap()
        val listed = panelSpecs.orEmpty().mapNotNull { spec ->
            val normalized = spec.trim().takeIf { it.length in 1..250 } ?: return@mapNotNull null
            val component = parsedComponent(normalized)
            installedByComponent[component.orEmpty()]?.copy(
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
        val extras = allInstalled.filter { it.component !in listedComponents }
            .map { it.copy(isInPanel = panelSpecs?.let { false }) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        DeviceTileInventory(source, (listed + extras).distinctBy { it.spec }.take(200))
    }

    private fun parseRootTileComponents(stdout: String): List<ComponentName> =
        stdout.lineSequence()
            .map(String::trim)
            .filter { ROOT_COMPONENT.matches(it) }
            .mapNotNull(ComponentName::unflattenFromString)
            .distinctBy(ComponentName::flattenToString)
            .take(200)
            .toList()

    private companion object {
        // Accept only bare components from 'query-services --components'.
        // Ignore errors, headings and shell diagnostics; never execute output.
        val ROOT_COMPONENT = Regex("[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+")
    }
}
