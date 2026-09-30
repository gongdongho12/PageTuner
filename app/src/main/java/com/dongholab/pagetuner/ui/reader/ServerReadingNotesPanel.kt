package com.dongholab.pagetuner.ui.reader

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.translation.sync.*
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkAutoFitText
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.theme.*

/** Server IDs and canonical ranges stay in the journal; page-only legacy/ZIP notes never enter this editor. */
@Composable
fun ServerReadingNotesPanel(receivedState: ServerReadingNotesUiState, document: ServerReadingDocument,
    bookmarksOnly: Boolean, pageIndex: Int, sync: ServerReadingNotesSync, onOpen: (ServerReadingAnchor) -> Unit) {
    val state = receivedState.takeIf { it.readerId == document.readerId } ?: ServerReadingNotesUiState(phase = ReadingProgressPhase.Loading)
    val context = LocalContext.current
    val shareLabel = stringResource(R.string.action_export_annotations)
    var selected by remember(document.openId, bookmarksOnly) { mutableStateOf<String?>(null) }
    var selectedSnapshot by remember(document.openId, bookmarksOnly) { mutableStateOf<ServerReadingNote?>(null) }
    var creating by remember(document.openId, bookmarksOnly) { mutableStateOf<ServerReadingNoteKind?>(null) }
    val entries = state.items.associateBy { it.noteId }
    val conflicts = state.conflicts.associateBy { it.noteId }
    val ids = (entries.keys + conflicts.keys).filter { id ->
        val kind = entries[id]?.note?.kind ?: conflicts[id]?.remote?.note?.kind
        kind == null || (kind == ServerReadingNoteKind.BOOKMARK) == bookmarksOnly
    }
    val selectedItem = selected?.let(entries::get) ?: selectedSnapshot?.takeIf { it.noteId == selected }
    val conflict = selected?.let(conflicts::get)
    val label = if (state.staleActionRejected) R.string.server_notes_stale_action else when (state.phase) {
        ReadingProgressPhase.Synced -> R.string.server_notes_synced
        ReadingProgressPhase.Pending -> R.string.server_notes_pending
        ReadingProgressPhase.Offline -> R.string.server_notes_offline
        ReadingProgressPhase.RateLimited -> R.string.server_notes_rate_limited
        ReadingProgressPhase.Conflict -> R.string.server_notes_conflict
        ReadingProgressPhase.Unavailable -> R.string.server_notes_unavailable
        ReadingProgressPhase.DeviceError -> R.string.server_notes_device_error
        else -> R.string.server_notes_loading
    }
    val enabled = state.readerId == document.readerId && state.phase != ReadingProgressPhase.DeviceError
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = EinkInk)
            if (state.phase in setOf(ReadingProgressPhase.Offline, ReadingProgressPhase.Unavailable, ReadingProgressPhase.RateLimited)) {
                TextButton(onClick = sync::retry, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.reading_progress_retry)) }
            }
        }
        if (selected != null && (selectedItem != null || conflict != null)) {
            NoteDetail(selected!!, selectedItem, conflict, enabled,
                onBack = { selected = null }, onOpen = onOpen,
                onEdit = { title, text, base -> sync.edit(document.readerId, selected!!, title, text, base) },
                onDelete = { base -> sync.delete(document.readerId, selected!!, base); selected = null },
                onResolve = { local -> conflict?.let { sync.resolve(document.readerId, selected!!, local, it) } })
        } else if (creating != null) {
            val kind = creating!!
            val title = stringResource(when (kind) {
                ServerReadingNoteKind.BOOKMARK -> R.string.bookmark_page_label
                ServerReadingNoteKind.NOTE -> R.string.annotation_note_label
                ServerReadingNoteKind.HIGHLIGHT -> R.string.annotation_highlight_label
            }, pageIndex + 1)
            NoteEditor(title, "", kind, enabled, { creating = null }) { name, text ->
                sync.create(document.readerId, kind, pageIndex, name, text); creating = null
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { creating = if (bookmarksOnly) ServerReadingNoteKind.BOOKMARK else ServerReadingNoteKind.NOTE },
                    enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                    Text(stringResource(if (bookmarksOnly) R.string.action_add_bookmark else R.string.action_add_note))
                }
                if (!bookmarksOnly) TextButton(onClick = { creating = ServerReadingNoteKind.HIGHLIGHT }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(stringResource(R.string.action_add_highlight)) }
                TextButton(enabled = ids.isNotEmpty(), modifier = Modifier.heightIn(min = 44.dp), onClick = {
                    val text = ids.mapNotNull(entries::get).joinToString("\n\n") { item ->
                        val note = item.note!!
                        "${note.title}\n${note.excerpt}\n${note.text}".trim()
                    }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, document.mapping.document.title); putExtra(Intent.EXTRA_TEXT, text)
                    }, shareLabel))
                }) { Text(stringResource(R.string.server_notes_share)) }
            }
            AdaptiveCollection(items = ids, estimatedPagedItemHeight = 76.dp, modifier = Modifier.weight(1f), itemKey = { it },
                emptyContent = { Text(stringResource(if (bookmarksOnly) R.string.bookmarks_empty else R.string.annotations_empty), color = EinkMuted) }) { id ->
                val note = entries[id]?.note ?: conflicts[id]?.remote?.note
                Row(Modifier.fillMaxWidth().height(76.dp).border(1.dp, EinkLine).background(EinkPaper), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).fillMaxHeight().clickable { note?.anchor?.let(onOpen) }.padding(8.dp), verticalArrangement = Arrangement.Center) {
                        Text(note?.title ?: stringResource(R.string.server_notes_deleted), maxLines = 1, overflow = TextOverflow.Ellipsis, color = EinkInk)
                        Text(when {
                            id in conflicts -> stringResource(R.string.server_notes_conflict)
                            id in state.pendingNoteIds -> stringResource(R.string.server_notes_pending)
                            else -> stringResource(R.string.bookmark_page_label, note?.let { document.page(it.anchor) + 1 } ?: 1)
                        }, maxLines = 1, color = EinkMuted, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { selectedSnapshot = entries[id]; selected = id }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.server_notes_details)) }
                }
            }
        }
    }
}

private enum class NoteTab { Details, Edit, Device, Account }

@Composable
private fun ColumnScope.NoteDetail(id: String, item: ServerReadingNote?, conflict: ServerReadingNoteConflictView?, enabled: Boolean,
    onBack: () -> Unit, onOpen: (ServerReadingAnchor) -> Unit, onEdit: (String, String, ServerReadingNote) -> Unit, onDelete: (ServerReadingNote?) -> Unit, onResolve: (Boolean) -> Unit) {
    val note = item?.note
    var tab by remember(id, conflict != null) { mutableStateOf(if (conflict == null) NoteTab.Details else NoteTab.Device) }
    var confirmingDelete by remember(id) { mutableStateOf(false) }
    var deleteBase by remember(id) { mutableStateOf<ServerReadingNote?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.server_notes_back)) }
        if (note != null) TextButton(onClick = { onOpen(note.anchor) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.action_open)) }
        Spacer(Modifier.weight(1f))
        if (note != null && conflict == null) TextButton(onClick = { deleteBase = item; confirmingDelete = true }, enabled = enabled,
            modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.action_delete_remote_source)) }
    }
    val tabs = if (conflict != null) listOf(NoteTab.Device, NoteTab.Account) else listOf(NoteTab.Details, NoteTab.Edit)
    val labels = mapOf(
        NoteTab.Details to stringResource(R.string.server_notes_details),
        NoteTab.Edit to stringResource(R.string.server_notes_edit),
        NoteTab.Device to stringResource(R.string.server_notes_device),
        NoteTab.Account to stringResource(R.string.server_notes_account),
    )
    EinkSegmentedControl(tabs, tab, { tab = it }) { option -> labels.getValue(option) }
    if (tab == NoteTab.Edit && note != null) {
        val base = remember(id) { requireNotNull(item) }
        val baseNote = requireNotNull(base.note)
        NoteEditor(baseNote.title, baseNote.text, baseNote.kind, enabled, onBack) { title, text -> onEdit(title, text, base); tab = NoteTab.Details }
    } else {
        val value = when (tab) { NoteTab.Device -> conflict?.local?.note; NoteTab.Account -> conflict?.remote?.note; else -> note }
        if (conflict != null) {
            Text(stringResource(if (conflict.remote.deleted && !conflict.local.deleted) R.string.server_notes_restore_warning else R.string.server_notes_conflict_detail),
                color = EinkInk, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { onResolve(tab == NoteTab.Device) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(stringResource(if (tab == NoteTab.Device) R.string.server_notes_use_device else R.string.server_notes_use_account))
            }
        }
        val content = value?.let { "${it.title}\n\n${it.excerpt}\n\n${it.text}" } ?: stringResource(R.string.server_notes_deleted)
        val blocks = remember(content) { noteTextBlocks(content) }
        AdaptiveCollection(items = blocks, estimatedPagedItemHeight = 100.dp, modifier = Modifier.weight(1f)) { text ->
            EinkAutoFitText(text, 16, 1.25f, Modifier.fillMaxWidth().height(100.dp).border(1.dp, EinkLine).padding(8.dp))
        }
    }
    if (confirmingDelete) AlertDialog(onDismissRequest = { confirmingDelete = false }, containerColor = EinkPanel,
        titleContentColor = EinkInk, textContentColor = EinkInk, title = { Text(stringResource(R.string.server_notes_delete_title)) },
        text = { Text(stringResource(R.string.server_notes_delete_detail)) },
        confirmButton = { TextButton(onClick = { confirmingDelete = false; onDelete(deleteBase) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.action_delete_remote_source)) } },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun ColumnScope.NoteEditor(initialTitle: String, initialText: String, kind: ServerReadingNoteKind, enabled: Boolean,
    onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember(initialTitle, initialText) { mutableStateOf(initialTitle) }
    var text by remember(initialTitle, initialText) { mutableStateOf(initialText) }
    OutlinedTextField(title, { if (it.length <= 200) title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(stringResource(R.string.server_notes_title)) })
    OutlinedTextField(text, { if (it.length <= 4000) text = it }, modifier = Modifier.fillMaxWidth().weight(1f),
        label = { Text(stringResource(R.string.field_note_text)) })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.action_cancel)) }
        TextButton(onClick = { onSave(title, text) }, enabled = enabled && title.isNotBlank() && (kind != ServerReadingNoteKind.NOTE || text.isNotBlank()),
            modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.action_save)) }
    }
}

/** Bounded, surrogate-safe detail pages also preserve explicit newlines. */
internal fun noteTextBlocks(text: String): List<String> {
    val result = mutableListOf<String>()
    var remaining = text
    while (remaining.isNotEmpty()) {
        val candidate = remaining.takeUtf16(120)
        val lines = candidate.indices.filter { candidate[it] == '\n' }
        val end = if (lines.size > 3) lines[3] + 1 else candidate.length
        result += remaining.substring(0, end)
        remaining = remaining.substring(end)
    }
    return result
}
