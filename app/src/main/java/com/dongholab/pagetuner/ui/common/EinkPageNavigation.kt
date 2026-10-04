package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper

/** Use the same font metrics and height for the page plan and the actual bar. */
@Composable
internal fun einkPageNavigationHeight(): Dp {
    val measurer = rememberTextMeasurer()
    val center = measurer.measure(
        AnnotatedString("0–0 / 0\n0 / 0"),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        ),
    )
    val button = measurer.measure(
        AnnotatedString("◀ Prev"),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
    )
    return with(LocalDensity.current) { maxOf(60.dp, maxOf(center.size.height, button.size.height).toDp() + 8.dp) }
}

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
    height: Dp = einkPageNavigationHeight(),
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(height),
        color = EinkPaper,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onPrevious,
                enabled = pageIndex > 0 && !busy,
                modifier = Modifier.weight(0.26f).fillMaxHeight(),
                contentPadding = PaddingValues(0.dp),
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
                modifier = Modifier.weight(0.26f).fillMaxHeight(),
                contentPadding = PaddingValues(0.dp),
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
