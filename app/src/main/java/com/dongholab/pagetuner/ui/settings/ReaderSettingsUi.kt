@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.ui.text.localizedLabel
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkPanel
import kotlin.math.roundToInt

@Composable
fun DisplaySettingsPanel(
    displayMode: DisplayMode,
    busy: Boolean,
    onDisplayModeChange: (DisplayMode) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.display_settings_title),
                style = MaterialTheme.typography.labelLarge,
                color = EinkInk,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DisplayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = displayMode == mode,
                        onClick = { onDisplayModeChange(mode) },
                        enabled = !busy,
                        label = { Text(mode.localizedLabel()) },
                    )
                }
            }
        }
    }
}

@Composable
fun PageTurnSettingsPanel(
    pageTurnMode: PageTurnMode,
    busy: Boolean,
    onPageTurnModeChange: (PageTurnMode) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.page_turn_settings_title),
                style = MaterialTheme.typography.labelLarge,
                color = EinkInk,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PageTurnMode.entries.forEach { mode ->
                    FilterChip(
                        selected = pageTurnMode == mode,
                        onClick = { onPageTurnModeChange(mode) },
                        enabled = !busy,
                        label = { Text(stringResource(mode.labelRes)) },
                    )
                }
            }
        }
    }
}

@Composable
fun ReaderPreferencesPanel(
    pdfFitMode: PdfFitMode,
    fontSizeSp: Int,
    lineSpacing: Float,
    pageMarginDp: Int,
    busy: Boolean,
    onPdfFitModeChange: (PdfFitMode) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onPageMarginChange: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_preferences_title),
                style = MaterialTheme.typography.labelLarge,
                color = EinkInk,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PdfFitMode.entries.forEach { mode ->
                    FilterChip(
                        selected = pdfFitMode == mode,
                        onClick = { onPdfFitModeChange(mode) },
                        enabled = !busy,
                        label = { Text(stringResource(mode.labelRes)) },
                    )
                }
            }
            ReaderPreferenceSlider(
                label = stringResource(R.string.reader_font_size, fontSizeSp),
                value = fontSizeSp.toFloat(),
                valueRange = 14f..28f,
                steps = 13,
                busy = busy,
                onValueChange = { onFontSizeChange(it.roundToInt()) },
            )
            ReaderPreferenceSlider(
                label = stringResource(R.string.reader_line_spacing, lineSpacing),
                value = lineSpacing,
                valueRange = 1.1f..1.8f,
                steps = 6,
                busy = busy,
                onValueChange = { onLineSpacingChange((it * 100f).roundToInt() / 100f) },
            )
            ReaderPreferenceSlider(
                label = stringResource(R.string.reader_page_margin, pageMarginDp),
                value = pageMarginDp.toFloat(),
                valueRange = 8f..36f,
                steps = 13,
                busy = busy,
                onValueChange = { onPageMarginChange(it.roundToInt()) },
            )
        }
    }
}

@Composable
private fun ReaderPreferenceSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    busy: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = EinkInk,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = !busy,
        )
    }
}
