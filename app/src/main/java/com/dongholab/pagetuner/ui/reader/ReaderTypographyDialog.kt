@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.settings.ReaderFontFamily
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.theme.EinkSoft
import kotlin.math.roundToInt

@Composable
fun ReaderTypographyDialog(
    fontSizeSp: Int,
    lineSpacing: Float,
    pageMarginDp: Int,
    fontFamily: ReaderFontFamily,
    onFontSizeChange: (Int) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onPageMarginChange: (Int) -> Unit,
    onFontFamilyChange: (ReaderFontFamily) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EinkPaper,
        titleContentColor = EinkInk,
        textContentColor = EinkInk,
        shape = RoundedCornerShape(8.dp),
        title = {
            Text(
                text = stringResource(R.string.reader_typography_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. Font Family Selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.reader_font_family),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = EinkInk,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ReaderFontFamily.entries.forEach { family ->
                            FilterChip(
                                selected = fontFamily == family,
                                onClick = { onFontFamilyChange(family) },
                                label = {
                                    Text(
                                        text = stringResource(family.labelRes),
                                        fontFamily = family.toComposeFontFamily(),
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EinkInk,
                                    selectedLabelColor = EinkPaper,
                                    containerColor = Color.Transparent,
                                    labelColor = EinkInk,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = fontFamily == family,
                                    borderColor = EinkLine,
                                    selectedBorderColor = EinkInk,
                                    borderWidth = 1.dp,
                                    selectedBorderWidth = 1.5.dp,
                                ),
                            )
                        }
                    }
                }

                // 2. Font Size (14 ~ 36 sp)
                TypographyStepperRow(
                    label = stringResource(R.string.typography_font_size),
                    currentValueText = "${fontSizeSp} sp",
                    value = fontSizeSp.toFloat(),
                    valueRange = 14f..36f,
                    steps = 21,
                    decrementLabel = "A-",
                    incrementLabel = "A+",
                    canDecrement = fontSizeSp > 14,
                    canIncrement = fontSizeSp < 36,
                    onDecrement = { onFontSizeChange((fontSizeSp - 1).coerceAtLeast(14)) },
                    onIncrement = { onFontSizeChange((fontSizeSp + 1).coerceAtMost(36)) },
                    onSliderChange = { onFontSizeChange(it.roundToInt().coerceIn(14, 36)) },
                )

                // 3. Line Spacing (1.1x ~ 2.4x)
                TypographyStepperRow(
                    label = stringResource(R.string.typography_line_spacing),
                    currentValueText = "%.2fx".format(lineSpacing),
                    value = lineSpacing,
                    valueRange = 1.1f..2.4f,
                    steps = 12,
                    decrementLabel = "-",
                    incrementLabel = "+",
                    canDecrement = lineSpacing > 1.11f,
                    canIncrement = lineSpacing < 2.39f,
                    onDecrement = {
                        val next = ((lineSpacing - 0.05f) * 100f).roundToInt() / 100f
                        onLineSpacingChange(next.coerceIn(1.1f, 2.4f))
                    },
                    onIncrement = {
                        val next = ((lineSpacing + 0.05f) * 100f).roundToInt() / 100f
                        onLineSpacingChange(next.coerceIn(1.1f, 2.4f))
                    },
                    onSliderChange = {
                        val rounded = ((it * 100f).roundToInt() / 100f).coerceIn(1.1f, 2.4f)
                        onLineSpacingChange(rounded)
                    },
                )

                // 4. Page Margin (0 ~ 48 dp)
                TypographyStepperRow(
                    label = stringResource(R.string.typography_page_margin),
                    currentValueText = "${pageMarginDp} dp",
                    value = pageMarginDp.toFloat(),
                    valueRange = 0f..48f,
                    steps = 21,
                    decrementLabel = "-",
                    incrementLabel = "+",
                    canDecrement = pageMarginDp > 0,
                    canIncrement = pageMarginDp < 48,
                    onDecrement = { onPageMarginChange((pageMarginDp - 2).coerceAtLeast(0)) },
                    onIncrement = { onPageMarginChange((pageMarginDp + 2).coerceAtMost(48)) },
                    onSliderChange = { onPageMarginChange(it.roundToInt().coerceIn(0, 48)) },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onFontSizeChange(18)
                    onLineSpacingChange(1.35f)
                    onPageMarginChange(18)
                    onFontFamilyChange(ReaderFontFamily.DEFAULT)
                },
            ) {
                Text(
                    text = stringResource(R.string.typography_reset_defaults),
                    color = EinkMuted,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = EinkInk),
            ) {
                Text(
                    text = stringResource(R.string.action_close),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

@Composable
private fun TypographyStepperRow(
    label: String,
    currentValueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    decrementLabel: String,
    incrementLabel: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onSliderChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = EinkInk,
            )
            Text(
                text = currentValueText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = EinkMuted,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onDecrement,
                enabled = canDecrement,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, if (canDecrement) EinkInk else EinkLine),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = EinkInk,
                    disabledContentColor = EinkMuted,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.width(44.dp),
            ) {
                Text(decrementLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Slider(
                value = value,
                onValueChange = onSliderChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = EinkInk,
                    activeTrackColor = EinkInk,
                    inactiveTrackColor = EinkLine,
                ),
            )
            OutlinedButton(
                onClick = onIncrement,
                enabled = canIncrement,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, if (canIncrement) EinkInk else EinkLine),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = EinkInk,
                    disabledContentColor = EinkMuted,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.width(44.dp),
            ) {
                Text(incrementLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}
