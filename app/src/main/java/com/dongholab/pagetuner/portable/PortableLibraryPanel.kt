package com.dongholab.pagetuner.portable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.theme.*

private sealed interface PortableRow {
    data class Native(val book: LocalBook) : PortableRow
    data class Imported(val entry: PortableLibraryEntry) : PortableRow
}

@Composable
fun PortableLibraryPanel(books: List<LocalBook>, currentBookId: String?, state: PortableLibraryState,
    onImport: () -> Unit, onNativeExport: (LocalBook, Boolean) -> Unit, onExport: (PortableLibraryEntry) -> Unit,
    onOpen: (PortableLibraryEntry, Boolean) -> Unit, modifier: Modifier = Modifier) {
    var imported by remember { mutableStateOf(true) }
    val importedLabel = stringResource(R.string.portable_imported_library)
    val nativeLabel = stringResource(R.string.portable_native_library)
    val rows: List<PortableRow> = if (imported) state.entries.map { PortableRow.Imported(it) } else books.map { PortableRow.Native(it) }
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
        AdaptiveCollection(items = rows, modifier = Modifier.weight(1f), estimatedPagedItemHeight = 124.dp,
            busy = state.busy, itemKey = { when (it) { is PortableRow.Native -> it.book.id; is PortableRow.Imported -> it.entry.key } },
            emptyContent = { Text(stringResource(R.string.portable_empty), color = EinkMuted) }) { row ->
            Surface(Modifier.fillMaxWidth().height(124.dp), color = EinkPanel, border = BorderStroke(1.dp, EinkLine), shadowElevation = 0.dp) {
                Column(Modifier.padding(8.dp)) {
                    Text(when (row) { is PortableRow.Native -> row.book.title; is PortableRow.Imported -> row.entry.document.bookTitle },
                        color = EinkInk, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (row is PortableRow.Imported) Text(row.entry.document.organization.folder.ifBlank { row.entry.document.language },
                        color = EinkMuted, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        when (row) {
                            is PortableRow.Native -> {
                                TextButton(onClick = { onNativeExport(row.book, false) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_export)) }
                                if (row.book.id == currentBookId && !row.book.contentIsTranslated) TextButton(onClick = { onNativeExport(row.book, true) }, enabled = !state.busy,
                                    modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_with_translation)) }
                            }
                            is PortableRow.Imported -> {
                                TextButton(onClick = { onOpen(row.entry, false) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_open)) }
                                TextButton(onClick = { onExport(row.entry) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_export)) }
                                if (row.entry.document.assets.any { it.role == "pdf" } && row.entry.document.paragraphs.isNotEmpty()) TextButton(onClick = { onOpen(row.entry, true) },
                                    enabled = !state.busy, modifier = Modifier.heightIn(min = 44.dp)) { Text("PDF") }
                            }
                        }
                    }
                }
            }
        }
    }
}
