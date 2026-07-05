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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.translation.TranslationDisplayMode
import com.dongholab.pagetuner.translation.PageTranslation
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = EinkInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.format_page_count,
                    document.format.localizedName(),
                    page.index + 1,
                    document.pageCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = EinkMuted,
            )
            page.chapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    text = stringResource(R.string.chapter_label, title),
                    style = MaterialTheme.typography.bodySmall,
                    color = EinkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                enabled = !busy && pageIndex > 0,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                Text(stringResource(R.string.action_previous))
            }
            Text(
                text = "${pageIndex + 1} / $pageCount",
                style = MaterialTheme.typography.titleMedium,
                color = EinkInk,
                fontFamily = FontFamily.Monospace,
            )
            TextButton(
                onClick = onNext,
                enabled = !busy && pageIndex < pageCount - 1,
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
fun ReaderSurface(
    page: ReaderPage,
    documentFormat: DocumentFormat,
    pdfPageBitmap: Bitmap?,
    pdfFitMode: PdfFitMode,
    displayMode: DisplayMode,
    translation: PageTranslation?,
    translationDisplayMode: TranslationDisplayMode,
    pageTurnMode: PageTurnMode,
    pageTurningEnabled: Boolean,
    fontSizeSp: Int,
    lineSpacing: Float,
    pageMarginDp: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showOriginal = translation == null || translationDisplayMode != TranslationDisplayMode.TranslationOnly
    val showTranslation = translation != null && translationDisplayMode != TranslationDisplayMode.OriginalOnly

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White,
        contentColor = EinkInk,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (pdfPageBitmap != null) {
                if (showOriginal) {
                    Image(
                        bitmap = pdfPageBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        contentScale = when (pdfFitMode) {
                            PdfFitMode.FitPage -> ContentScale.Fit
                            PdfFitMode.FitWidth -> ContentScale.FillWidth
                        },
                    )
                }
                if (translation != null && showTranslation) {
                    TranslationPanel(
                        translation = translation,
                        fontSizeSp = fontSizeSp,
                        lineSpacing = lineSpacing,
                        modifier = Modifier
                            .align(
                                if (showOriginal) {
                                    Alignment.BottomCenter
                                } else {
                                    Alignment.Center
                                },
                            )
                            .fillMaxWidth()
                            .padding(12.dp),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(pageMarginDp.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (showOriginal) {
                        OriginalPageContent(
                            page = page,
                            documentFormat = documentFormat,
                            displayMode = displayMode,
                            fontSizeSp = fontSizeSp,
                            lineSpacing = lineSpacing,
                            modifier = Modifier.weight(
                                if (showTranslation) {
                                    0.55f
                                } else {
                                    1f
                                },
                            ),
                        )
                    }
                    if (translation != null && showTranslation) {
                        TranslationPanel(
                            translation = translation,
                            fontSizeSp = fontSizeSp,
                            lineSpacing = lineSpacing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(
                                    if (showOriginal) {
                                        0.45f
                                    } else {
                                        1f
                                    },
                                ),
                        )
                    }
                }
            }
            PageTurnTapZones(
                pageTurnMode = pageTurnMode,
                enabled = pageTurningEnabled,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
            )
        }
    }
}

@Composable
private fun OriginalPageContent(
    page: ReaderPage,
    documentFormat: DocumentFormat,
    displayMode: DisplayMode,
    fontSizeSp: Int,
    lineSpacing: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = page.plainText.ifBlank {
                if (documentFormat == DocumentFormat.PDF) {
                    stringResource(R.string.viewer_pdf_rendering)
                } else {
                    stringResource(R.string.viewer_no_text)
                }
            },
            modifier = Modifier.weight(if (page.images.isEmpty()) 1f else 0.7f),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSizeSp.sp),
            color = EinkInk,
            lineHeight = (fontSizeSp * lineSpacing).sp,
            overflow = TextOverflow.Ellipsis,
        )
        page.images.take(2).forEach { image ->
            EmbeddedPageImage(
                image = image,
                displayMode = displayMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f),
            )
        }
    }
}

@Composable
private fun EmbeddedPageImage(
    image: ReaderPageImage,
    displayMode: DisplayMode,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(image.id, displayMode) {
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
            ?.copy(Bitmap.Config.ARGB_8888, true)
            ?.also { bitmap -> bitmap.applyDisplayMode(displayMode) }
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

@Composable
private fun TranslationPanel(
    translation: PageTranslation,
    fontSizeSp: Int,
    lineSpacing: Float,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = EinkSoft,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.saved_translation_title),
                style = MaterialTheme.typography.titleMedium,
                color = EinkInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = translation.text.ifBlank {
                    stringResource(R.string.translation_preparing)
                },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSizeSp.sp),
                color = EinkInk,
                lineHeight = (fontSizeSp * lineSpacing).sp,
                overflow = TextOverflow.Ellipsis,
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
) {
    if (!enabled || pageTurnMode == PageTurnMode.ButtonsOnly) return

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
    val rightInteraction = remember { MutableInteractionSource() }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .clickable(
                    interactionSource = leftInteraction,
                    indication = null,
                    onClick = leftAction,
                ),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .clickable(
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
