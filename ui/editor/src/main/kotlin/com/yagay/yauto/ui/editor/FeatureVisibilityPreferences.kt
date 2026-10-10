package com.yagay.yauto.ui.editor

import android.content.Context
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.isRootExclusiveFeature
import com.yagay.yauto.core.model.FeatureRef

/**
 * Shared picker preferences. The default is visible to preserve existing
 * users' catalog after an upgrade; this setting is not a runtime permission.
 */
object FeatureVisibilityPreferences {
    private const val PREFERENCES_NAME = "yauto_feature_picker"
    private const val KEY_SHOW_ROOT_EXCLUSIVE = "show_root_lsposed_exclusive"

    fun showRootExclusive(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_ROOT_EXCLUSIVE, true)

    fun setShowRootExclusive(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_ROOT_EXCLUSIVE, show).apply()
    }
}

/** Editing a saved Root-only step remains possible even when new insertion is hidden. */
internal fun filterPickerFeatures(
    descriptors: List<FeatureDescriptor>,
    kind: FeatureKind,
    showRootExclusive: Boolean,
    initial: FeatureRef? = null,
): List<FeatureDescriptor> = descriptors.filter { descriptor ->
    descriptor.kind == kind &&
        descriptor.isPickerSelectable() &&
        (showRootExclusive || !descriptor.isRootExclusiveFeature() || descriptor.id.value == initial?.typeId)
}
