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
import androidx.compose.ui.text.font.FontFamily
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
    fontFamily: FontFamily = FontFamily.Default,
    color: Color = EinkInk,
    minimumFontSizeSp: Int = 11,
) = EinkAutoFitText(
    text = AnnotatedString(text),
    requestedFontSizeSp = requestedFontSizeSp,
    lineSpacing = lineSpacing,
    modifier = modifier,
    fontFamily = fontFamily,
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
    fontFamily: FontFamily = FontFamily.Default,
    color: Color = EinkInk,
    minimumFontSizeSp: Int = 11,
) {
    BoxWithConstraints(modifier = modifier) {
        val textMeasurer = rememberTextMeasurer(cacheSize = 16)
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = fontFamily)
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
            val lower = minimumFontSizeSp.coerceAtMost(requestedFontSizeSp)
            var upper = requestedFontSizeSp.coerceAtLeast(lower)

            // Fast path: test requested font size first (fits ~90% of pages without binary search loops)
            val testRequested = textMeasurer.measure(
                text = text,
                style = textStyle.copy(
                    fontSize = upper.sp,
                    lineHeight = (upper * lineSpacing).sp,
                ),
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
            if (!testRequested.hasVisualOverflow) {
                return@remember upper
            }

            var best = lower
            var searchLower = lower
            upper -= 1
            while (searchLower <= upper) {
                val candidate = (searchLower + upper) / 2
                val measured = textMeasurer.measure(
                    text = text,
                    style = textStyle.copy(
                        fontSize = candidate.sp,
                        lineHeight = (candidate * lineSpacing).sp,
                    ),
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
                    searchLower = candidate + 1
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
