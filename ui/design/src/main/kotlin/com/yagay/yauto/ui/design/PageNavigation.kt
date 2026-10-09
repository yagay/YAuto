package com.yagay.yauto.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * One navigation contract for the app shell and nested screens.
 *
 * Back pops only the immediately preceding destination. It never replaces a
 * nested destination with HOME, and the root has no internal back destination.
 * Screens own their own trail while modal overlays own a higher-priority back handler.
 */
data class PageNavigation<T> private constructor(val entries: List<T>) {
    init { require(entries.isNotEmpty()) }

    val current: T get() = entries.last()
    val canGoBack: Boolean get() = entries.size > 1

    fun forward(destination: T): PageNavigation<T> =
        if (destination == current) this else PageNavigation(entries + destination)

    fun back(): PageNavigation<T>? =
        if (canGoBack) PageNavigation(entries.dropLast(1)) else null

    companion object {
        fun <T> root(page: T): PageNavigation<T> = PageNavigation(listOf(page))

        fun <T> restore(root: T, saved: List<T>): PageNavigation<T> =
            if (saved.firstOrNull() != root) root(root) else PageNavigation(saved)
    }
}

/** Save enum destinations across Activity recreation, e.g. when switching app language. */
@Composable
fun <T : Enum<T>> rememberPageNavigation(root: T): MutableState<PageNavigation<T>> {
    val enumConstants = root.javaClass.enumConstants?.toList().orEmpty()
    val saver = Saver<PageNavigation<T>, ArrayList<String>>(
        save = { trail -> ArrayList(trail.entries.map { it.name }) },
        restore = { encoded ->
            val restored = encoded.mapNotNull { name -> enumConstants.firstOrNull { it.name == name } }
            PageNavigation.restore(root, restored)
        },
    )
    return rememberSaveable(root.name, stateSaver = saver) {
        mutableStateOf(PageNavigation.root(root))
    }
}
