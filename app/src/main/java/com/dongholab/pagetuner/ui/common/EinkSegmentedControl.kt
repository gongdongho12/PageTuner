package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkPaper

/** Equal-width, non-scrolling tabs with a stable E-Ink selection marker. */
@Composable
fun <T> EinkSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    itemHeight: Dp = 46.dp,
    label: (T) -> String,
) {
    require(options.isNotEmpty()) { "EinkSegmentedControl requires at least one option." }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkInk),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(itemHeight)
                        .clickable(enabled = enabled) { onSelect(option) },
                    color = if (isSelected) EinkPaper else EinkPanel,
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, if (isSelected) EinkInk else EinkLine),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label(option),
                                modifier = Modifier.padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) EinkInk else EinkMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(if (isSelected) EinkInk else EinkPanel),
                        )
                    }
                }
            }
        }
    }
}
