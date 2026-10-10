package com.yagay.yauto.ui.editor

import com.yagay.yauto.ui.design.PageNavigation
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeatureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageNavigationTest {
    private enum class Page { HOME, SETTINGS, PERMISSIONS, ENGINE, EDITOR, DETAILS }

    @Test fun `back goes to immediate parent not directly to home`() {
        val route = PageNavigation.root(Page.HOME)
            .forward(Page.SETTINGS)
            .forward(Page.PERMISSIONS)
        assertEquals(Page.PERMISSIONS, route.current)
        val settings = route.back()!!
        assertEquals(Page.SETTINGS, settings.current)
        assertTrue(settings.canGoBack)
        val home = settings.back()!!
        assertEquals(Page.HOME, home.current)
        assertFalse(home.canGoBack)
        assertNull(home.back())
    }

    @Test fun `editing existing feature returns to containing category`() {
        val category = CatalogCategory("app", 0, 0, 10)
        val descriptor = FeatureDescriptor(FeatureId("android.app.launch"),
            FeatureKind.ACTION, "Launch app", "", FeatureCategory.APP)
        val navigation = FeaturePickerNavState.initial(descriptor, category)
        assertEquals(3, navigation.stack.size)
        assertEquals(descriptor, (navigation.current as PickerPage.Configure).descriptor)
        val list = navigation.pop()!!
        assertEquals(category, (list.current as PickerPage.Features).category)
        assertEquals(PickerPage.Categories, list.pop()!!.current)
    }

    @Test fun `separate navigation trails do not accidentally dismiss ancestor`() {
        val app = PageNavigation.root(Page.HOME).forward(Page.SETTINGS)
        val settings = PageNavigation.root(Page.SETTINGS)
            .forward(Page.ENGINE)
            .forward(Page.DETAILS)
        assertEquals(Page.ENGINE, settings.back()!!.current)
        assertEquals(Page.SETTINGS, app.current)
    }

    @Test fun `restored route preserves history and invalid root is rejected`() {
        val route = PageNavigation.root(Page.HOME).forward(Page.SETTINGS).forward(Page.ENGINE)
        assertEquals(route, PageNavigation.restore(Page.HOME, route.entries))
        assertEquals(Page.HOME, PageNavigation.restore(Page.HOME, listOf(Page.SETTINGS, Page.ENGINE)).current)
    }

    @Test fun `navigation to the current destination avoids duplicate pop steps`() {
        val route = PageNavigation.root(Page.HOME)
            .forward(Page.SETTINGS)
            .forward(Page.SETTINGS)
        assertEquals(listOf(Page.HOME, Page.SETTINGS), route.entries)
        assertEquals(Page.HOME, route.back()!!.current)
    }

    @Test fun `independent editor back stack keeps nested flow`() {
        val route = PageNavigation.root(Page.HOME)
            .forward(Page.EDITOR)
            .forward(Page.DETAILS)
        assertEquals(Page.EDITOR, route.back()!!.current)
    }
}
