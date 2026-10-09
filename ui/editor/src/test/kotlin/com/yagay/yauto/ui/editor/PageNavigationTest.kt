package com.yagay.yauto.ui.editor

import com.yagay.yauto.ui.design.PageNavigation
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
