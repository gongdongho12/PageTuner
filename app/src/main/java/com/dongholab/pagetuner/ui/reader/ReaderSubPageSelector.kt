package com.dongholab.pagetuner.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkPaper

enum class ReaderSubPage(val title: String) {
    READER("📖 Reader"),
    SEARCH("🔍 Search"),
    BOOKMARKS("🔖 Bookmarks"),
    ANNOTATIONS("✏️ Notes"),
}

/**
 * Compact E-Ink Sub-Page Selector Bar for Reader Mode.
 * Switches between Full Viewport Reader, Search, Bookmarks, and Notes pages.
 */
@Composable
fun ReaderSubPageSelector(
    selectedPage: ReaderSubPage,
    busy: Boolean,
    onSelectPage: (ReaderSubPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = EinkPaper,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderSubPage.entries.forEach { subPage ->
                FilterChip(
                    selected = selectedPage == subPage,
                    onClick = { onSelectPage(subPage) },
                    enabled = !busy,
                    label = {
                        Text(
                            text = subPage.title,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selectedPage == subPage) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                )
            }
        }
    }
}
