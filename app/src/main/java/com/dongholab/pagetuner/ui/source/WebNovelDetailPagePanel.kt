package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.ui.common.EinkPagingContainer
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft

@Composable
fun WebNovelDetailPagePanel(
    novelItem: RemoteBookItem,
    coverBytes: ByteArray?,
    chapters: List<RemoteBookItem>,
    displayMode: DisplayMode,
    busy: Boolean,
    isFavorite: Boolean = false,
    batchProgress: com.dongholab.pagetuner.source.BatchDownloadProgress? = null,
    onToggleFavorite: () -> Unit = {},
    onBackToList: () -> Unit,
    onReadChapter: (RemoteBookItem) -> Unit,
    onSaveChapterTxt: ((RemoteBookItem) -> Unit)? = null,
    onBatchDownloadChapters: ((List<RemoteBookItem>) -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedVolumeFilter by remember { mutableStateOf<Int?>(null) }
    var selectedPageSize by remember { mutableStateOf(6) }
    var showQuickJumpDialog by remember { mutableStateOf(false) }
    var quickJumpNumberText by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("TOC") } // "ABOUT", "TOC"

    val volumeNumbers = remember(chapters) {
        chapters.mapNotNull { ch ->
            com.dongholab.pagetuner.source.WebNovelTextExtractor.extractVolumeNumber(ch.title, ch.downloadUrl)
        }.distinct().sorted()
    }

    val filteredChapters = remember(chapters, searchQuery, selectedVolumeFilter) {
        chapters.filter { item ->
            val matchQuery = searchQuery.isBlank() || item.title.contains(searchQuery, ignoreCase = true)
            val itemVol = com.dongholab.pagetuner.source.WebNovelTextExtractor.extractVolumeNumber(item.title, item.downloadUrl)
            val matchVol = selectedVolumeFilter == null || itemVol == selectedVolumeFilter
            matchQuery && matchVol
        }
    }

    if (showQuickJumpDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showQuickJumpDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val num = quickJumpNumberText.toIntOrNull()
                        if (num != null) {
                            val targetCh = chapters.firstOrNull { ch ->
                                ch.title.contains("Chapter $num", ignoreCase = true) ||
                                    ch.title.contains("Ch. $num", ignoreCase = true) ||
                                    ch.title.contains(" $num ")
                            } ?: chapters.getOrNull(num - 1)
                            if (targetCh != null) {
                                onReadChapter(targetCh)
                            }
                        }
                        showQuickJumpDialog = false
                    },
                ) {
                    Text("Jump 🚀", fontWeight = FontWeight.Bold, color = EinkInk)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickJumpDialog = false }) {
                    Text("Cancel", color = EinkMuted)
                }
            },
            title = { Text("Quick Jump to Chapter Number", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = quickJumpNumberText,
                    onValueChange = { quickJumpNumberText = it },
                    label = { Text("Enter Chapter Number (e.g. 5)") },
                    singleLine = true,
                )
            },
            containerColor = EinkPanel,
            shape = RoundedCornerShape(6.dp),
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top Navigation Header: Back to Catalog List
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBackToList, enabled = !busy) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back to Novel Catalog", tint = EinkInk)
                }
                IconButton(onClick = onToggleFavorite, enabled = !busy) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (isFavorite) "Remove Favorite" else "Add Favorite",
                        tint = EinkInk,
                    )
                }
            }

            // Novel Overview Header: Cover, Title, Author, Genre, Description
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = EinkSoft,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, EinkLine),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    com.dongholab.pagetuner.ui.source.RemoteCoverThumbnail(
                        coverBytes = coverBytes,
                        displayMode = displayMode,
                        contentDescription = novelItem.title,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = novelItem.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = EinkInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Author: ${novelItem.authors.joinToString().ifBlank { "WTR-Lab Author" }}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = EinkInk,
                        )
                        Text(
                            text = "Genre: Web Novel / Fantasy • Language: ${novelItem.language ?: "en"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EinkMuted,
                        )
                        // 2x3 High Contrast E-Ink Metadata Grid Block
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = EinkPanel,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, EinkLine),
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("★ Rating: 4.8", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = EinkInk)
                                    Text("👥 Readers: 12.4k", style = MaterialTheme.typography.labelSmall, color = EinkInk)
                                    Text("📚 Ch: ${chapters.size}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = EinkInk)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("⚡ Status: Ongoing", style = MaterialTheme.typography.labelSmall, color = EinkInk)
                                    Text("Author: WTR-Lab", style = MaterialTheme.typography.labelSmall, color = EinkMuted)
                                    Text("Lang: ${novelItem.language ?: "en"}", style = MaterialTheme.typography.labelSmall, color = EinkMuted)
                                }
                            }
                        }

                        // Tag Chips List (.tags-list .tag-chip)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            listOf("System 💻", "Transmigration 🌀", "Action ⚔️", "Fantasy 🪄").forEach { tag ->
                                androidx.compose.material3.FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }
            }

            // Section Header & Quick Jump / Batch Download Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.ListAlt, contentDescription = null, tint = EinkInk, modifier = Modifier.size(20.dp))
                    Text(
                        text = "Table of Contents (${chapters.size} Ch.)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = EinkInk,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { showQuickJumpDialog = true },
                        enabled = !busy && chapters.isNotEmpty(),
                    ) {
                        Text("Quick Jump 🚀", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onBatchDownloadChapters?.invoke(chapters) },
                        enabled = !busy && chapters.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EinkInk,
                            contentColor = EinkPanel,
                        ),
                        shape = RoundedCornerShape(2.dp),
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Batch 📥", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (batchProgress != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = EinkSoft,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, EinkLine),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Background Offline Download: ${batchProgress.currentItemIndex} / ${batchProgress.totalItems} - ${batchProgress.currentTitle}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = EinkInk,
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { batchProgress.currentItemIndex.toFloat() / batchProgress.totalItems.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            color = EinkInk,
                        )
                    }
                }
            }

            // Volume (권) Filter Bar
            if (volumeNumbers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Select Volume (권):",
                        style = MaterialTheme.typography.labelSmall,
                        color = EinkMuted,
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        androidx.compose.material3.FilterChip(
                            selected = selectedVolumeFilter == null,
                            onClick = { selectedVolumeFilter = null },
                            enabled = !busy,
                            label = { Text("All Volumes", style = MaterialTheme.typography.labelSmall) },
                        )
                        volumeNumbers.forEach { vol ->
                            androidx.compose.material3.FilterChip(
                                selected = selectedVolumeFilter == vol,
                                onClick = { selectedVolumeFilter = vol },
                                enabled = !busy,
                                label = { Text("Volume $vol", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }

            // Search / Filter Chapters Box & Page Size Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    label = { Text("Filter by Chapter Number or Title...") },
                    singleLine = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(6, 12, 24).forEach { size ->
                        androidx.compose.material3.FilterChip(
                            selected = selectedPageSize == size,
                            onClick = { selectedPageSize = size },
                            enabled = !busy,
                            label = { Text("$size/p", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }

            // Chapter Number & Title List with Dynamic EinkAutoFitPagingContainer (Zero Item Clipping)
            com.dongholab.pagetuner.ui.common.EinkAutoFitPagingContainer(
                items = filteredChapters,
                busy = busy,
                estimatedItemHeight = 48.dp,
                emptyContent = {
                    Text(
                        text = if (chapters.isEmpty()) "Fetching chapter list from novel page..." else "No chapters match your filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EinkMuted,
                    )
                },
            ) { chapter ->
                val chapterIndex = chapters.indexOf(chapter) + 1
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = EinkSoft,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, EinkLine),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Filled.Book, contentDescription = null, tint = EinkMuted, modifier = Modifier.size(16.dp))
                            Column {
                                val isOfflineSaved = com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore.globalOfflineStore.isChapterDownloaded(novelItem.identity.accountId, chapterIndex)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Ch. #$chapterIndex - ${chapter.title}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = EinkInk,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (isOfflineSaved) {
                                        Text(
                                            text = "Offline Ready ✅",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = EinkInk,
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { onSaveChapterTxt?.invoke(chapter) },
                                enabled = !busy,
                            ) {
                                Text("Save .txt 💾", style = MaterialTheme.typography.labelSmall, color = EinkInk)
                            }
                            Button(
                                onClick = { onReadChapter(chapter) },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EinkInk,
                                    contentColor = EinkPanel,
                                ),
                                shape = RoundedCornerShape(2.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Read", modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Read & Translate", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
