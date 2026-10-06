package com.yagay.yauto.ui.editor

import android.app.AppOpsManager
import android.app.Notification
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.UserManager
import android.provider.CalendarContract
import android.telephony.SubscriptionManager
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import com.yagay.yauto.core.registry.*
import java.util.Locale
import java.util.TimeZone

@Suppress("DEPRECATION")
internal fun loadSystemPickerOptions(
    context: Context,
    picker: FieldPickerSource,
    values: Map<String, String>,
    locale: Locale,
): List<SystemPickerOption> = runCatching {
    val comparator = localizedStringComparator(locale)
    when (picker) {
        FieldPickerSource.InstalledApp -> installedApps(context, locale).map {
            SystemPickerOption(it.packageName, it.label, it.packageName)
        }
        is FieldPickerSource.Permission -> {
            val pkg = values[picker.packageFieldKey].orEmpty().trim()
            if (pkg.isBlank()) {
                emptyList()
            } else {
                val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                info.requestedPermissions.orEmpty()
                    .distinct()
                    .sortedWith(comparator)
                    .map { SystemPickerOption(it, it) }
            }
        }
        is FieldPickerSource.Component -> loadComponentOptions(context, picker, values, comparator)
        FieldPickerSource.Subscription -> {
            val manager = context.getSystemService(SubscriptionManager::class.java)
            manager.activeSubscriptionInfoList.orEmpty()
                .sortedBy { it.simSlotIndex }
                .map { info ->
                    val label = info.displayName?.toString().orEmpty().ifBlank {
                        info.carrierName?.toString().orEmpty().ifBlank { info.subscriptionId.toString() }
                    }
                    SystemPickerOption(
                        value = info.subscriptionId.toString(),
                        label = label,
                        detail = info.subscriptionId.toString(),
                    )
                }
        }
        FieldPickerSource.Camera -> {
            context.getSystemService(CameraManager::class.java).cameraIdList
                .sortedWith(comparator)
                .map { SystemPickerOption(it, it) }
        }
        FieldPickerSource.AndroidUser -> {
            context.getSystemService(UserManager::class.java).userProfiles
                .map { userHandleIdentifier(it).toString() }
                .distinct()
                .sorted()
                .map { SystemPickerOption(it, it) }
        }
        FieldPickerSource.TimeZone -> TimeZone.getAvailableIDs()
            .distinct()
            .sortedWith(comparator)
            .map { SystemPickerOption(it, it) }
        FieldPickerSource.Locale -> Locale.getAvailableLocales()
            .mapNotNull { available ->
                available.toLanguageTag().takeIf { it.isNotBlank() }?.let { tag ->
                    SystemPickerOption(tag, available.getDisplayName(locale).ifBlank { tag }, tag)
                }
            }
            .distinctBy { it.value }
            .sortedWith { left, right -> comparator.compare(left.label, right.label) }
        FieldPickerSource.Calendar -> {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
            )
            val output = mutableListOf<SystemPickerOption>()
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex).toString()
                    val name = cursor.getString(nameIndex).orEmpty().ifBlank { id }
                    output += SystemPickerOption(id, name, cursor.getString(accountIndex))
                }
            }
            output
        }
        FieldPickerSource.InputMethod -> {
            context.getSystemService(InputMethodManager::class.java).inputMethodList
                .map { method ->
                    val label = runCatching {
                        method.loadLabel(context.packageManager).toString()
                    }.getOrDefault(method.id)
                    SystemPickerOption(method.id, label, method.id)
                }
                .distinctBy { it.value }
                .sortedWith { left, right -> comparator.compare(left.label, right.label) }
        }
        FieldPickerSource.KeyCode -> {
            KeyEvent::class.java.fields
                .asSequence()
                .filter { it.name.startsWith("KEYCODE_") && it.type == Int::class.javaPrimitiveType }
                .mapNotNull { field ->
                    runCatching {
                        val value = field.getInt(null).toString()
                        SystemPickerOption(value, field.name.removePrefix("KEYCODE_"), value)
                    }.getOrNull()
                }
                .distinctBy { it.value }
                .sortedWith { left, right -> comparator.compare(left.label, right.label) }
                .toList()
        }
        FieldPickerSource.WifiSsid -> {
            val manager = context.applicationContext.getSystemService(WifiManager::class.java)
            buildList {
                manager.connectionInfo?.ssid
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                    ?.let { add(SystemPickerOption(it, it)) }
                manager.scanResults.orEmpty().forEach { result ->
                    val ssid = result.SSID.orEmpty().trim()
                    if (ssid.isNotBlank()) add(SystemPickerOption(ssid, ssid, result.BSSID))
                }
            }.distinctBy { it.value }
                .sortedWith { left, right -> comparator.compare(left.label, right.label) }
        }
        FieldPickerSource.BluetoothDevice -> {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            adapter?.bondedDevices.orEmpty()
                .map { device ->
                    val address = device.address.orEmpty()
                    val label = device.name.orEmpty().ifBlank { address }
                    SystemPickerOption(address, label, address)
                }
                .distinctBy { it.value }
                .sortedWith { left, right -> comparator.compare(left.label, right.label) }
        }
        FieldPickerSource.LocationProvider -> {
            context.getSystemService(LocationManager::class.java).allProviders.orEmpty()
                .distinct()
                .sortedWith(comparator)
                .map { SystemPickerOption(it, it) }
        }
        FieldPickerSource.AppOperation -> {
            AppOpsManager::class.java.fields
                .asSequence()
                .filter { it.name.startsWith("OPSTR_") && it.type == String::class.java }
                .mapNotNull { field ->
                    runCatching {
                        val value = field.get(null)?.toString().orEmpty()
                        value.takeIf { it.isNotBlank() }?.let {
                            SystemPickerOption(it, field.name.removePrefix("OPSTR_"), it)
                        }
                    }.getOrNull()
                }
                .distinctBy { it.value }
                .sortedWith { left, right -> comparator.compare(left.label, right.label) }
                .toList()
        }
        FieldPickerSource.IntentAction -> androidConstantOptions(
            owner = Intent::class.java,
            prefix = "ACTION_",
            comparator = comparator,
        )
        FieldPickerSource.IntentCategory -> androidConstantOptions(
            owner = Intent::class.java,
            prefix = "CATEGORY_",
            comparator = comparator,
        )
        FieldPickerSource.NotificationCategory -> androidConstantOptions(
            owner = Notification::class.java,
            prefix = "CATEGORY_",
            comparator = comparator,
        )
        is FieldPickerSource.Options -> picker.options.map {
            SystemPickerOption(it.value, it.label, it.value.takeIf { value -> value != it.label })
        }
    }
}.getOrDefault(emptyList())

private fun androidConstantOptions(
    owner: Class<*>,
    prefix: String,
    comparator: Comparator<String>,
): List<SystemPickerOption> =
    owner.fields
        .asSequence()
        .filter { it.name.startsWith(prefix) && it.type == String::class.java }
        .mapNotNull { field ->
            runCatching {
                val value = field.get(null)?.toString().orEmpty()
                value.takeIf { it.isNotBlank() }?.let {
                    SystemPickerOption(it, field.name.removePrefix(prefix), it)
                }
            }.getOrNull()
        }
        .distinctBy { it.value }
        .sortedWith { left, right -> comparator.compare(left.label, right.label) }
        .toList()

private fun userHandleIdentifier(handle: android.os.UserHandle): Int =
    runCatching {
        val method = handle.javaClass.getDeclaredMethod("getIdentifier")
        method.isAccessible = true
        (method.invoke(handle) as Number).toInt()
    }.getOrElse { handle.hashCode() }

@Suppress("DEPRECATION")
private fun loadComponentOptions(
    context: Context,
    picker: FieldPickerSource.Component,
    values: Map<String, String>,
    comparator: Comparator<String>,
): List<SystemPickerOption> {
    val pm = context.packageManager
    val explicitPackage = picker.packageFieldKey?.let { values[it].orEmpty().trim() }.orEmpty()
    val packages = if (explicitPackage.isNotBlank()) {
        listOf(explicitPackage)
    } else {
        pm.getInstalledApplications(PackageManager.GET_META_DATA).map { it.packageName }
    }
    val flags =
        (if (ComponentPickerKind.ACTIVITY in picker.kinds) PackageManager.GET_ACTIVITIES else 0) or
            (if (
                ComponentPickerKind.SERVICE in picker.kinds ||
                ComponentPickerKind.QUICK_SETTINGS_TILE in picker.kinds
            ) PackageManager.GET_SERVICES else 0) or
            (if (ComponentPickerKind.RECEIVER in picker.kinds) PackageManager.GET_RECEIVERS else 0) or
            (if (ComponentPickerKind.PROVIDER in picker.kinds) PackageManager.GET_PROVIDERS else 0)
    val output = mutableListOf<SystemPickerOption>()

    packages.forEach { pkg ->
        val info = runCatching { pm.getPackageInfo(pkg, flags) }.getOrNull() ?: return@forEach
        if (ComponentPickerKind.ACTIVITY in picker.kinds) {
            info.activities.orEmpty().forEach { item ->
                val value = componentPickerValue(picker, item.packageName, item.name)
                output += SystemPickerOption(value, item.name, item.packageName)
            }
        }
        if (
            ComponentPickerKind.SERVICE in picker.kinds ||
            ComponentPickerKind.QUICK_SETTINGS_TILE in picker.kinds
        ) {
            info.services.orEmpty().forEach { item ->
                if (
                    ComponentPickerKind.QUICK_SETTINGS_TILE in picker.kinds &&
                    ComponentPickerKind.SERVICE !in picker.kinds &&
                    item.permission != "android.permission.BIND_QUICK_SETTINGS_TILE"
                ) return@forEach
                val value = componentPickerValue(picker, item.packageName, item.name)
                output += SystemPickerOption(value, item.name, item.packageName)
            }
        }
        if (ComponentPickerKind.RECEIVER in picker.kinds) {
            info.receivers.orEmpty().forEach { item ->
                val value = componentPickerValue(picker, item.packageName, item.name)
                output += SystemPickerOption(value, item.name, item.packageName)
            }
        }
        if (ComponentPickerKind.PROVIDER in picker.kinds) {
            info.providers.orEmpty().forEach { item ->
                val value = componentPickerValue(picker, item.packageName, item.name)
                output += SystemPickerOption(value, item.name, item.packageName)
            }
        }
    }

    return output
        .distinctBy { it.value }
        .sortedWith { left, right ->
            val label = comparator.compare(left.label, right.label)
            if (label != 0) label else comparator.compare(left.value, right.value)
        }
}

private fun componentPickerValue(
    picker: FieldPickerSource.Component,
    packageName: String,
    className: String,
): String = when (picker.valueMode) {
    ComponentPickerValueMode.FLATTENED -> ComponentName(packageName, className).flattenToString()
    ComponentPickerValueMode.CLASS_NAME -> className
}

@Suppress("DEPRECATION")
internal fun installedApps(context: Context, locale: Locale): List<InstalledApp> = runCatching {
    val pm = context.packageManager
    val comparator = localizedStringComparator(locale)
    pm.getInstalledApplications(PackageManager.GET_META_DATA).map { info ->
        InstalledApp(
            label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
            packageName = info.packageName,
            system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        )
    }.sortedWith { left, right ->
        when {
            left.system != right.system -> left.system.compareTo(right.system)
            else -> comparator.compare(left.label, right.label)
        }
    }
}.getOrDefault(emptyList())
