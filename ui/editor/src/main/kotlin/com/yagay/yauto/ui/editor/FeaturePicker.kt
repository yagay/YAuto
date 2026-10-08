package com.yagay.yauto.ui.editor

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
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
 * Navigation and catalog preparation are intentionally separate from rendering:
 * - [FeaturePickerNavState] owns the internal back stack.
 * - [FeaturePickerCatalogModel] is built once per descriptor/locale set.
 * - catalog screens only render lightweight immutable UI items.
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
    val textResolver = rememberFeatureTextResolver()
    val locale = currentEditorLocale()
    val titleComparator = remember(locale) { localizedStringComparator(locale) }
    val catalog = remember(editable, textResolver, titleComparator) {
        buildFeaturePickerCatalog(editable, textResolver, titleComparator)
    }
    val initialDescriptor = initial?.let { ref -> catalog.item(ref.typeId)?.descriptor }
    val initialGroup = initialDescriptor?.let { catalog.unifiedGroupForMember(it.id.value) }
    var navigation by remember(initial?.typeId, catalog) {
        mutableStateOf(
            if (initialGroup != null && initialDescriptor != null) {
                FeaturePickerNavState.initialUnified(initialGroup, initialDescriptor.id.value)
            } else {
                FeaturePickerNavState.initial(initialDescriptor)
            }
        )
    }
    val pageQueries = remember(kind) { mutableStateMapOf<String, String>() }
    val pageListStates = remember(kind) { mutableMapOf<String, LazyListState>() }
    var favorites by remember(kind) {
        mutableStateOf(loadIds(prefs.getString(favoriteKey(kind), "")).toSet())
    }
    var recent by remember(kind) {
        mutableStateOf(loadIds(prefs.getString(recentKey(kind), "")))
    }
    val accent = kindAccent(kind)
    val page = navigation.current
    val pageKey = pickerPageStateKey(page)
    val query = pageQueries[pageKey].orEmpty()
    val listState = pageListStates.getOrPut(pageKey) { LazyListState() }

    fun updateQuery(value: String) {
        pageQueries[pageKey] = value
    }

    fun push(destination: PickerPage) {
        navigation = navigation.push(destination)
    }

    fun openFeature(descriptor: FeatureDescriptor) {
        val group = catalog.unifiedGroupForMember(descriptor.id.value)
        push(
            if (group != null) {
                PickerPage.Unified(group, descriptor.id.value)
            } else {
                PickerPage.Configure(descriptor)
            }
        )
    }

    fun navigateBack() {
        val previous = navigation.pop()
        if (previous == null) {
            onDismiss()
        } else {
            navigation = previous
        }
    }

    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.edit().putString(favoriteKey(kind), favorites.joinToString("\n")).apply()
    }

    fun toggleUnifiedFavorite(group: UnifiedFeatureGroup) {
        val ids = group.members.map { it.descriptor.id.value }.toSet()
        favorites = if (ids.any { it in favorites }) favorites - ids else favorites + ids
        prefs.edit().putString(favoriteKey(kind), favorites.joinToString("\n")).apply()
    }

    fun recordRecent(id: String) {
        recent = (listOf(id) + recent.filterNot { it == id }).take(12)
        prefs.edit().putString(recentKey(kind), recent.joinToString("\n")).apply()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
        ),
    ) {
        BackHandler(onBack = ::navigateBack)

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
                                PickerPage.Categories ->
                                    stringResource(TextR.string.editor_select_kind_format, kindLabel(kind))
                                is PickerPage.Features -> categoryTitle(current)
                                is PickerPage.Unified -> stringResource(current.group.spec.titleRes)
                                is PickerPage.Configure ->
                                    catalog.item(current.descriptor.id.value)?.title ?: current.descriptor.title
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
                    catalog = catalog,
                    query = query,
                    listState = listState,
                    favorites = favorites,
                    recent = recent,
                    onQuery = ::updateQuery,
                    onCategory = { push(it) },
                    onFeature = ::openFeature,
                    onUnified = { push(PickerPage.Unified(it)) },
                    onFavorite = ::toggleFavorite,
                    onUnifiedFavorite = ::toggleUnifiedFavorite,
                    onUnifiedFavorite = ::toggleUnifiedFavorite,
                )
                is PickerPage.Features -> FeatureListPage(
                    modifier = Modifier.padding(padding),
                    kind = kind,
                    categoryPage = current,
                    catalog = catalog,
                    query = query,
                    listState = listState,
                    favorites = favorites,
                    recent = recent,
                    onQuery = ::updateQuery,
                    onFeature = ::openFeature,
                    onUnified = { push(PickerPage.Unified(it)) },
                    onFavorite = ::toggleFavorite,
                )
                is PickerPage.Unified -> UnifiedFeatureConfigEditor(
                    modifier = Modifier.padding(padding),
                    group = current.group,
                    initial = initial,
                    initialMemberId = current.selectedMemberId,
                    accent = accent,
                ) { feature ->
                    recordRecent(feature.typeId)
                    onPick(feature)
                }
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


private fun pickerPageStateKey(page: PickerPage): String = when (page) {
    PickerPage.Categories -> "categories"
    is PickerPage.Features -> buildString {
        append("features:")
        append(page.category.id)
        page.special?.let {
            append(':')
            append(it)
        }
    }
    is PickerPage.Unified -> "unified:" + page.group.spec.id
    is PickerPage.Configure -> "configure:" + page.descriptor.id.value
}
private fun loadIds(raw: String?): List<String> = raw.orEmpty().lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()

private fun favoriteKey(kind: FeatureKind) = "favorites_${kind.name.lowercase(Locale.ROOT)}"
private fun recentKey(kind: FeatureKind) = "recent_${kind.name.lowercase(Locale.ROOT)}"
