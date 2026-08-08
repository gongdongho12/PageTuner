package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.ui.common.EinkAutoFitPagingContainer
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft

@Composable
fun WebNovelDetailDialog(
    novelItem: RemoteBookItem,
    chapters: List<RemoteBookItem>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onReadChapter: (RemoteBookItem) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredChapters = remember(chapters, searchQuery) {
        if (searchQuery.isBlank()) {
            chapters
        } else {
            chapters.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = EinkInk)
            }
        },
        title = {
            Text(
                text = novelItem.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = EinkInk,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Novel Metadata & Details
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = EinkSoft,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, EinkLine),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Author: ${novelItem.authors.joinToString().ifBlank { "Unknown Author" }}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = EinkInk,
                        )
                        Text(
                            text = "Language: ${novelItem.language ?: "en"} | Format: ${novelItem.format.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = EinkMuted,
                        )
                        Text(
                            text = "URL: ${novelItem.downloadUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EinkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Chapter Search & Volume Selection Header
                Text(
                    text = "Chapters / Volume List (${filteredChapters.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = EinkInk,
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Filter chapters (e.g. Chapter 10)") },
                    singleLine = true,
                )

                // Paginated Chapter List for E-Ink
                EinkAutoFitPagingContainer(
                    items = filteredChapters,
                    estimatedItemHeight = 64.dp,
                    fallbackPageSize = 3,
                    busy = busy,
                    modifier = Modifier.weight(1f),
                    emptyContent = {
                        Text(
                            text = if (chapters.isEmpty()) "Loading chapter list..." else "No chapters match your filter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EinkMuted,
                        )
                    },
                ) { chapter ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        color = EinkPanel,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, EinkLine),
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = EinkInk,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Button(
                                onClick = {
                                    onReadChapter(chapter)
                                    onDismiss()
                                },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EinkInk,
                                    contentColor = EinkPanel,
                                ),
                                shape = RoundedCornerShape(2.dp),
                            ) {
                                Text("Read", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        containerColor = EinkPanel,
    )
}
