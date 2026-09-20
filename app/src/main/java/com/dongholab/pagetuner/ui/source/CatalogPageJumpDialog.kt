package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkPaper

@Composable
fun CatalogPageJumpDialog(
    currentPage: Int,
    totalPages: Int,
    onJumpToPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val maxPage = totalPages.coerceAtLeast(1)
    var inputPageText by remember(currentPage) { mutableStateOf(currentPage.toString()) }

    fun submitJump() {
        val target = inputPageText.trim().toIntOrNull()?.coerceIn(1, maxPage) ?: currentPage
        onJumpToPage(target)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.catalog_jump_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = EinkInk,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.catalog_jump_current, currentPage, maxPage),
                    style = MaterialTheme.typography.bodySmall,
                    color = EinkMuted,
                )

                OutlinedTextField(
                    value = inputPageText,
                    onValueChange = { newText ->
                        if (newText.isEmpty() || newText.all { it.isDigit() }) {
                            inputPageText = newText
                        }
                    },
                    label = { Text(stringResource(R.string.catalog_jump_input_label, maxPage)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitJump() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Quick Step Row: -50, -10, +10, +50
                Text(
                    text = "빠른 이동 단위",
                    style = MaterialTheme.typography.labelSmall,
                    color = EinkMuted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(-50, -10, 10, 50).forEach { delta ->
                        val target = (currentPage + delta).coerceIn(1, maxPage)
                        val isEnabled = target != currentPage
                        OutlinedButton(
                            onClick = { onJumpToPage(target) },
                            enabled = isEnabled,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp),
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(1.dp, if (isEnabled) EinkInk else EinkLine),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                        ) {
                            Text(
                                text = if (delta > 0) "+$delta" else "$delta",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabled) EinkInk else EinkMuted,
                            )
                        }
                    }
                }

                // Extreme Row: First (1) / Last (maxPage)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { onJumpToPage(1) },
                        enabled = currentPage > 1,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp),
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(1.dp, if (currentPage > 1) EinkInk else EinkLine),
                    ) {
                        Text(
                            text = "⏮ " + stringResource(R.string.catalog_jump_first),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (currentPage > 1) EinkInk else EinkMuted,
                        )
                    }
                    OutlinedButton(
                        onClick = { onJumpToPage(maxPage) },
                        enabled = currentPage < maxPage,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp),
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(1.dp, if (currentPage < maxPage) EinkInk else EinkLine),
                    ) {
                        Text(
                            text = stringResource(R.string.catalog_jump_last) + " ⏭",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (currentPage < maxPage) EinkInk else EinkMuted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submitJump() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EinkInk,
                    contentColor = EinkPaper,
                ),
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(
                    text = stringResource(R.string.catalog_jump_button),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = EinkInk,
                )
            }
        },
        containerColor = EinkPanel,
        shape = RoundedCornerShape(4.dp),
    )
}
