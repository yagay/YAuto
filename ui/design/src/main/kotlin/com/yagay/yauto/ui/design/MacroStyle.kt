package com.yagay.yauto.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object MacroPalette {
    val Trigger = Color(0xFFE53935)
    val Action = Color(0xFF168BC2)
    val Constraint = Color(0xFF43A047)
    val State = Color(0xFFEF6C00)
    val Utility = Color(0xFF546E7A)
    val Flow = Color(0xFF6A4FB3)
    val Variable = Color(0xFF00897B)
    val Diagnostics = Color(0xFF5D6D7E)
}

@Composable
fun MacroSection(
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    count: Int? = null,
    onAdd: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().background(color).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(title)
                        count?.let { append("  ($it)") }
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelSmall)
                }
            }
            trailing()
            if (onAdd != null) {
                TextButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("＋", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}

@Composable
fun MacroItemRow(
    title: String,
    subtitle: String? = null,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onMenu: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onMenu != null) {
                TextButton(onClick = onMenu, modifier = Modifier.align(Alignment.CenterVertically)) { Text("⋮") }
            }
        }
    }
}

@Composable
fun MacroHomeTile(
    title: String,
    subtitle: String? = null,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.aspectRatio(1.08f).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("＋", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.headlineMedium)
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color.White.copy(alpha = .82f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun CapabilityBadge(text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        enabled = false,
    )
}
