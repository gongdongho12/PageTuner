package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
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
import com.dongholab.pagetuner.ui.theme.EinkPaper
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
/**
 * Standardized App Navigation Tab Bar with E-Ink Active Tab Indicator Bar.
 * Enforces single-line non-wrapping text labels with balanced segmented design.
 */
@Composable
fun AppTabNavigation(
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
) {
    EinkSegmentedControl(
        options = AppTab.entries,
        selected = selectedTab,
        onSelect = onSelectTab,
        itemHeight = 42.dp,
        label = AppTab::title,
    )
}
