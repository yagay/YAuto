package com.yagay.yauto.core.registry

/** The category order on MacroDroid's three pickers is not alphabetical and differs by kind. */
private val triggerPickerOrder = listOf(
    FeaturePickerCategory.APPLICATIONS,
    FeaturePickerCategory.BATTERY_POWER,
    FeaturePickerCategory.CALL_SMS,
    FeaturePickerCategory.CONNECTIVITY,
    FeaturePickerCategory.DATE_TIME,
    FeaturePickerCategory.DEVICE_EVENTS,
    FeaturePickerCategory.LOCATION,
    FeaturePickerCategory.YAUTO_SPECIFIC,
    FeaturePickerCategory.SENSORS,
    FeaturePickerCategory.USER_INPUT,
)

private val actionPickerOrder = listOf(
    FeaturePickerCategory.APPLICATIONS,
    FeaturePickerCategory.CAMERA_PHOTO,
    FeaturePickerCategory.CONNECTIVITY,
    FeaturePickerCategory.CONDITIONS_LOOPS,
    FeaturePickerCategory.DATE_TIME,
    FeaturePickerCategory.DEVICE_ACTIONS,
    FeaturePickerCategory.DEVICE_SETTINGS,
    FeaturePickerCategory.FILES,
    FeaturePickerCategory.LOCATION,
    FeaturePickerCategory.LOGGING,
    FeaturePickerCategory.YAUTO_SPECIFIC,
    FeaturePickerCategory.MACROS,
    FeaturePickerCategory.MEDIA,
    FeaturePickerCategory.MESSAGING,
    FeaturePickerCategory.NOTIFICATIONS,
    FeaturePickerCategory.PHONE,
    FeaturePickerCategory.SCREEN,
    FeaturePickerCategory.VARIABLES,
    FeaturePickerCategory.VOLUME,
    FeaturePickerCategory.WEB_INTERACTIONS,
    FeaturePickerCategory.AI,
)

private val constraintPickerOrder = listOf(
    FeaturePickerCategory.BATTERY_POWER,
    FeaturePickerCategory.CONNECTIVITY,
    FeaturePickerCategory.DATE_TIME,
    FeaturePickerCategory.DEVICE_STATE,
    FeaturePickerCategory.LOCATION,
    FeaturePickerCategory.YAUTO_SPECIFIC,
    FeaturePickerCategory.MEDIA,
    FeaturePickerCategory.NOTIFICATIONS,
    FeaturePickerCategory.PHONE,
    FeaturePickerCategory.SCREEN,
    FeaturePickerCategory.SENSORS,
)

/** Stable UI order. Unknown/invalid combinations are placed after the supported categories. */
fun macroDroidCategoryOrder(kind: FeatureKind, category: FeaturePickerCategory): Int {
    val categories = when (kind) {
        FeatureKind.EVENT -> triggerPickerOrder
        FeatureKind.ACTION -> actionPickerOrder
        FeatureKind.STATE, FeatureKind.CONDITION -> constraintPickerOrder
    }
    val index = categories.indexOf(category)
    return if (index < 0) 10_000 else (index + 1) * 10
}

/**
 * MacroDroid maintains distinct category menus for triggers, actions and constraints.
 * YAuto-specific capabilities use YAUTO_SPECIFIC without creating misleading empty tabs.
 * STATE follows the constraint taxonomy because both inspect the current device state.
 */
fun macroDroidCategoriesForKind(kind: FeatureKind): Set<FeaturePickerCategory> = when (kind) {
    FeatureKind.EVENT -> triggerPickerOrder.toSet()
    FeatureKind.ACTION -> actionPickerOrder.toSet()
    FeatureKind.STATE, FeatureKind.CONDITION -> constraintPickerOrder.toSet()
}

/** Constrain legacy and explicit categories to the matching MacroDroid-style picker menu. */
fun normalizeMacroDroidPickerCategory(
    kind: FeatureKind,
    category: FeaturePickerCategory,
): FeaturePickerCategory {
    if (category in macroDroidCategoriesForKind(kind)) return category
    return when (kind) {
        FeatureKind.EVENT -> when (category) {
            FeaturePickerCategory.PHONE, FeaturePickerCategory.MESSAGING -> FeaturePickerCategory.CALL_SMS
            FeaturePickerCategory.AI, FeaturePickerCategory.VARIABLES,
            FeaturePickerCategory.MACROS, FeaturePickerCategory.CONDITIONS_LOOPS ->
                FeaturePickerCategory.YAUTO_SPECIFIC
            else -> FeaturePickerCategory.DEVICE_EVENTS
        }
        FeatureKind.ACTION -> when (category) {
            FeaturePickerCategory.BATTERY_POWER, FeaturePickerCategory.DEVICE_STATE ->
                FeaturePickerCategory.DEVICE_SETTINGS
            FeaturePickerCategory.CALL_SMS -> FeaturePickerCategory.MESSAGING
            else -> FeaturePickerCategory.DEVICE_ACTIONS
        }
        FeatureKind.STATE, FeatureKind.CONDITION -> when (category) {
            // Volume/ringer constraints are audio-related, not display-related.
            // The MacroDroid constraint category is Screen/Speaker for
            // ringer mode and audio stream volume, not the Media playback category.
            FeaturePickerCategory.VOLUME -> FeaturePickerCategory.SCREEN
            FeaturePickerCategory.CALL_SMS, FeaturePickerCategory.MESSAGING -> FeaturePickerCategory.PHONE
            FeaturePickerCategory.WEB_INTERACTIONS -> FeaturePickerCategory.CONNECTIVITY
            FeaturePickerCategory.AI, FeaturePickerCategory.VARIABLES,
            FeaturePickerCategory.MACROS, FeaturePickerCategory.CONDITIONS_LOOPS ->
                FeaturePickerCategory.YAUTO_SPECIFIC
            else -> FeaturePickerCategory.DEVICE_STATE
        }
    }
}

internal fun fallbackPickerCategory(
    kind: FeatureKind,
    legacyCategory: FeatureCategory,
): FeaturePickerCategory = when (legacyCategory) {
    FeatureCategory.APP -> FeaturePickerCategory.APPLICATIONS
    FeatureCategory.NETWORK -> FeaturePickerCategory.CONNECTIVITY
    FeatureCategory.DISPLAY -> FeaturePickerCategory.SCREEN
    FeatureCategory.AUDIO -> FeaturePickerCategory.MEDIA
    FeatureCategory.NOTIFICATION -> FeaturePickerCategory.NOTIFICATIONS
    FeatureCategory.FILE -> FeaturePickerCategory.FILES
    FeatureCategory.VARIABLE -> if (kind == FeatureKind.ACTION) FeaturePickerCategory.VARIABLES
        else FeaturePickerCategory.YAUTO_SPECIFIC
    FeatureCategory.FLOW ->
        if (kind == FeatureKind.ACTION) FeaturePickerCategory.CONDITIONS_LOOPS
        else FeaturePickerCategory.YAUTO_SPECIFIC
    FeatureCategory.UI_AUTOMATION -> when (kind) {
        FeatureKind.ACTION -> FeaturePickerCategory.DEVICE_ACTIONS
        FeatureKind.EVENT -> FeaturePickerCategory.USER_INPUT
        FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.DEVICE_STATE
    }
    FeatureCategory.SCRIPT -> FeaturePickerCategory.YAUTO_SPECIFIC
    FeatureCategory.CORE, FeatureCategory.COMPATIBILITY -> FeaturePickerCategory.YAUTO_SPECIFIC
    FeatureCategory.DEVICE,
    FeatureCategory.SYSTEM,
    FeatureCategory.ADVANCED -> when (kind) {
        FeatureKind.ACTION -> FeaturePickerCategory.DEVICE_ACTIONS
        FeatureKind.EVENT -> FeaturePickerCategory.DEVICE_EVENTS
        FeatureKind.STATE, FeatureKind.CONDITION -> FeaturePickerCategory.DEVICE_STATE
    }
}
