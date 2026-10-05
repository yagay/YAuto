package com.yagay.yauto.ui.editor

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.ui.design.R as TextR
import java.util.Locale

/**
 * Feature picker shell.
 *
 * Catalog browsing lives in FeaturePickerCatalog.kt and descriptor-driven configuration lives in
 * GenericFeatureConfigEditor.kt. Keeping this file navigation-only prevents the picker from growing
 * every time YAuto gains a new field type or feature domain.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MacroFeaturePickerDialog(
    kind: FeatureKind,
    descriptors: List<FeatureDescriptor>,
    initial: FeatureRef? = null,
    onDismiss: () -> Unit,
    onPick: (FeatureRef) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("yauto_feature_picker", Context.MODE_PRIVATE) }
    val editable = remember(descriptors, kind) {
        descriptors.filter { it.kind == kind && it.category != FeatureCategory.COMPATIBILITY }
    }
    val initialDescriptor = initial?.let { ref -> editable.firstOrNull { it.id.value == ref.typeId } }
    var page by remember(initial?.typeId) {
        mutableStateOf<PickerPage>(
            initialDescriptor?.let { PickerPage.Configure(it, null) } ?: PickerPage.Categories
        )
    }
    var query by remember { mutableStateOf("") }
    var favorites by remember(kind) {
        mutableStateOf(loadIds(prefs.getString(favoriteKey(kind), "")).toSet())
    }
    var recent by remember(kind) {
        mutableStateOf(loadIds(prefs.getString(recentKey(kind), "")))
    }
    val accent = kindAccent(kind)

    fun navigateBack() {
        page = when (val current = page) {
            PickerPage.Categories -> {
                onDismiss()
                PickerPage.Categories
            }
            is PickerPage.Features -> PickerPage.Categories
            is PickerPage.Configure -> current.fromCategory ?: PickerPage.Categories
        }
        query = ""
    }

    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.edit().putString(favoriteKey(kind), favorites.joinToString("\n")).apply()
    }

    fun recordRecent(id: String) {
        recent = (listOf(id) + recent.filterNot { it == id }).take(12)
        prefs.edit().putString(recentKey(kind), recent.joinToString("\n")).apply()
    }

    Dialog(
        onDismissRequest = {
            if (page == PickerPage.Categories) onDismiss() else navigateBack()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
        ),
    ) {
        BackHandler { navigateBack() }

        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = accent,
                        titleContentColor = Color.White,
                    ),
                    title = {
                        Text(
                            when (val current = page) {
                                PickerPage.Categories -> stringResource(TextR.string.editor_select_kind_format, kindLabel(kind))
                                is PickerPage.Features -> categoryTitle(current)
                                is PickerPage.Configure -> localizedFeatureTitle(current.descriptor)
                            }
                        )
                    },
                    navigationIcon = {
                        TextButton(onClick = ::navigateBack) {
                            if (page == PickerPage.Categories) {
                                Text(stringResource(TextR.string.common_close), color = Color.White)
                            } else {
                                androidx.compose.material3.Icon(
                                    painter = androidx.compose.ui.res.painterResource(TextR.drawable.ic_back),
                                    contentDescription = stringResource(TextR.string.icon_back),
                                    tint = Color.White,
                                )
                            }
                        }
                    },
                )
            }
        ) { padding ->
            when (val current = page) {
                PickerPage.Categories -> FeatureCategoryPage(
                    modifier = Modifier.padding(padding),
                    kind = kind,
                    descriptors = editable,
                    query = query,
                    favorites = favorites,
                    recent = recent,
                    onQuery = { query = it },
                    onCategory = {
                        page = it
                        query = ""
                    },
                    onFeature = { page = PickerPage.Configure(it, null) },
                    onFavorite = ::toggleFavorite,
                )
                is PickerPage.Features -> FeatureListPage(
                    modifier = Modifier.padding(padding),
                    kind = kind,
                    categoryPage = current,
                    descriptors = editable,
                    query = query,
                    favorites = favorites,
                    recent = recent,
                    onQuery = { query = it },
                    onFeature = { page = PickerPage.Configure(it, current) },
                    onFavorite = ::toggleFavorite,
                )
                is PickerPage.Configure -> GenericFeatureConfigEditor(
                    modifier = Modifier.padding(padding),
                    descriptor = current.descriptor,
                    initial = initial?.takeIf { it.typeId == current.descriptor.id.value },
                    accent = accent,
                ) { feature ->
                    recordRecent(feature.typeId)
                    onPick(feature)
                }
            }
        }
    }
}

private fun loadIds(raw: String?): List<String> = raw.orEmpty().lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()

private fun favoriteKey(kind: FeatureKind) = "favorites_${kind.name.lowercase(Locale.ROOT)}"
private fun recentKey(kind: FeatureKind) = "recent_${kind.name.lowercase(Locale.ROOT)}"
