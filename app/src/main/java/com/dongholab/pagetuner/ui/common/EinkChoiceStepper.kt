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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper

/** Makes an arbitrary number of choices reachable without wrapping chips below the viewport. */
@Composable
fun <T> EinkChoiceStepper(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (T) -> String,
) {
    if (options.isEmpty()) return
    val selectedIndex = options.indexOf(selected).takeIf { it >= 0 } ?: 0

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = EinkPaper,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onSelect(options[selectedIndex - 1]) },
                enabled = enabled && selectedIndex > 0,
                modifier = Modifier.weight(0.24f),
            ) {
                Text("◀", color = if (enabled && selectedIndex > 0) EinkInk else EinkMuted)
            }
            Text(
                text = label(options[selectedIndex]),
                modifier = Modifier.weight(0.52f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = EinkInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = { onSelect(options[selectedIndex + 1]) },
                enabled = enabled && selectedIndex < options.lastIndex,
                modifier = Modifier.weight(0.24f),
            ) {
                Text("▶", color = if (enabled && selectedIndex < options.lastIndex) EinkInk else EinkMuted)
            }
        }
    }
}
