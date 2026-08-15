package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper

/** A compact page bar whose three regions cannot push one another off-screen. */
@Composable
internal fun EinkPageNavigation(
    startIndex: Int,
    endIndex: Int,
    itemCount: Int,
    pageIndex: Int,
    pageCount: Int,
    busy: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPaper,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onPrevious,
                enabled = pageIndex > 0 && !busy,
                modifier = Modifier.weight(0.26f),
            ) {
                Text(
                    text = "◀ Prev",
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (pageIndex > 0 && !busy) EinkInk else EinkMuted,
                )
            }

            Text(
                text = "$startIndex–$endIndex / $itemCount\n${pageIndex + 1} / $pageCount",
                modifier = Modifier.weight(0.48f),
                maxLines = 2,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = EinkInk,
            )

            TextButton(
                onClick = onNext,
                enabled = pageIndex < pageCount - 1 && !busy,
                modifier = Modifier.weight(0.26f),
            ) {
                Text(
                    text = "Next ▶",
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (pageIndex < pageCount - 1 && !busy) EinkInk else EinkMuted,
                )
            }
        }
    }
}
