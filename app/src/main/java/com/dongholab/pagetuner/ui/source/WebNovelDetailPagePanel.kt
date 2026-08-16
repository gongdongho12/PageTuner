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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.settings.ListLayoutMode
import com.dongholab.pagetuner.source.BatchDownloadProgress
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.LocalListLayoutMode
import com.dongholab.pagetuner.ui.common.EinkOperationIndicator
import com.dongholab.pagetuner.ui.common.EinkPagingState
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.common.EinkStablePageContent
import com.dongholab.pagetuner.ui.common.rememberEinkPagingState
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.theme.EinkSoft

private enum class NovelDetailTab { Overview, Chapters, Tools }
private enum class ChapterReadLanguage { Original, Translation }
private val ChapterPagedRowHeight = 100.dp

internal fun findChapterByNumber(
    chapters: List<RemoteBookItem>,
    chapterNumber: Int,
): RemoteBookItem? {
    if (chapterNumber <= 0) return null
    return chapters.firstOrNull { it.chapterNumber == chapterNumber }
        ?: chapters.getOrNull(chapterNumber - 1)
}

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
    var activeTab by rememberSaveable { mutableStateOf(NovelDetailTab.Chapters) }
    var readLanguage by rememberSaveable(canTranslate) {
        mutableStateOf(if (canTranslate) ChapterReadLanguage.Translation else ChapterReadLanguage.Original)
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showQuickJumpDialog by rememberSaveable { mutableStateOf(false) }
    var quickJumpNumberText by rememberSaveable { mutableStateOf("") }
    val chapterPagingState = rememberEinkPagingState(
        novelItem.seriesId ?: novelItem.identity.remoteId,
        searchQuery,
    )

    val filteredChapters = remember(chapters, searchQuery) {
        chapters.filterIndexed { index, item ->
            searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                (item.chapterNumber ?: index + 1).toString() == searchQuery.trim()
        }
    }
    val tabLabels = mapOf(
        NovelDetailTab.Overview to stringResource(R.string.novel_detail_tab_overview),
        NovelDetailTab.Chapters to stringResource(R.string.chapter_section_browse),
        NovelDetailTab.Tools to stringResource(R.string.chapter_section_find_download),
    )

    if (showQuickJumpDialog) {
        QuickJumpDialog(
            value = quickJumpNumberText,
            onValueChange = { quickJumpNumberText = it },
            onDismiss = { showQuickJumpDialog = false },
            onConfirm = {
                quickJumpNumberText.toIntOrNull()?.let { number ->
                    findChapterByNumber(chapters, number)?.let(onReadChapter)
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
                compact = activeTab != NovelDetailTab.Overview,
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
                label = { tab -> tabLabels.getValue(tab) },
            )

            EinkStablePageContent(
                content = {
                    when (activeTab) {
                        NovelDetailTab.Overview -> NovelOverviewPanel(
                            novelItem = novelItem,
                            chapterCount = chapters.size,
                            modifier = Modifier.fillMaxSize(),
                        )
                        NovelDetailTab.Chapters -> ChapterListPanel(
                            chapters = chapters,
                            filteredChapters = filteredChapters,
                            busy = busy,
                            targetLanguage = targetLanguage,
                            readLanguage = readLanguage,
                            pagingState = chapterPagingState,
                            onSaveChapterTxt = onSaveChapterTxt,
                            onReadOriginalChapter = onReadOriginalChapter,
                            onReadChapter = onReadChapter,
                            modifier = Modifier.fillMaxSize(),
                        )
                        NovelDetailTab.Tools -> ChapterToolsPanel(
                            chapters = chapters,
                            filteredChapterCount = filteredChapters.size,
                            searchQuery = searchQuery,
                            busy = busy,
                            targetLanguage = targetLanguage,
                            canTranslate = canTranslate,
                            readLanguage = readLanguage,
                            onReadLanguageChange = { readLanguage = it },
                            onSearchQueryChange = { searchQuery = it },
                            onQuickJump = { showQuickJumpDialog = true },
                            onBatchDownload = { onBatchDownloadChapters?.invoke(chapters) },
                            onShowResults = { activeTab = NovelDetailTab.Chapters },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
                overlay = {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EinkOperationIndicator(
                            visible = busy || batchProgress != null,
                            title = when {
                                batchProgress?.isCompleted == true && batchProgress.failedItems > 0 ->
                                    stringResource(R.string.web_novel_offline_save_failed_title)
                                batchProgress?.isCompleted == true && batchProgress.translationFailedItems > 0 ->
                                    stringResource(R.string.web_novel_offline_translation_failed_title)
                                batchProgress?.isCompleted == true ->
                                    stringResource(R.string.web_novel_offline_save_complete_title)
                                chapters.isEmpty() ->
                                    stringResource(R.string.web_novel_loading_details_title)
                                batchProgress != null ->
                                    stringResource(R.string.web_novel_offline_saving_title)
                                else -> stringResource(R.string.web_novel_preparing_chapter_title)
                            },
                            detail = batchProgress?.let {
                                buildString {
                                    append("${it.currentItemIndex} / ${it.totalItems} · ${it.stage.name}")
                                    if (it.totalTranslationParts > 0) append(" · part ${it.translatedPart}/${it.totalTranslationParts}")
                                    append(" · saved ${it.savedItems}")
                                    if (it.failedItems > 0) append(" · failed ${it.failedItems}")
                                    if (it.translationFailedItems > 0) append(" · translation failed ${it.translationFailedItems}")
                                    it.targetLanguage?.let { language -> append(" · ${language.uppercase()}") }
                                    it.errorMessage?.takeIf(String::isNotBlank)?.let { error -> append(" · $error") }
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
                    }
                },
            )
        }
    }
}

@Composable
private fun NovelDetailHeader(
    novelItem: RemoteBookItem,
    coverBytes: ByteArray?,
    chapterCount: Int,
    displayMode: DisplayMode,
    compact: Boolean,
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
        if (!compact) {
            RemoteCoverThumbnail(
                coverBytes = coverBytes,
                displayMode = displayMode,
                contentDescription = novelItem.title,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = novelItem.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = EinkInk,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    text = "${novelItem.authors.firstOrNull() ?: "WTR-Lab"} · ${novelItem.chapterCount ?: chapterCount} Ch. · ${novelItem.language ?: "en"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = EinkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    chapters: List<RemoteBookItem>,
    filteredChapters: List<RemoteBookItem>,
    busy: Boolean,
    targetLanguage: String,
    readLanguage: ChapterReadLanguage,
    pagingState: EinkPagingState,
    onSaveChapterTxt: ((RemoteBookItem) -> Unit)?,
    onReadOriginalChapter: (RemoteBookItem) -> Unit,
    onReadChapter: (RemoteBookItem) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AdaptiveCollection(
            items = filteredChapters,
            estimatedPagedItemHeight = ChapterPagedRowHeight,
            fallbackPageSize = 3,
            busy = busy,
            pagingState = pagingState,
            itemKey = {
                "${it.identity.sourceType}:${it.identity.accountId}:${it.identity.remoteId}"
            },
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
                chapterIndex = chapter.chapterNumber ?: chapters.indexOf(chapter) + 1,
                busy = busy,
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
private fun ChapterToolsPanel(
    chapters: List<RemoteBookItem>,
    filteredChapterCount: Int,
    searchQuery: String,
    busy: Boolean,
    targetLanguage: String,
    canTranslate: Boolean,
    readLanguage: ChapterReadLanguage,
    onReadLanguageChange: (ChapterReadLanguage) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onQuickJump: () -> Unit,
    onBatchDownload: () -> Unit,
    onShowResults: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EinkSegmentedControl(
            options = ChapterReadLanguage.entries,
            selected = readLanguage,
            onSelect = onReadLanguageChange,
            enabled = !busy,
            itemHeight = 42.dp,
            label = { language ->
                if (language == ChapterReadLanguage.Original) "Read original"
                else "Read ${targetLanguage.uppercase()}"
            },
        )
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("Chapter number or title") },
            singleLine = true,
        )
        TextButton(
            onClick = onQuickJump,
            enabled = !busy && chapters.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Quick jump", fontWeight = FontWeight.Bold, color = EinkInk)
        }
        Button(
            onClick = onBatchDownload,
            enabled = !busy && canTranslate && chapters.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPanel),
            shape = RoundedCornerShape(2.dp),
        ) {
            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Original + ${targetLanguage.uppercase()}", maxLines = 2, style = MaterialTheme.typography.labelSmall)
        }
        Button(
            onClick = onShowResults,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPanel),
            shape = RoundedCornerShape(2.dp),
        ) {
            Text(stringResource(R.string.chapter_show_results, filteredChapterCount))
        }
    }
}

@Composable
private fun ChapterRowItem(
    chapter: RemoteBookItem,
    chapterIndex: Int,
    busy: Boolean,
    readLanguageLabel: String,
    onSaveChapterTxt: ((RemoteBookItem) -> Unit)?,
    onReadChapter: (RemoteBookItem) -> Unit,
) {
    val offlineLanguages = OfflineNovelStorageStore.globalOfflineStore.downloadedLanguages(chapter)
    val isOfflineSaved = offlineLanguages.isNotEmpty()
    val scrollLayout = LocalListLayoutMode.current == ListLayoutMode.Scroll

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (scrollLayout) Modifier.heightIn(min = 124.dp)
                else Modifier.height(ChapterPagedRowHeight),
            ),
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
                    .then(if (scrollLayout) Modifier else Modifier.weight(1f)),
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
                    maxLines = if (scrollLayout) 6 else 2,
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
                    enabled = !busy && onSaveChapterTxt != null,
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
