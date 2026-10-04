package com.dongholab.pagetuner.ui.translation

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
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import com.dongholab.pagetuner.translation.glossary.BookGlossaryStore
import com.dongholab.pagetuner.translation.glossary.GlossaryTermKind
import com.dongholab.pagetuner.translation.sync.*
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class GlossaryPanelRow(val key: String, val content: @Composable (Modifier) -> Unit)
private data class GlossaryEntryDraft(val entry: BookGlossaryEntry, val original: BookGlossaryEntry?, val base: BookGlossaryEditBase)
private data class GlossarySubmission(val value: BookGlossaryValue, val session: Long, val beforeRevision: Long)
private enum class GlossarySection { Account, Device, Sync }

/** One bounded collection keeps long identities, ordered entries and both conflict versions reachable. */
@Composable
fun ServerBookGlossaryPanel(state: BookGlossaryUiState, sync: ServerBookGlossarySync, books: List<LocalBook>,
    deviceStore: BookGlossaryStore, modifier: Modifier = Modifier) {
    var section by remember(state.target) { mutableStateOf(GlossarySection.Account) }
    var draft by remember(state.target) { mutableStateOf<GlossaryEntryDraft?>(null) }
    var submitted by remember(state.target) { mutableStateOf<GlossarySubmission?>(null) }
    var invalid by remember(state.target) { mutableStateOf(false) }
    var confirmingDelete by remember(state.target) { mutableStateOf<BookGlossaryEditBase?>(null) }
    var remoteSide by remember(state.target) { mutableStateOf(false) }
    var adoption by remember(state.target) { mutableStateOf<Pair<BookGlossaryValue, BookGlossaryEditBase>?>(null) }
    var loadingDevice by remember(state.target) { mutableStateOf(false) }
    val latest by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    val labels = listOf(R.string.book_glossary_account, R.string.book_glossary_device, R.string.book_glossary_sync).map { stringResource(it) }
    val rowHeight = 116.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val ready = state.target != null && state.base.remote != null && state.phase != ReadingProgressPhase.DeviceError && state.choice == null
    // A previous failure flag is not the result of a new submission. Consume only its later actor result.
    LaunchedEffect(submitted, state.editRevision, state.session, state.phase) {
        val pending = submitted ?: return@LaunchedEffect
        if (state.session != pending.session) submitted = null
        else if (state.editRevision > pending.beforeRevision) {
            if (state.phase != ReadingProgressPhase.DeviceError && state.base.local == pending.value) draft = null
            submitted = null
        }
    }
    val rows = mutableListOf<GlossaryPanelRow>()
    fun text(key: String, label: Int, value: String) {
        rows += GlossaryPanelRow(key) { row -> OutlinedTextField(value, {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(label)) }, modifier = row) }
    }
    fun action(key: String, label: Int, enabled: Boolean = ready, perform: () -> Unit) {
        rows += GlossaryPanelRow(key) { row -> OutlinedButton(onClick = perform, enabled = enabled, modifier = row) { Text(stringResource(label)) } }
    }
    fun entryFields(prefix: String, entry: BookGlossaryEntry) {
        text("$prefix:id", R.string.book_glossary_entry_id, entry.id)
        text("$prefix:source", R.string.glossary_source_term, entry.sourceTerm)
        text("$prefix:translation", R.string.glossary_translation_term, entry.translatedTerm)
        text("$prefix:display", R.string.glossary_display_alias, entry.displayTerm)
        text("$prefix:kind", R.string.book_glossary_kind, entry.kind.name)
        text("$prefix:case", R.string.book_glossary_case_sensitive, entry.caseSensitive.toString())
        text("$prefix:enabled", R.string.book_glossary_enabled, entry.enabled.toString())
    }
    val status = when {
        invalid || state.invalidInput -> R.string.book_glossary_invalid
        state.staleActionRejected -> R.string.book_glossary_stale
        else -> when (state.phase) {
            ReadingProgressPhase.Inactive -> R.string.book_glossary_unavailable
            ReadingProgressPhase.Loading -> R.string.book_glossary_loading
            ReadingProgressPhase.Synced -> if (state.selected) R.string.book_glossary_synced else R.string.book_glossary_select_required
            ReadingProgressPhase.Pending -> R.string.book_glossary_pending
            ReadingProgressPhase.Offline -> R.string.book_glossary_offline
            ReadingProgressPhase.RateLimited -> R.string.book_glossary_rate_limited
            ReadingProgressPhase.Conflict -> R.string.book_glossary_conflict
            ReadingProgressPhase.Unavailable -> R.string.book_glossary_unavailable
            ReadingProgressPhase.DeviceError -> R.string.book_glossary_device_error
        }
    }
    rows += GlossaryPanelRow("status") { row -> Column(row) {
        Text(stringResource(status), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.book_glossary_pending_count, state.pendingDocuments), style = MaterialTheme.typography.bodySmall)
    } }
    val editing = draft
    if (editing != null) {
        action("back", R.string.book_glossary_back, true) { draft = null; submitted = null; invalid = false }
        text("id", R.string.book_glossary_entry_id, editing.entry.id)
        listOf(R.string.glossary_source_term, R.string.glossary_translation_term, R.string.glossary_display_alias).forEachIndexed { index, label ->
            val value = when (index) { 0 -> editing.entry.sourceTerm; 1 -> editing.entry.translatedTerm; else -> editing.entry.displayTerm }
            rows += GlossaryPanelRow("edit:$index") { row -> OutlinedTextField(value, { changed ->
                draft = editing.copy(entry = when (index) {
                    0 -> editing.entry.copy(sourceTerm = changed)
                    1 -> editing.entry.copy(translatedTerm = changed)
                    else -> editing.entry.copy(displayTerm = changed)
                }); invalid = false
            }, label = { Text(stringResource(label)) }, singleLine = true, readOnly = !ready || submitted != null, modifier = row) }
        }
        rows += GlossaryPanelRow("kind") { row -> Column(row) {
            Text(stringResource(R.string.book_glossary_kind))
            EinkSegmentedControl(GlossaryTermKind.entries, editing.entry.kind,
                { draft = editing.copy(entry = editing.entry.copy(kind = it)) }, enabled = ready && submitted == null, label = { it.name })
        } }
        rows += GlossaryPanelRow("flags") { row -> Column(row) {
            TextButton(onClick = { draft = editing.copy(entry = editing.entry.copy(caseSensitive = !editing.entry.caseSensitive)) },
                enabled = ready && submitted == null, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(stringResource(R.string.book_glossary_flag, stringResource(R.string.book_glossary_case_sensitive), editing.entry.caseSensitive.toString()))
            }
            TextButton(onClick = { draft = editing.copy(entry = editing.entry.copy(enabled = !editing.entry.enabled)) },
                enabled = ready && submitted == null, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(stringResource(R.string.book_glossary_flag, stringResource(R.string.book_glossary_enabled), editing.entry.enabled.toString()))
            }
        } }
        action("save", R.string.action_save, ready && submitted == null) {
            val entries = editing.base.local?.entries.orEmpty().toMutableList()
            val index = entries.indexOfFirst { it.id == editing.entry.id }
            if (editing.original == null) entries += editing.entry else if (index >= 0) entries[index] = editing.entry
            val value = BookGlossaryValue(entries)
            if (runCatching { ServerBookGlossaryJson.validateRequest(requireNotNull(state.target).identity, value) }.isSuccess) {
                submitted = GlossarySubmission(value, state.session, state.editRevision); sync.edit(state.session, value, editing.base)
            } else invalid = true
        }
        if (editing.original != null) action("deleteEntry", R.string.glossary_delete) { confirmingDelete = editing.base }
    } else when (section) {
        GlossarySection.Account -> {
            state.target?.identity?.let { identity ->
                text("provider", R.string.source_favorites_provider_id, identity.providerId)
                text("book", R.string.source_favorites_book_id, identity.bookId)
                text("language", R.string.book_glossary_target_language, identity.targetLanguage)
            }
            if (!state.selected) action("select", R.string.book_glossary_use_account) { sync.selectAccount(state.session, state.base) }
            else action("device", R.string.book_glossary_use_device) { sync.selectDevice(state.session, state.base) }
            action("add", R.string.glossary_add) {
                draft = GlossaryEntryDraft(BookGlossaryEntry(java.util.UUID.randomUUID().toString(), "", ""), null, state.base)
            }
            val entries = state.base.local?.entries
            if (entries == null) rows += GlossaryPanelRow("empty") { row -> Text(stringResource(R.string.book_glossary_deleted), modifier = row) }
            entries.orEmpty().forEachIndexed { index, entry -> rows += GlossaryPanelRow("entry:${entry.id}") { row ->
                OutlinedButton(onClick = { draft = GlossaryEntryDraft(entry, entry, state.base) }, enabled = ready, modifier = row) {
                    Column { Text("${index + 1}. ${entry.sourceTerm}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.translatedTerm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.displayTerm, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            } }
        }
        GlossarySection.Device -> {
            rows += GlossaryPanelRow("scope") { row -> Text(stringResource(R.string.book_glossary_adopt_scope), modifier = row) }
            if (adoption == null) books.forEach { book -> rows += GlossaryPanelRow("local:${book.id}") { row ->
                OutlinedButton(onClick = {
                    val shown = state; loadingDevice = true; invalid = false
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) {
                            BookGlossaryValue(deviceStore.load(book.id).entries).also { ServerBookGlossaryJson.validateRequest(requireNotNull(shown.target).identity, it) }
                        } }
                        if (latest.target == shown.target && latest.session == shown.session) {
                            loadingDevice = false
                            result.onSuccess { adoption = it to shown.base }.onFailure { invalid = true }
                        }
                    }
                }, enabled = ready && !loadingDevice, modifier = row) {
                    Column { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(book.id, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            } } else {
                val chosen = requireNotNull(adoption)
                action("adopt", R.string.book_glossary_confirm_adoption) { sync.adopt(state.session, chosen.first, chosen.second); adoption = null; section = GlossarySection.Sync }
                action("cancel", R.string.action_cancel, true) { adoption = null }
                chosen.first.entries.orEmpty().forEachIndexed { index, entry -> entryFields("adopt:$index", entry) }
            }
        }
        GlossarySection.Sync -> {
            action("retry", R.string.source_favorites_retry, state.target != null) { sync.retry(); invalid = false }
            val choice = state.choice
            if (choice != null) {
                rows += GlossaryPanelRow("side") { row -> Column(row) {
                    Text(stringResource(R.string.book_glossary_inspect_both))
                    val sideLabels = listOf(stringResource(R.string.book_glossary_pending_version), stringResource(R.string.book_glossary_account))
                    EinkSegmentedControl(listOf(false, true), remoteSide, { remoteSide = it }, label = { sideLabels[if (it) 1 else 0] })
                } }
                action("localChoice", R.string.book_glossary_use_pending, state.phase != ReadingProgressPhase.DeviceError && choice.remote.version < MaxReadingVersion) { sync.choose(state.session, true, choice) }
                action("remoteChoice", R.string.book_glossary_use_account, state.phase != ReadingProgressPhase.DeviceError) { sync.choose(state.session, false, choice) }
                val selected = if (remoteSide) choice.remote.value else choice.local
                if (selected.entries == null) rows += GlossaryPanelRow("deleted") { row -> Text(stringResource(R.string.book_glossary_deleted), modifier = row) }
                else if (selected.entries.isEmpty()) rows += GlossaryPanelRow("empty") { row -> Text(stringResource(R.string.glossary_empty), modifier = row) }
                selected.entries.orEmpty().forEachIndexed { index, entry -> entryFields("conflict:$remoteSide:$index", entry) }
            } else action("delete", R.string.book_glossary_delete_account) { confirmingDelete = state.base }
        }
    }
    Column(modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (editing == null) EinkSegmentedControl(GlossarySection.entries, section, { section = it; invalid = false },
            itemHeight = 46.dp * LocalDensity.current.fontScale.coerceAtLeast(1f), label = { labels[it.ordinal] })
        key(state.target, section, editing?.entry?.id) {
            AdaptiveCollection(rows, modifier = Modifier.weight(1f), estimatedPagedItemHeight = rowHeight, fallbackPageSize = 3,
                itemKey = { it.key }) { row -> row.content(Modifier.fillMaxWidth().height(rowHeight)) }
        }
    }
    confirmingDelete?.let { base -> AlertDialog(onDismissRequest = { confirmingDelete = null },
        title = { Text(stringResource(R.string.book_glossary_confirm_delete)) },
        text = { Text(stringResource(if (editing != null) R.string.book_glossary_delete_entry_scope else R.string.book_glossary_delete_scope)) },
        confirmButton = { TextButton(onClick = {
            val value = if (editing == null) BookGlossaryValue(null) else BookGlossaryValue(base.local?.entries.orEmpty().filterNot { it.id == editing.entry.id })
            submitted = GlossarySubmission(value, state.session, state.editRevision); sync.edit(state.session, value, base); confirmingDelete = null
        }) { Text(stringResource(R.string.glossary_delete)) } },
        dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}
