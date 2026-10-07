package com.yagay.yauto.ui.editor

import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeaturePickerNavigatorTest {
    private val category = CatalogCategory("app", 1, 2, 20)
    private val descriptor = FeatureDescriptor(
        id = FeatureId("android.app.launch"),
        kind = FeatureKind.ACTION,
        title = "Launch App",
        description = "Launch an app",
        category = FeatureCategory.APP,
    )

    @Test
    fun `back walks configure to list to categories before dismiss`() {
        var state = FeaturePickerNavState.initial(null)
        state = state.push(PickerPage.Features(category))
        state = state.push(PickerPage.Configure(descriptor))

        assertEquals(PickerPage.Configure(descriptor), state.current)

        state = state.pop()!!
        assertEquals(PickerPage.Features(category), state.current)

        state = state.pop()!!
        assertEquals(PickerPage.Categories, state.current)

        assertNull(state.pop())
    }

    @Test
    fun `back walks configure through family and category`() {
        val family = FeaturePickerFamily(
            spec = FeatureFamilySpec(
                id = "volume",
                titleRes = 1,
                subtitleRes = 2,
                memberIds = listOf(descriptor.id.value),
            ),
            members = emptyList(),
        )
        var state = FeaturePickerNavState.initial(null)
        state = state.push(PickerPage.Features(category))
        state = state.push(PickerPage.Family(family))
        state = state.push(PickerPage.Configure(descriptor))

        state = state.pop()!!
        assertEquals(PickerPage.Family(family), state.current)

        state = state.pop()!!
        assertEquals(PickerPage.Features(category), state.current)

        state = state.pop()!!
        assertEquals(PickerPage.Categories, state.current)
    }

    @Test
    fun `editing existing feature starts above categories root`() {
        val state = FeaturePickerNavState.initial(descriptor)

        assertEquals(PickerPage.Configure(descriptor), state.current)
        assertEquals(PickerPage.Categories, state.pop()!!.current)
    }
}
