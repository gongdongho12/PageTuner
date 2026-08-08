@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.dongholab.pagetuner.source.BatchDownloadProgress
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore
import com.dongholab.pagetuner.ui.common.EinkAutoFitPagingContainer
import com.dongholab.pagetuner.ui.common.EinkOperationIndicator
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.theme.EinkSoft

private enum class NovelDetailTab { Overview, Chapters }
private enum class ChapterReadLanguage { Original, Translation }

@Composable
fun WebNovelDetailPagePanel(
    novelItem: RemoteBookItem,
    coverBytes: ByteArray?,
    chapters: List<RemoteBookItem>,
    displayMode: DisplayMode,
    busy: Boolean,
    targetLanguage: String,
    canTranslate: Boolean,
    loadError: String? = null,
    isFavorite: Boolean = false,
    batchProgress: BatchDownloadProgress? = null,
    onToggleFavorite: () -> Unit = {},
    onBackToList: () -> Unit,
    onReadOriginalChapter: (RemoteBookItem) -> Unit,
    onReadChapter: (RemoteBookItem) -> Unit,
    onSaveChapterTxt: ((RemoteBookItem) -> Unit)? = null,
    onBatchDownloadChapters: ((List<RemoteBookItem>) -> Unit)? = null,
) {
    var activeTab by remember { mutableStateOf(NovelDetailTab.Chapters) }
    var searchQuery by remember { mutableStateOf("") }
    var showQuickJumpDialog by remember { mutableStateOf(false) }
    var quickJumpNumberText by remember { mutableStateOf("") }

    val filteredChapters = remember(chapters, searchQuery) {
        chapters.filterIndexed { index, item ->
            searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                (index + 1).toString() == searchQuery.trim()
        }
    }

    if (showQuickJumpDialog) {
        QuickJumpDialog(
            value = quickJumpNumberText,
            onValueChange = { quickJumpNumberText = it },
            onDismiss = { showQuickJumpDialog = false },
            onConfirm = {
                quickJumpNumberText.toIntOrNull()?.let { number ->
                    chapters.firstOrNull { chapter ->
                        chapter.title.contains("Chapter $number", ignoreCase = true) ||
                            chapter.title.contains("Ch. $number", ignoreCase = true)
                    }?.let(onReadChapter) ?: chapters.getOrNull(number - 1)?.let(onReadChapter)
                }
                showQuickJumpDialog = false
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NovelDetailHeader(
                novelItem = novelItem,
                coverBytes = coverBytes,
                chapterCount = chapters.size,
                displayMode = displayMode,
                busy = busy,
                isFavorite = isFavorite,
                onBackToList = onBackToList,
                onToggleFavorite = onToggleFavorite,
            )

            EinkSegmentedControl(
                options = NovelDetailTab.entries,
                selected = activeTab,
                onSelect = { activeTab = it },
                enabled = !busy,
                label = { tab -> if (tab == NovelDetailTab.Overview) "Overview" else "Chapters" },
            )

            EinkOperationIndicator(
                visible = busy,
                title = when {
                    chapters.isEmpty() -> "Loading novel details and chapter list…"
                    batchProgress != null -> "Saving chapters for offline reading…"
                    else -> "Preparing chapter and translation…"
                },
                detail = batchProgress?.let {
                    buildString {
                        append("${it.currentItemIndex} / ${it.totalItems} · ${it.stage.name}")
                        if (it.totalTranslationParts > 0) append(" · part ${it.translatedPart}/${it.totalTranslationParts}")
                        append(" · saved ${it.savedItems}")
                        if (it.failedItems > 0) append(" · failed ${it.failedItems}")
                        it.targetLanguage?.let { language -> append(" · ${language.uppercase()}") }
                    }
                },
                progress = batchProgress?.fraction,
            )

            if (!loadError.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = EinkPaper,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, EinkInk),
                ) {
                    Text(
                        text = loadError,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = EinkInk,
                    )
                }
            }

            when (activeTab) {
                NovelDetailTab.Overview -> NovelOverviewPanel(
                    novelItem = novelItem,
                    chapterCount = chapters.size,
                    modifier = Modifier.weight(1f),
                )
                NovelDetailTab.Chapters -> ChapterListPanel(
                    novelItem = novelItem,
                    chapters = chapters,
                    filteredChapters = filteredChapters,
                    searchQuery = searchQuery,
                    busy = busy,
                    targetLanguage = targetLanguage,
                    canTranslate = canTranslate,
                    onSearchQueryChange = { searchQuery = it },
                    onQuickJump = { showQuickJumpDialog = true },
                    onBatchDownload = { onBatchDownloadChapters?.invoke(chapters) },
                    onSaveChapterTxt = onSaveChapterTxt,
                    onReadOriginalChapter = onReadOriginalChapter,
                    onReadChapter = onReadChapter,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NovelDetailHeader(
    novelItem: RemoteBookItem,
    coverBytes: ByteArray?,
    chapterCount: Int,
    displayMode: DisplayMode,
    busy: Boolean,
    isFavorite: Boolean,
    onBackToList: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackToList) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to catalog", tint = EinkInk)
        }
        RemoteCoverThumbnail(
            coverBytes = coverBytes,
            displayMode = displayMode,
            contentDescription = novelItem.title,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = novelItem.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = EinkInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${novelItem.authors.firstOrNull() ?: "WTR-Lab"} · ${novelItem.chapterCount ?: chapterCount} Ch. · ${novelItem.language ?: "en"}",
                style = MaterialTheme.typography.labelSmall,
                color = EinkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleFavorite, enabled = !busy) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "Favorite",
                tint = EinkInk,
            )
        }
    }
}

@Composable
private fun NovelOverviewPanel(novelItem: RemoteBookItem, chapterCount: Int, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = EinkSoft,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Book information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Author: ${novelItem.authors.joinToString().ifBlank { "WTR-Lab" }}")
            Text("Chapters: ${novelItem.chapterCount ?: chapterCount} · Language: ${novelItem.language ?: "en"}")
            val tags = remember(novelItem) { novelItem.tags.filter { it.isNotBlank() }.distinct().take(6) }
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        FilterChip(selected = false, onClick = {}, label = { Text(tag, maxLines = 1) })
                    }
                }
            }
            Text("Synopsis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = novelItem.description?.ifBlank { "No synopsis was provided by the source page." }
                    ?: "No synopsis was provided by the source page.",
                style = MaterialTheme.typography.bodyMedium,
                color = EinkInk,
                maxLines = 16,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChapterListPanel(
    novelItem: RemoteBookItem,
    chapters: List<RemoteBookItem>,
    filteredChapters: List<RemoteBookItem>,
    searchQuery: String,
    busy: Boolean,
    targetLanguage: String,
    canTranslate: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onQuickJump: () -> Unit,
    onBatchDownload: () -> Unit,
    onSaveChapterTxt: ((RemoteBookItem) -> Unit)?,
    onReadOriginalChapter: (RemoteBookItem) -> Unit,
    onReadChapter: (RemoteBookItem) -> Unit,
    modifier: Modifier,
) {
    var readLanguage by remember(canTranslate) {
        mutableStateOf(if (canTranslate) ChapterReadLanguage.Translation else ChapterReadLanguage.Original)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(
                onClick = onQuickJump,
                enabled = !busy && chapters.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Quick jump", fontWeight = FontWeight.Bold, color = EinkInk)
            }
            Button(
                onClick = onBatchDownload,
                enabled = !busy && canTranslate && chapters.isNotEmpty(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPanel),
                shape = RoundedCornerShape(2.dp),
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Original + ${targetLanguage.uppercase()}", maxLines = 2, style = MaterialTheme.typography.labelSmall)
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("Chapter number or title") },
            singleLine = true,
        )

        EinkSegmentedControl(
            options = ChapterReadLanguage.entries,
            selected = readLanguage,
            onSelect = { readLanguage = it },
            enabled = !busy,
            itemHeight = 38.dp,
            label = { language ->
                if (language == ChapterReadLanguage.Original) "Read original"
                else "Read ${targetLanguage.uppercase()}"
            },
        )

        EinkAutoFitPagingContainer(
            items = filteredChapters,
            estimatedItemHeight = 124.dp,
            fallbackPageSize = 3,
            busy = busy,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            emptyContent = {
                Text(
                    text = if (busy || chapters.isEmpty()) {
                        "Chapter list is being loaded…"
                    } else {
                        "No chapters match this filter."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = EinkMuted,
                )
            },
        ) { chapter ->
            ChapterRowItem(
                chapter = chapter,
                chapterIndex = chapters.indexOf(chapter) + 1,
                busy = busy,
                canTranslate = canTranslate,
                onSaveChapterTxt = onSaveChapterTxt,
                readLanguageLabel = if (readLanguage == ChapterReadLanguage.Original) {
                    "Read original"
                } else {
                    "Read ${targetLanguage.uppercase()}"
                },
                onReadChapter = if (readLanguage == ChapterReadLanguage.Original) {
                    onReadOriginalChapter
                } else {
                    onReadChapter
                },
            )
        }
    }
}

@Composable
private fun ChapterRowItem(
    chapter: RemoteBookItem,
    chapterIndex: Int,
    busy: Boolean,
    canTranslate: Boolean,
    readLanguageLabel: String,
    onSaveChapterTxt: ((RemoteBookItem) -> Unit)?,
    onReadChapter: (RemoteBookItem) -> Unit,
) {
    val offlineLanguages = OfflineNovelStorageStore.globalOfflineStore.downloadedLanguages(chapter)
    val isOfflineSaved = offlineLanguages.isNotEmpty()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(124.dp),
        color = EinkSoft,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Filled.Book, contentDescription = null, tint = EinkMuted, modifier = Modifier.size(16.dp))
                Text(
                    text = "$chapterIndex. ${chapter.title}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = EinkInk,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isOfflineSaved) {
                    Text(
                        "Saved ${offlineLanguages.joinToString("+") { it.uppercase() }} ✓",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(
                    onClick = { onSaveChapterTxt?.invoke(chapter) },
                    enabled = !busy && canTranslate && onSaveChapterTxt != null,
                    modifier = Modifier.weight(0.36f),
                ) {
                    Text("Save offline", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = { onReadChapter(chapter) },
                    enabled = !busy,
                    modifier = Modifier.weight(0.64f),
                    colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPanel),
                    shape = RoundedCornerShape(2.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isOfflineSaved) "$readLanguageLabel offline" else readLanguageLabel,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickJumpDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to chapter", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Chapter number") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Jump", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = EinkPanel,
        shape = RoundedCornerShape(6.dp),
    )
}
