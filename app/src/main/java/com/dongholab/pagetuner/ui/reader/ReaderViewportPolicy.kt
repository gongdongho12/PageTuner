package com.dongholab.pagetuner.ui.reader

import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.translation.PageTranslation

private const val FullscreenMaximumMarginDp = 8

/**
 * Reader chrome and spacing are derived in one place so every input method sees the same viewport.
 * Full screen deliberately keeps a small paper margin while giving the rest of the display to text.
 */
data class ReaderViewportPolicy(
    val showChrome: Boolean,
    val rootPaddingDp: Int,
    val pageMarginDp: Int,
)

fun readerViewportPolicy(
    fullScreen: Boolean,
    configuredPageMarginDp: Int,
): ReaderViewportPolicy = if (fullScreen) {
    ReaderViewportPolicy(
        showChrome = false,
        rootPaddingDp = 0,
        pageMarginDp = configuredPageMarginDp.coerceIn(0, FullscreenMaximumMarginDp),
    )
} else {
    ReaderViewportPolicy(
        showChrome = true,
        rootPaddingDp = 14,
        pageMarginDp = configuredPageMarginDp.coerceAtLeast(0),
    )
}

/** Translation and catalog background work must not stall a local page turn. */
fun isReaderPageTurnBlocked(
    libraryBusy: Boolean,
    translationBusy: Boolean,
    catalogBusy: Boolean,
): Boolean {
    @Suppress("UNUSED_VARIABLE")
    val backgroundOnly = translationBusy || catalogBusy
    return libraryBusy
}

/** Never paint the previous page's translation while the next page cache is being resolved. */
fun PageTranslation?.forReaderPage(page: ReaderPage): PageTranslation? =
    this?.takeIf { it.page.index == page.index }
