@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.display.applyDisplayMode
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.ReaderPageImage
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.reader.ReaderAnnotation
import com.dongholab.pagetuner.reader.ReaderAnnotationType
import com.dongholab.pagetuner.reader.ReaderBookmark
import com.dongholab.pagetuner.reader.ReaderDisplayPosition
import com.dongholab.pagetuner.reader.ReaderDisplayNavigation
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.settings.ReaderFontFamily
import com.dongholab.pagetuner.translation.TranslationDisplayMode
import com.dongholab.pagetuner.translation.PageTranslation
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import com.dongholab.pagetuner.translation.glossary.GlossaryTextProcessor
import com.dongholab.pagetuner.translation.glossary.GlossaryDisplayText
import com.dongholab.pagetuner.ui.text.localizedName
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.theme.EinkSoft

@Composable
fun ReaderHeader(
    document: ReaderDocument,
    page: ReaderPage,
    controlsVisible: Boolean,
    onOpen: () -> Unit,
    onToggleControls: () -> Unit,
    onManualRefresh: () -> Unit,
    onShowDetails: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onShowTypography: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = EinkInk,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.format_page_count,
                    document.format.localizedName(),
                    page.index + 1,
                    document.pageCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = EinkMuted,
            )
            page.chapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    text = stringResource(R.string.chapter_label, title),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = EinkInk,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (controlsVisible) {
                if (onShowTypography != null) {
                    IconButton(onClick = onShowTypography) {
                        Text(
                            text = "Aa",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = EinkInk,
                        )
                    }
                }
                IconButton(onClick = onManualRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_manual_refresh),
                        tint = EinkInk,
                    )
                }
                IconButton(onClick = onShowDetails) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(R.string.action_show_details),
                        tint = EinkInk,
                    )
                }
            }
            IconButton(onClick = onEnterFullscreen) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.action_enter_fullscreen),
                    tint = EinkInk,
                )
            }
            IconButton(onClick = onToggleControls) {
                Icon(
                    imageVector = if (controlsVisible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = stringResource(
                        if (controlsVisible) {
                            R.string.action_hide_controls
                        } else {
                            R.string.action_show_controls
                        },
                    ),
                    tint = EinkInk,
                )
            }
            if (controlsVisible) {
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPaper),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_open))
                }
            }
        }
    }
}

@Composable
fun ReaderPager(
    pageIndex: Int,
    pageCount: Int,
    busy: Boolean,
    currentChapterTitle: String?,
    canPreviousChapter: Boolean,
    canNextChapter: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    canPreviousDisplayPage: Boolean = pageIndex > 0,
    canNextDisplayPage: Boolean = pageIndex < pageCount - 1,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onPrevious,
                enabled = !busy && canPreviousDisplayPage,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                Text(stringResource(R.string.action_previous))
            }
            Text(
                text = stringResource(R.string.reader_source_position, pageIndex + 1, pageCount),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = EinkInk,
                fontFamily = FontFamily.Monospace,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton(
                onClick = onNext,
                enabled = !busy && canNextDisplayPage,
            ) {
                Text(stringResource(R.string.action_next))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
        currentChapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onPreviousChapter,
                    enabled = !busy && canPreviousChapter,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                    Text(stringResource(R.string.action_previous_chapter))
                }
                Text(
                    modifier = Modifier.weight(1f),
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EinkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onNextChapter,
                    enabled = !busy && canNextChapter,
                ) {
                    Text(stringResource(R.string.action_next_chapter))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
fun ReaderSearchPanel(
    query: String,
    resultCount: Int,
    selectedResultNumber: Int,
    selectedPreview: String?,
    busy: Boolean,
    onQueryChange: (String) -> Unit,
    onPreviousResult: () -> Unit,
    onNextResult: () -> Unit,
    onClearSearch: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPaper,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text(stringResource(R.string.field_search_book)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                )
                IconButton(
                    onClick = onClearSearch,
                    enabled = !busy && query.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_clear_search),
                        tint = EinkInk,
                    )
                }
            }
            Text(
                text = searchSummaryText(
                    query = query,
                    resultCount = resultCount,
                    selectedResultNumber = selectedResultNumber,
                ),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = EinkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onPreviousResult,
                    enabled = !busy && query.isNotBlank() && resultCount > 0,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                    Text(stringResource(R.string.action_previous_match))
                }
                TextButton(
                    onClick = onNextResult,
                    enabled = !busy && query.isNotBlank() && resultCount > 0,
                ) {
                    Text(stringResource(R.string.action_next_match))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
            selectedPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = EinkInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ReaderBookmarkPanel(
    bookmarks: List<ReaderBookmark>,
    currentPageIndex: Int,
    draftLabel: String,
    busy: Boolean,
    onDraftLabelChange: (String) -> Unit,
    onAddBookmark: () -> Unit,
    onOpenBookmark: (ReaderBookmark) -> Unit,
    onRemoveBookmark: (ReaderBookmark) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EinkPaper,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.bookmarks_title),
                style = MaterialTheme.typography.titleSmall,
                color = EinkInk,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draftLabel,
                    onValueChange = onDraftLabelChange,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text(stringResource(R.string.field_bookmark_name)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Bookmark, contentDescription = null)
                    },
                )
                Button(
                    onClick = onAddBookmark,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPaper),
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_add_bookmark))
                }
            }
            if (bookmarks.isEmpty()) {
                Text(
                    text = stringResource(R.string.bookmarks_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = EinkMuted,
                )
            } else {
                com.dongholab.pagetuner.ui.common.AdaptiveCollection(
                    items = bookmarks,
                    modifier = Modifier.weight(1f),
                    estimatedPagedItemHeight = 64.dp,
                    fallbackPageSize = 5,
                    busy = busy,
                ) { bookmark ->
                    ReaderBookmarkRow(
                        bookmark = bookmark,
                        selected = bookmark.pageIndex == currentPageIndex,
                        busy = busy,
                        onOpenBookmark = onOpenBookmark,
                        onRemoveBookmark = onRemoveBookmark,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderBookmarkRow(
    bookmark: ReaderBookmark,
    selected: Boolean,
    busy: Boolean,
    onOpenBookmark: (ReaderBookmark) -> Unit,
    onRemoveBookmark: (ReaderBookmark) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        color = if (selected) EinkSoft else EinkPaper,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, if (selected) EinkInk else EinkLine),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 4.dp, end = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onOpenBookmark(bookmark) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = bookmark.label ?: stringResource(
                            R.string.bookmark_page_label,
                            bookmark.pageIndex + 1,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = EinkInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.bookmark_page_label, bookmark.pageIndex + 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = EinkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = { onRemoveBookmark(bookmark) },
                enabled = !busy,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete_bookmark),
                    tint = EinkInk,
                )
            }
        }
    }
}

@Composable
fun ReaderAnnotationPanel(
    annotations: List<ReaderAnnotation>,
    currentPageIndex: Int,
    noteDraft: String,
    busy: Boolean,
    onNoteDraftChange: (String) -> Unit,
    onAddHighlight: () -> Unit,
    onAddNote: () -> Unit,
    onExportAnnotations: () -> Unit,
    onOpenAnnotation: (ReaderAnnotation) -> Unit,
    onRemoveAnnotation: (ReaderAnnotation) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EinkPaper,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.annotations_title),
                style = MaterialTheme.typography.titleSmall,
                color = EinkInk,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = noteDraft,
                onValueChange = onNoteDraftChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text(stringResource(R.string.field_note_text)) },
                leadingIcon = {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                },
                minLines = 1,
                maxLines = 3,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = onAddHighlight,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPaper),
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_add_highlight))
                }
                TextButton(
                    onClick = onAddNote,
                    enabled = !busy,
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_add_note))
                }
                TextButton(
                    onClick = onExportAnnotations,
                    enabled = !busy && annotations.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_export_annotations))
                }
            }
            if (annotations.isEmpty()) {
                Text(
                    text = stringResource(R.string.annotations_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = EinkMuted,
                )
            } else {
                com.dongholab.pagetuner.ui.common.AdaptiveCollection(
                    items = annotations.reversed(),
                    modifier = Modifier.weight(1f),
                    estimatedPagedItemHeight = 76.dp,
                    fallbackPageSize = 4,
                    busy = busy,
                ) { annotation ->
                    ReaderAnnotationRow(
                        annotation = annotation,
                        selected = annotation.pageIndex == currentPageIndex,
                        busy = busy,
                        onOpenAnnotation = onOpenAnnotation,
                        onRemoveAnnotation = onRemoveAnnotation,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderAnnotationRow(
    annotation: ReaderAnnotation,
    selected: Boolean,
    busy: Boolean,
    onOpenAnnotation: (ReaderAnnotation) -> Unit,
    onRemoveAnnotation: (ReaderAnnotation) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp),
        color = if (selected) EinkSoft else EinkPaper,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, if (selected) EinkInk else EinkLine),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 4.dp, end = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onOpenAnnotation(annotation) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            annotation.type.labelRes,
                            annotation.pageIndex + 1,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = EinkInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = annotation.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = EinkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = { onRemoveAnnotation(annotation) },
                enabled = !busy,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete_annotation),
                    tint = EinkInk,
                )
            }
        }
    }
}

private val ReaderAnnotationType.labelRes: Int
    get() = when (this) {
        ReaderAnnotationType.Highlight -> R.string.annotation_highlight_label
        ReaderAnnotationType.Note -> R.string.annotation_note_label
    }

@Composable
private fun searchSummaryText(
    query: String,
    resultCount: Int,
    selectedResultNumber: Int,
): String {
    return when {
        query.isBlank() -> stringResource(R.string.search_idle)
        resultCount == 0 -> stringResource(R.string.search_no_results)
        selectedResultNumber > 0 -> stringResource(
            R.string.search_result_position,
            selectedResultNumber,
            resultCount,
        )
        else -> stringResource(R.string.search_result_count, resultCount)
    }
}

@Composable
fun ReaderSurface(
    page: ReaderPage,
    documentFormat: DocumentFormat,
    pdfPageBitmap: Bitmap?,
    pdfFitMode: PdfFitMode,
    displayMode: DisplayMode,
    translation: PageTranslation?,
    glossaryEntries: List<BookGlossaryEntry> = emptyList(),
    translationDisplayMode: TranslationDisplayMode,
    pageTurnMode: PageTurnMode,
    pageTurningEnabled: Boolean,
    fontSizeSp: Int,
    lineSpacing: Float,
    pageMarginDp: Int,
    fontFamily: ReaderFontFamily = ReaderFontFamily.DEFAULT,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    fullScreen: Boolean = false,
    onExitFullscreen: () -> Unit = {},
    modifier: Modifier = Modifier,
    document: ReaderDocument? = null,
    displayPosition: ReaderDisplayPosition = ReaderDisplayPosition(page.index),
    pageChangeRevision: Long = 0,
    onDisplayNavigation: (ReaderDisplayNavigation) -> Unit = {},
    onDisplayPositionResolved: (ReaderDisplayPosition) -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxSize(), color = Color.White, contentColor = EinkInk,
        shape = if (fullScreen) RectangleShape else RoundedCornerShape(6.dp),
        border = if (fullScreen) null else BorderStroke(1.dp, EinkLine), shadowElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            ReaderMeasuredContent(
                document = document ?: ReaderDocument("reader-surface", "", documentFormat, listOf(page)),
                page = page, position = displayPosition, pageChangeRevision = pageChangeRevision,
                pdfPageBitmap = pdfPageBitmap, pdfFitMode = pdfFitMode, displayMode = displayMode,
                translation = translation, glossaryEntries = glossaryEntries,
                translationDisplayMode = translationDisplayMode, fontSizeSp = fontSizeSp,
                lineSpacing = lineSpacing, pageMarginDp = pageMarginDp,
                fontFamily = fontFamily,
                onNavigation = onDisplayNavigation, onPositionResolved = onDisplayPositionResolved,
            )
            PageTurnTapZones(pageTurnMode, pageTurningEnabled, onPreviousPage, onNextPage,
                onCenterTap = onExitFullscreen.takeIf { fullScreen })
        }
    }
}
@Composable
internal fun EmbeddedPageImage(
    image: ReaderPageImage,
    displayMode: DisplayMode,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(image.id, displayMode) {
        com.dongholab.pagetuner.display.decodeSampledBitmapFromByteArray(
            bytes = image.bytes,
            reqWidth = 800,
            reqHeight = 1200,
        )?.also { bitmap -> bitmap.applyDisplayMode(displayMode) }
    }

    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        if (bitmap == null) {
            Text(
                text = image.altText ?: stringResource(R.string.viewer_image_unavailable),
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = EinkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.altText,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

internal fun GlossaryDisplayText.toEmphasizedAnnotatedString(): AnnotatedString = buildAnnotatedString {
    append(text)
    emphasizedRanges.forEach { range ->
        if (range.first >= 0 && range.last < text.length && !range.isEmpty()) {
            addStyle(
                style = SpanStyle(fontWeight = FontWeight.Bold),
                start = range.first,
                end = range.last + 1,
            )
        }
    }
}

@Composable
fun PageTurnTapZones(
    pageTurnMode: PageTurnMode,
    enabled: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onCenterTap: (() -> Unit)? = null,
) {
    val pageTapEnabled = enabled && pageTurnMode != PageTurnMode.ButtonsOnly
    if (!pageTapEnabled && onCenterTap == null) return

    val leftAction: () -> Unit = when (pageTurnMode) {
        PageTurnMode.LeftPreviousRightNext -> onPreviousPage
        PageTurnMode.LeftNextRightPrevious -> onNextPage
        PageTurnMode.ButtonsOnly -> ({})
    }
    val rightAction: () -> Unit = when (pageTurnMode) {
        PageTurnMode.LeftPreviousRightNext -> onNextPage
        PageTurnMode.LeftNextRightPrevious -> onPreviousPage
        PageTurnMode.ButtonsOnly -> ({})
    }
    val leftInteraction = remember { MutableInteractionSource() }
    val centerInteraction = remember { MutableInteractionSource() }
    val rightInteraction = remember { MutableInteractionSource() }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(4f)
                .fillMaxSize()
                .clickable(
                    enabled = pageTapEnabled,
                    interactionSource = leftInteraction,
                    indication = null,
                    onClick = leftAction,
                ),
        )
        Box(
            modifier = Modifier
                .weight(2f)
                .fillMaxSize()
                .clickable(
                    enabled = onCenterTap != null,
                    interactionSource = centerInteraction,
                    indication = null,
                    onClick = { onCenterTap?.invoke() },
                ),
        )
        Box(
            modifier = Modifier
                .weight(4f)
                .fillMaxSize()
                .clickable(
                    enabled = pageTapEnabled,
                    interactionSource = rightInteraction,
                    indication = null,
                    onClick = rightAction,
                ),
        )
    }
}

@Composable
fun DocumentDetailsDialog(
    document: ReaderDocument,
    currentBook: LocalBook?,
    pageIndex: Int,
    onDismiss: () -> Unit,
) {
    val progress = currentBook?.readingProgressPercent
        ?: (((pageIndex + 1).toFloat() / document.pageCount.toFloat()) * 100f)
            .toInt()
            .coerceIn(0, 100)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.document_details_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.document_details_name, document.title))
                Text(stringResource(R.string.document_details_format, document.format.localizedName()))
                Text(stringResource(R.string.document_details_pages, document.pageCount))
                Text(stringResource(R.string.document_details_progress, progress))
                if (currentBook != null) {
                    Text(
                        stringResource(
                            R.string.document_details_size,
                            currentBook.fileSizeBytes.formatFileSize(),
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

private fun Long.formatFileSize(): String {
    val kb = this / 1024f
    val mb = kb / 1024f
    return if (mb >= 1f) {
        "%.1f MB".format(mb)
    } else {
        "%.1f KB".format(kb.coerceAtLeast(0.1f))
    }
}
