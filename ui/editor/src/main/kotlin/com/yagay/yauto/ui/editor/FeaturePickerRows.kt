package com.yagay.yauto.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeaturePickerCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.ui.design.MacroPalette
import com.yagay.yauto.ui.design.localizedList
import com.yagay.yauto.ui.design.R as TextR

@Composable
internal fun UnifiedFeatureRow(
    group: UnifiedFeatureGroup,
    preferredTitle: String?,
    favorite: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val title = stringResource(group.spec.titleRes)
    val detail = stringResource(
        TextR.string.feature_picker_family_count_format,
        stringResource(group.spec.subtitleRes),
        group.members.size,
    )
    FeaturePickerRow(
        title = if (favorite) stringResource(TextR.string.editor_favorite_prefix, title) else title,
        subtitle = if (preferredTitle.isNullOrBlank()) detail else "$preferredTitle · $detail",
        accent = accent,
        onClick = onClick,
        onFavorite = onFavorite,
        height = 68.dp,
        showChevron = true,
    )
}

@Composable
internal fun favoriteTitle(item: FeaturePickerCatalogItem, favorites: Set<String>): String =
    if (item.descriptor.id.value in favorites) {
        stringResource(TextR.string.editor_favorite_prefix, item.title)
    } else {
        item.title
    }

/** Main-style rounded cards, with 1003's statuses, permission tags and stable list keys. */
@Composable
internal fun FeaturePickerRow(
    title: String,
    subtitle: String?,
    accent: Color,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    height: androidx.compose.ui.unit.Dp,
    availability: FeatureAvailabilityUi? = null,
    accessTags: String = "",
    showChevron: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = height)
                .drawBehind {
                    drawRect(accent, size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height))
                }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = localizedList(
                    listOfNotNull(
                        accessTags.takeIf { it.isNotBlank() },
                        availability?.summary?.takeIf { it.isNotBlank() },
                    )
                )
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                availability?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it.statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (it.tone) {
                            FeatureAvailabilityTone.READY -> MacroPalette.Constraint
                            FeatureAvailabilityTone.BLOCKED -> MacroPalette.Trigger
                            FeatureAvailabilityTone.BROKEN -> MaterialTheme.colorScheme.error
                            FeatureAvailabilityTone.UNSUPPORTED -> MacroPalette.Utility
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Icon(
                painter = androidx.compose.ui.res.painterResource(TextR.drawable.ic_more),
                contentDescription = stringResource(TextR.string.icon_more_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp).clickable(onClick = onFavorite).padding(11.dp),
            )
            if (showChevron) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(TextR.drawable.ic_chevron_right),
                    contentDescription = stringResource(TextR.string.icon_open_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun featureAccessTags(descriptor: FeatureDescriptor): String {
    // Keep the picker and configuration editor consistent: list only the
    // actual requirements of the preferred available method. In particular,
    // "No Root required" is not a permission and should never be displayed.
    val requirements = remember(descriptor) {
        permissionsForFeature(descriptor, null) { PermissionAvailability.UNKNOWN }
    }
    if (requirements.isEmpty()) return ""
    val permissions = requirements.map { requirement ->
        stringResource(when (requirement) {
        AccessRequirement.ROOT -> TextR.string.access_root
        AccessRequirement.SHIZUKU -> TextR.string.access_shizuku
        AccessRequirement.LSPOSED -> TextR.string.access_lsposed
        AccessRequirement.ZYGISK -> TextR.string.access_zygisk
        AccessRequirement.ACCESSIBILITY -> TextR.string.access_accessibility
        AccessRequirement.USAGE_STATS -> TextR.string.access_usage_stats
        AccessRequirement.NOTIFICATION_LISTENER -> TextR.string.access_notification_listener
        AccessRequirement.POST_NOTIFICATIONS -> TextR.string.access_post_notifications
        AccessRequirement.OVERLAY -> TextR.string.access_overlay
        AccessRequirement.WRITE_SETTINGS -> TextR.string.access_write_settings
        AccessRequirement.CAMERA -> TextR.string.access_camera
        AccessRequirement.LOCATION -> TextR.string.access_location
        AccessRequirement.BLUETOOTH_CONNECT -> TextR.string.access_bluetooth
        AccessRequirement.DND_POLICY -> TextR.string.access_dnd_policy
        AccessRequirement.DEVICE_ADMIN -> TextR.string.access_device_admin
        AccessRequirement.CALENDAR -> TextR.string.access_calendar
        AccessRequirement.CONTACTS -> TextR.string.access_contacts
        AccessRequirement.CALL_LOG -> TextR.string.access_call_log
        AccessRequirement.SMS -> TextR.string.access_sms
        AccessRequirement.PHONE -> TextR.string.access_phone
        AccessRequirement.RECORD_AUDIO -> TextR.string.access_record_audio
        AccessRequirement.ACTIVITY_RECOGNITION -> TextR.string.access_activity_recognition
        })
    }
    return localizedList(permissions)
}

@Composable
internal fun PickerEmptyState(message: String) {
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Matches the main-branch card rhythm without changing category IDs or navigation. */
@Composable
internal fun CategoryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                painter = androidx.compose.ui.res.painterResource(TextR.drawable.ic_chevron_right),
                contentDescription = stringResource(TextR.string.icon_open_details),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
internal fun categoryTitle(page: PickerPage.Features): String = when (page.special) {
    "recent" -> stringResource(TextR.string.feature_picker_recent)
    "favorites" -> stringResource(TextR.string.feature_picker_favorites)
    else -> stringResource(page.category.titleRes)
}

@Composable
internal fun categorySubtitle(page: PickerPage.Features): String = when (page.special) {
    "recent" -> stringResource(TextR.string.feature_picker_recent)
    "favorites" -> stringResource(TextR.string.feature_picker_favorites)
    else -> stringResource(page.category.subtitleRes)
}

@Composable
internal fun kindLabel(kind: FeatureKind): String = stringResource(
    when (kind) {
        FeatureKind.EVENT -> TextR.string.kind_event
        FeatureKind.STATE -> TextR.string.kind_state
        FeatureKind.ACTION -> TextR.string.kind_action
        FeatureKind.CONDITION -> TextR.string.kind_condition
    }
)

@Composable
internal fun kindHelp(kind: FeatureKind): String = stringResource(
    when (kind) {
        FeatureKind.EVENT -> TextR.string.kind_event_help
        FeatureKind.STATE -> TextR.string.kind_state_help
        FeatureKind.ACTION -> TextR.string.kind_action_help
        FeatureKind.CONDITION -> TextR.string.kind_condition_help
    }
)

internal fun kindAccent(kind: FeatureKind) = when (kind) {
    FeatureKind.EVENT -> MacroPalette.Trigger
    FeatureKind.STATE -> MacroPalette.State
    FeatureKind.ACTION -> MacroPalette.Action
    FeatureKind.CONDITION -> MacroPalette.Constraint
}
