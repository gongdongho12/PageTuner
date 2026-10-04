package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
    canPrevious: Boolean = pageIndex > 0 && !busy,
    canNext: Boolean = pageIndex < pageCount - 1 && !busy,
    infoPrefix: String? = null,
    onInfoClick: (() -> Unit)? = null,
    onFastPrevious: (() -> Unit)? = null,
    onFastNext: (() -> Unit)? = null,
    height: Dp = einkPageNavigationHeight(),
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(height),
        color = EinkPaper,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // The jump dialog retains the same shortcuts when a narrow bar cannot fit five targets.
        val hasFastNav = maxWidth >= 360.dp && (onFastPrevious != null || onFastNext != null)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasFastNav && onFastPrevious != null) {
                TextButton(
                    onClick = onFastPrevious,
                    enabled = !busy,
                    modifier = Modifier.weight(0.14f).fillMaxHeight(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "◀ 10",
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (!busy) EinkInk else EinkMuted,
                    )
                }
            }

            TextButton(
                onClick = onPrevious,
                enabled = canPrevious,
                modifier = Modifier.weight(if (hasFastNav) 0.20f else 0.26f).fillMaxHeight(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    text = "◀ Prev",
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (canPrevious) EinkInk else EinkMuted,
                )
            }

            val centerText = buildString {
                if (!infoPrefix.isNullOrBlank()) {
                    append(infoPrefix)
                }
                append("$startIndex–$endIndex / $itemCount\n${pageIndex + 1} / $pageCount")
                if (onInfoClick != null) {
                    append(" ▾")
                }
            }

            val centerWeight = if (hasFastNav) 0.32f else 0.48f

            if (onInfoClick != null) {
                TextButton(
                    onClick = onInfoClick,
                    enabled = !busy,
                    modifier = Modifier.weight(centerWeight).fillMaxHeight(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = centerText,
                        maxLines = 2,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = EinkInk,
                    )
                }
            } else {
                Text(
                    text = centerText,
                    modifier = Modifier.weight(centerWeight),
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = EinkInk,
                )
            }

            TextButton(
                onClick = onNext,
                enabled = canNext,
                modifier = Modifier.weight(if (hasFastNav) 0.20f else 0.26f).fillMaxHeight(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    text = "Next ▶",
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (canNext) EinkInk else EinkMuted,
                )
            }

            if (hasFastNav && onFastNext != null) {
                TextButton(
                    onClick = onFastNext,
                    enabled = !busy,
                    modifier = Modifier.weight(0.14f).fillMaxHeight(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "10 ▶",
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (!busy) EinkInk else EinkMuted,
                    )
                }
            }
        }
    }
}
}
