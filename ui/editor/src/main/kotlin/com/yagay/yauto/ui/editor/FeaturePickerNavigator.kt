package com.yagay.yauto.ui.editor

import com.yagay.yauto.core.registry.FeatureDescriptor

/**
 * Internal navigation for the feature picker.
 *
 * The picker owns its own stack. Activity/dialog dismissal only happens after the stack reaches
 * [PickerPage.Categories], so system back and the toolbar back button always share the same path.
 */
internal sealed interface PickerPage {
    data object Categories : PickerPage
    data class Features(val category: CatalogCategory, val special: String? = null) : PickerPage
    data class Unified(
        val group: UnifiedFeatureGroup,
        val selectedMemberId: String? = null,
    ) : PickerPage
    data class Configure(val descriptor: FeatureDescriptor) : PickerPage
}

/**
 * Feature picker destinations share the same back-stack primitive as the app,
 * settings and diagnostics. The wrapper keeps the existing editor API stable.
 */
internal data class FeaturePickerNavState private constructor(
    private val navigation: com.yagay.yauto.ui.design.PageNavigation<PickerPage>,
) {
    val stack: List<PickerPage> get() = navigation.entries
    val current: PickerPage get() = navigation.current

    fun push(page: PickerPage): FeaturePickerNavState =
        FeaturePickerNavState(navigation.forward(page))

    fun pop(): FeaturePickerNavState? = navigation.back()?.let(::FeaturePickerNavState)

    companion object {
        fun initial(initialDescriptor: FeatureDescriptor?): FeaturePickerNavState {
            var trail = com.yagay.yauto.ui.design.PageNavigation.root<PickerPage>(PickerPage.Categories)
            initialDescriptor?.let { trail = trail.forward(PickerPage.Configure(it)) }
            return FeaturePickerNavState(trail)
        }

        fun initialUnified(
            group: UnifiedFeatureGroup,
            selectedMemberId: String? = null,
        ): FeaturePickerNavState = FeaturePickerNavState(
            com.yagay.yauto.ui.design.PageNavigation.root<PickerPage>(PickerPage.Categories)
                .forward(PickerPage.Unified(group, selectedMemberId))
        )
    }
}
