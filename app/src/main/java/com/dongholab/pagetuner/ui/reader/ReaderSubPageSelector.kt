package com.dongholab.pagetuner.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class ReaderSubPage(val titleRes: Int) {
    READER(com.dongholab.pagetuner.R.string.reader_tab_reader),
    SEARCH(com.dongholab.pagetuner.R.string.reader_tab_search),
    BOOKMARKS(com.dongholab.pagetuner.R.string.reader_tab_bookmarks),
    ANNOTATIONS(com.dongholab.pagetuner.R.string.reader_tab_notes),
    GLOSSARY(com.dongholab.pagetuner.R.string.reader_tab_glossary),
}

/**
 * Compact E-Ink Sub-Page Selector Bar with Active Tab Indicator.
 * Switches between Full Viewport Reader, Search, Bookmarks, and Notes pages.
 */
@Composable
fun ReaderSubPageSelector(
    selectedPage: ReaderSubPage,
    busy: Boolean,
    onSelectPage: (ReaderSubPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = ReaderSubPage.entries.associateWith {
        androidx.compose.ui.res.stringResource(it.titleRes)
    }
    com.dongholab.pagetuner.ui.common.EinkSegmentedControl(
        options = ReaderSubPage.entries,
        selected = selectedPage,
        onSelect = onSelectPage,
        modifier = modifier,
        enabled = !busy,
        itemHeight = 52.dp,
        label = { labels.getValue(it) },
    )
}
