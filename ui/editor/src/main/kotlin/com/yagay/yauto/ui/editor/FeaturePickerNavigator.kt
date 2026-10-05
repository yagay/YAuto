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
    data class Configure(val descriptor: FeatureDescriptor) : PickerPage
}

internal data class FeaturePickerNavState private constructor(
    val stack: List<PickerPage>,
) {
    init {
        require(stack.isNotEmpty())
        require(stack.first() == PickerPage.Categories)
    }

    val current: PickerPage
        get() = stack.last()

    fun push(page: PickerPage): FeaturePickerNavState = copy(stack = stack + page)

    /**
     * Returns null only when the caller is already on the picker root and should dismiss the dialog.
     */
    fun pop(): FeaturePickerNavState? =
        if (stack.size == 1) null else copy(stack = stack.dropLast(1))

    companion object {
        fun initial(initialDescriptor: FeatureDescriptor?): FeaturePickerNavState =
            FeaturePickerNavState(
                buildList {
                    add(PickerPage.Categories)
                    initialDescriptor?.let { add(PickerPage.Configure(it)) }
                }
            )
    }
}
