package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft

enum class AppTab(val title: String) {
    Local("Local"),
    Favorites("Favorites ★"),
    WebNovel("Web Novel"),
    RemoteDrive("Drive"),
    Settings("Settings"),
}

/**
 * Standardized App Navigation Tab Bar with E-Ink Active Tab Indicator Bar.
 * Enforces single-line non-wrapping text labels.
 */
@Composable
fun AppTabNavigation(
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable { onSelectTab(tab) },
                    color = if (selected) EinkSoft else EinkPanel,
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, if (selected) EinkInk else EinkLine),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 1.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.5.sp),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) EinkInk else EinkMuted,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // E-Ink Active Tab Indicator Bar (3.dp Solid Line)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(if (selected) EinkInk else EinkPanel),
                        )
                    }
                }
            }
        }
    }
}
