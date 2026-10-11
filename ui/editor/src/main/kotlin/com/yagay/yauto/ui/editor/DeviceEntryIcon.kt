package com.yagay.yauto.ui.editor

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared leading icon across YAuto app, component, notification and QS pickers. */
enum class DevicePickerIconKind {
    APP, TILE, NOTIFICATION, KEY, NETWORK, CAMERA, TIME, LOCATION, GENERIC,
}

/**
 * Only visible LazyColumn rows request icons. All PackageManager/drawable
 * decoding happens off the main thread; repeated items reuse an LRU bitmap.
 * Missing/hidden packages use a category glyph, never a fabricated app icon.
 */
@Composable
fun DeviceEntryIcon(
    packageName: String? = null,
    componentName: String? = null,
    kind: DevicePickerIconKind = DevicePickerIconKind.GENERIC,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val pkg = packageName.orEmpty().trim()
    val component = componentName.orEmpty().trim()
    val cacheKey = if (pkg.isNotBlank() || component.isNotBlank()) "$pkg|$component" else ""
    val icon by produceState<ImageBitmap?>(
        initialValue = DeviceIconCache.cached(cacheKey),
        key1 = context,
        key2 = cacheKey,
    ) {
        value = if (cacheKey.isBlank()) null else withContext(Dispatchers.IO) {
            DeviceIconCache.load(context, pkg, component, cacheKey)
        }
    }
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Image(
                bitmap = icon!!,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        } else {
            val fallback = when (kind) {
                DevicePickerIconKind.APP -> android.R.drawable.sym_def_app_icon
                DevicePickerIconKind.TILE -> android.R.drawable.ic_menu_manage
                DevicePickerIconKind.NOTIFICATION -> android.R.drawable.ic_dialog_email
                DevicePickerIconKind.KEY -> android.R.drawable.ic_menu_view
                DevicePickerIconKind.NETWORK -> android.R.drawable.ic_menu_compass
                DevicePickerIconKind.CAMERA -> android.R.drawable.ic_menu_camera
                DevicePickerIconKind.TIME -> android.R.drawable.ic_menu_recent_history
                DevicePickerIconKind.LOCATION -> android.R.drawable.ic_menu_mylocation
                DevicePickerIconKind.GENERIC -> android.R.drawable.ic_menu_info_details
            }
            Icon(
                painter = painterResource(fallback),
                contentDescription = null,
                modifier = Modifier.size(25.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private object DeviceIconCache {
    private val cache = LruCache<String, ImageBitmap>(160)

    fun cached(key: String): ImageBitmap? =
        key.takeIf(String::isNotBlank)?.let(cache::get)

    fun load(context: Context, pkg: String, flattened: String, key: String): ImageBitmap? {
        cached(key)?.let { return it }
        val pm = context.packageManager
        val component = ComponentName.unflattenFromString(flattened)
        val appPackage = component?.packageName ?: pkg
        val drawable = runCatching {
            component?.let {
                // Tiles are services. For other component selectors try their
                // own icon before falling back to the containing app icon.
                runCatching { pm.getServiceInfo(it, PackageManager.GET_META_DATA).loadIcon(pm) }
                    .getOrNull()
                    ?: runCatching { pm.getActivityInfo(it, 0).loadIcon(pm) }.getOrNull()
                    ?: runCatching { pm.getReceiverInfo(it, 0).loadIcon(pm) }.getOrNull()
                    ?: runCatching { pm.getProviderInfo(it, 0).loadIcon(pm) }.getOrNull()
            } ?: if (appPackage.isNotBlank()) pm.getApplicationIcon(appPackage) else null
        }.getOrNull() ?: runCatching {
            if (appPackage.isNotBlank()) pm.getApplicationIcon(appPackage) else null
        }.getOrNull()
        val image = runCatching {
            drawable?.toBitmap(width = 96, height = 96)?.asImageBitmap()
        }.getOrNull()
        if (image != null) cache.put(key, image)
        return image
    }
}
