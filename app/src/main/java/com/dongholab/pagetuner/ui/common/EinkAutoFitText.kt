package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.dongholab.pagetuner.ui.theme.EinkInk

/** Fits long, non-scrollable reader text inside its assigned page without cutting a line. */
@Composable
fun EinkAutoFitText(
    text: String,
    requestedFontSizeSp: Int,
    lineSpacing: Float,
    modifier: Modifier = Modifier,
    color: Color = EinkInk,
    minimumFontSizeSp: Int = 11,
) = EinkAutoFitText(
    text = AnnotatedString(text),
    requestedFontSizeSp = requestedFontSizeSp,
    lineSpacing = lineSpacing,
    modifier = modifier,
    color = color,
    minimumFontSizeSp = minimumFontSizeSp,
)

/** Annotated variant used for high-contrast character-name emphasis. */
@Composable
fun EinkAutoFitText(
    text: AnnotatedString,
    requestedFontSizeSp: Int,
    lineSpacing: Float,
    modifier: Modifier = Modifier,
    color: Color = EinkInk,
    minimumFontSizeSp: Int = 11,
) {
    BoxWithConstraints(modifier = modifier) {
        val textMeasurer = rememberTextMeasurer(cacheSize = 16)
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val textStyle = MaterialTheme.typography.bodyLarge
        val availableSize = IntSize(
            width = constraints.maxWidth.coerceAtLeast(1),
            height = constraints.maxHeight.coerceAtLeast(1),
        )
        val fittedFontSizeSp = remember(
            text,
            requestedFontSizeSp,
            minimumFontSizeSp,
            lineSpacing,
            availableSize,
            textStyle,
            textMeasurer,
            density,
            layoutDirection,
        ) {
            var lower = minimumFontSizeSp.coerceAtMost(requestedFontSizeSp)
            var upper = requestedFontSizeSp.coerceAtLeast(lower)
            var best = lower
            while (lower <= upper) {
                val candidate = (lower + upper) / 2
                val measured = textMeasurer.measure(
                    text = text,
                    style = textStyle.copy(fontSize = candidate.sp),
                    overflow = TextOverflow.Clip,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                    constraints = Constraints(
                        maxWidth = availableSize.width,
                        maxHeight = availableSize.height,
                    ),
                    layoutDirection = layoutDirection,
                    density = density,
                )
                if (measured.hasVisualOverflow) {
                    upper = candidate - 1
                } else {
                    best = candidate
                    lower = candidate + 1
                }
            }
            best
        }

        Text(
            text = text,
            modifier = Modifier.fillMaxSize(),
            style = textStyle.copy(fontSize = fittedFontSizeSp.sp),
            color = color,
            lineHeight = (fittedFontSizeSp * lineSpacing).sp,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
        )
    }
}
