package com.yagay.yauto.ui.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun YAutoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Observe the same revision as settings: no activity recreation needed.
    YAutoAppearanceSettings.revision
    val dark = when (YAutoAppearanceSettings.mode(context)) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (Build.VERSION.SDK_INT >= 31 && YAutoAppearanceSettings.dynamic(context)) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun YSectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            content()
        }
    }
}
