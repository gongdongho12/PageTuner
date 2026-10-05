package com.dongholab.pagetuner.portable

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.theme.*

private sealed interface PortableRow {
    data class Native(val book: LocalBook) : PortableRow
    data class Imported(val entry: PortableLibraryEntry) : PortableRow
}
private val PortableRow.exportKey: String get() = when (this) {
    is PortableRow.Native -> "native:${book.id}"
    is PortableRow.Imported -> "portable:${entry.key}"
}
private data class ExportOption(val label: Int, val description: Int, val action: () -> Unit)

@Composable
fun PortableLibraryPanel(books: List<LocalBook>, currentBookId: String?, state: PortableLibraryState,
    onImport: () -> Unit, onNativeExport: (LocalBook, Boolean) -> Unit, onExport: (PortableLibraryEntry) -> Unit,
    onOpen: (PortableLibraryEntry, Boolean) -> Unit, onVerify: (PortableLibraryEntry) -> Unit,
    onFileExportSelection: (String?) -> Unit,
    onNativeFileExport: (LocalBook, PortableDocumentFileFormat) -> Unit,
    onFileExport: (PortableLibraryEntry, PortableDocumentFileFormat) -> Unit,
    modifier: Modifier = Modifier) {
    var imported by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf<PortableRow?>(null) }
    DisposableEffect(exporting?.exportKey) {
        onFileExportSelection(exporting?.exportKey)
        onDispose { onFileExportSelection(null) }
    }
    exporting?.let { selected ->
        BackHandler { exporting = null }
        val pdf = when (selected) {
            is PortableRow.Native -> selected.book.format == DocumentFormat.PDF
            is PortableRow.Imported -> selected.entry.document.assets.any { it.role == "pdf" }
        }
        val title = when (selected) { is PortableRow.Native -> selected.book.title; is PortableRow.Imported -> selected.entry.document.bookTitle }
        fun file(format: PortableDocumentFileFormat) = when (selected) {
            is PortableRow.Native -> onNativeFileExport(selected.book, format)
            is PortableRow.Imported -> onFileExport(selected.entry, format)
        }
        val options = mutableListOf(ExportOption(R.string.portable_export, R.string.document_file_zip_description) {
            when (selected) { is PortableRow.Native -> onNativeExport(selected.book, false); is PortableRow.Imported -> onExport(selected.entry) }
        })
        if (pdf) options += ExportOption(R.string.document_file_pdf, R.string.document_file_pdf_description) { file(PortableDocumentFileFormat.PDF) }
        else {
            options += ExportOption(R.string.document_file_txt, R.string.document_file_text_description) { file(PortableDocumentFileFormat.TXT) }
            options += ExportOption(R.string.document_file_markdown, R.string.document_file_text_description) { file(PortableDocumentFileFormat.MARKDOWN) }
        }
        if (selected is PortableRow.Native && selected.book.id == currentBookId && !selected.book.contentIsTranslated) {
            options += ExportOption(R.string.portable_with_translation, R.string.document_file_translation_description) { onNativeExport(selected.book, true) }
        }
        val exportRowHeight = 152.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporting = null }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.action_close)) }
                Text(title, color = EinkInk, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (state.busy) Text(stringResource(R.string.portable_busy), color = EinkInk)
            state.status?.let { Text(stringResource(it), color = EinkInk) }
            state.error?.let { Text(it, color = EinkInk, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            AdaptiveCollection(items = options, modifier = Modifier.weight(1f).clipToBounds(), estimatedPagedItemHeight = exportRowHeight,
                busy = state.busy, itemKey = { it.label }) { option ->
                Surface(Modifier.fillMaxWidth().height(exportRowHeight).clipToBounds(), color = EinkPanel,
                    border = BorderStroke(1.dp, EinkLine), shadowElevation = 0.dp) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(option.description), color = EinkMuted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = option.action, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                            Text(stringResource(option.label))
                        }
                    }
                }
            }
        }
        return
    }
    val importedLabel = stringResource(R.string.portable_imported_library)
    val nativeLabel = stringResource(R.string.portable_native_library)
    val rows: List<PortableRow> = if (imported) state.entries.map { PortableRow.Imported(it) } else books.map { PortableRow.Native(it) }
    val rowHeight = 168.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onImport, enabled = !state.busy, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_import)) }
            Text(stringResource(R.string.portable_summary), modifier = Modifier.weight(1f), color = EinkMuted, style = MaterialTheme.typography.bodySmall)
        }
        EinkSegmentedControl(listOf(false, true), imported, { imported = it }, enabled = !state.busy,
            label = { if (it) importedLabel else nativeLabel })
        if (state.busy) Text(stringResource(R.string.portable_busy), color = EinkInk)
        state.status?.let { Text(stringResource(it), color = EinkInk) }
        state.error?.let { Text(it, color = EinkInk, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        AdaptiveCollection(items = rows, modifier = Modifier.weight(1f).clipToBounds(), estimatedPagedItemHeight = rowHeight,
            busy = state.busy, itemKey = { when (it) { is PortableRow.Native -> it.book.id; is PortableRow.Imported -> it.entry.key } },
            emptyContent = { Text(stringResource(R.string.portable_empty), color = EinkMuted) }) { row ->
            Surface(Modifier.fillMaxWidth().height(rowHeight).clipToBounds(), color = EinkPanel, border = BorderStroke(1.dp, EinkLine), shadowElevation = 0.dp) {
                Column(Modifier.padding(8.dp)) {
                    Text(when (row) { is PortableRow.Native -> row.book.title; is PortableRow.Imported -> row.entry.document.bookTitle },
                        color = EinkInk, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (row is PortableRow.Imported) Text(row.entry.document.organization.folder.ifBlank { row.entry.document.language },
                        color = EinkMuted, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        when (row) {
                            is PortableRow.Native -> {
                                TextButton(onClick = { exporting = row }, enabled = !state.busy, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.document_file_export)) }
                            }
                            is PortableRow.Imported -> {
                                TextButton(onClick = { onOpen(row.entry, false) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_open)) }
                                TextButton(onClick = { exporting = row }, enabled = !state.busy, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.document_file_export)) }
                            }
                        }
                    }
                    if (row is PortableRow.Imported) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { onVerify(row.entry) }, enabled = !state.busy,
                            modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(
                                if (row.entry.document.assets.any { it.role == "pdf" }) R.string.pdf_storage_manage else R.string.portable_identity_check)) }
                        if (row.entry.document.assets.any { it.role == "pdf" } && row.entry.document.paragraphs.isNotEmpty()) TextButton(onClick = { onOpen(row.entry, true) },
                            enabled = !state.busy, modifier = Modifier.heightIn(min = 44.dp)) { Text("PDF") }
                    }
                }
            }
        }
    }
}
