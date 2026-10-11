package com.yagay.yauto

import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.yauto.ui.design.R as TextR
import kotlinx.coroutines.launch

/** Read-only QS inventory; tile clicks and panel edits are never simulated here. */
@Composable
internal fun DeviceQuickSettingsScreen(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inventory by remember { mutableStateOf<DeviceTileInventory?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            val data = runCatching { graph.deviceQuickSettingsTiles.load() }
            inventory = data.getOrNull()
            failed = data.isFailure
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(stringResource(TextR.string.live_picker_tile_inventory_hint),
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = ::refresh, enabled = !loading) {
                Text(stringResource(TextR.string.sx_records_refresh))
            }
        }
        if (loading) item { CircularProgressIndicator() }
        if (failed) item { Text(stringResource(TextR.string.live_picker_unavailable)) }
        inventory?.let { snapshot ->
            item {
                Text(
                    when (snapshot.source) {
                        "lsposed_systemui" -> stringResource(TextR.string.live_picker_tile_source_hook)
                        "root_saved_config" -> stringResource(TextR.string.live_picker_tile_source_root)
                        "root_service_inventory" -> stringResource(TextR.string.live_picker_tile_source_root_services)
                        else -> stringResource(TextR.string.live_picker_tile_source_installed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(snapshot.entries, key = { it.spec }) { tile ->
                Card(Modifier.fillMaxWidth().clickable {
                    val text = tile.component ?: tile.spec
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("QS tile", text))
                }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tile.label, fontWeight = FontWeight.SemiBold)
                        Text(tile.spec, style = MaterialTheme.typography.labelSmall)
                        Text(
                            when (tile.isInPanel) {
                                true -> stringResource(TextR.string.live_picker_tile_in_panel)
                                false -> stringResource(TextR.string.live_picker_tile_not_in_panel)
                                null -> stringResource(TextR.string.live_picker_tile_unknown_panel)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
