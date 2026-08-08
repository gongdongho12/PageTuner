package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.dongholab.pagetuner.ui.theme.EinkPaper

/**
 * Standardized E-Ink Single-Line Toolbar.
 * Strictly guarantees ZERO line-wrapping (maxLines = 1) even when text is long.
 */
@Composable
fun EinkSingleLineToolbar(
    title: String,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingActions: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = EinkPaper,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcon?.invoke()
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp),
                    fontWeight = FontWeight.Bold,
                    color = EinkInk,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (trailingActions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    trailingActions()
                }
            }
        }
    }
}
