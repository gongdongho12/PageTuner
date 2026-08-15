package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
    minimumFontSizeSp: Int = 13,
) {
    BoxWithConstraints(modifier = modifier) {
        var fittedFontSizeSp by remember(text, requestedFontSizeSp, maxWidth, maxHeight) {
            mutableIntStateOf(requestedFontSizeSp)
        }

        Text(
            text = text,
            modifier = Modifier.fillMaxSize(),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = fittedFontSizeSp.sp),
            color = color,
            lineHeight = (fittedFontSizeSp * lineSpacing).sp,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            onTextLayout = { result ->
                if (result.didOverflowHeight && fittedFontSizeSp > minimumFontSizeSp) {
                    fittedFontSizeSp -= 1
                }
            },
        )
    }
}
