package com.dongholab.pagetuner.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.theme.EinkSoft

enum class ReaderSubPage(val title: String) {
    READER("📖 Reader"),
    SEARCH("🔍 Search"),
    BOOKMARKS("🔖 Bookmarks"),
    ANNOTATIONS("✏️ Notes"),
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = EinkPaper,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderSubPage.entries.forEach { subPage ->
                val selected = selectedPage == subPage
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = !busy) { onSelectPage(subPage) },
                    color = if (selected) EinkSoft else EinkPaper,
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, if (selected) EinkInk else EinkLine),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = subPage.title,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) EinkInk else EinkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // E-Ink Active Sub-Page Indicator Bar (3.dp Solid Line)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(if (selected) EinkInk else EinkPaper),
                        )
                    }
                }
            }
        }
    }
}
