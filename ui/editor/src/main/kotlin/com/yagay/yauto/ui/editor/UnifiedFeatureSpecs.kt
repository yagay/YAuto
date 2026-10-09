package com.yagay.yauto.ui.editor

import androidx.annotation.StringRes
import com.yagay.yauto.ui.design.R as TextR

/**
 * An approved one-entry / multiple-implementation picker operation.
 *
 * Do not merge actions merely because they share a category, noun, or similar
 * wording. Each pair is verified against ONE MacroDroid resource key, falling
 * back to ONE ShortX key when MacroDroid has no matching action.
 *
 * The stable feature ID is always preserved when the user saves a choice.
 * Evidence: tools/verified_picker_merges.csv; enforced by the CI audit.
 */
internal data class UnifiedFeatureSpec(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val memberIds: List<String>,
)

internal val UNIFIED_FEATURE_SPECS = listOf(
    // MacroDroid action_clipboard: the same clipboard-writing operation.
    UnifiedFeatureSpec(
        "clipboard_write",
        TextR.string.verified_family_clipboard_write,
        TextR.string.verified_family_clipboard_write_subtitle,
        listOf("android.clipboard.set", "android.clipboard.write"),
    ),
    // ShortX ui.action.read.clipboard: MacroDroid has no validated match for both IDs.
    UnifiedFeatureSpec(
        "clipboard_read",
        TextR.string.verified_family_clipboard_read,
        TextR.string.verified_family_clipboard_read_subtitle,
        listOf("android.clipboard.get", "android.clipboard.read"),
    ),
    // MacroDroid action_take_screenshot: alternative implementations of one action.
    UnifiedFeatureSpec(
        "screenshot_capture",
        TextR.string.verified_family_screenshot_capture,
        TextR.string.verified_family_screenshot_capture_subtitle,
        listOf("android.screen.screenshot", "android.screenshot.capture"),
    ),
)
