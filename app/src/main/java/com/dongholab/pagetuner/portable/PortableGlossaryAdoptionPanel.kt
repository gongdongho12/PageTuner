package com.dongholab.pagetuner.portable

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.core.backup.exchange.BookGlossarySnapshotPresence
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncEntry
import com.dongholab.pagetuner.translation.sync.*
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl

private enum class AdoptionSection { Scope, Zip, Account, Device, Confirm }
private data class AdoptionRow(val key: String, val content: @Composable (Modifier) -> Unit)

/** Every identity and entry field has a paged row; long text remains selectable instead of ellipsized. */
@Composable
fun PortableGlossaryAdoptionPanel(state: PortableGlossaryAdoptionState, accountLabel: String, current: Boolean,
    onBack: () -> Unit, onCompare: (Int) -> Unit, onConfirm: (String) -> Unit,
    syncContent: @Composable () -> Unit, modifier: Modifier = Modifier) {
    // Local sub-tabs and reader fullscreen can remove this panel without changing AppTab.Local.
    // Expire its comparison and release the confirmed actor target whenever it is no longer visible.
    val latestBack by rememberUpdatedState(onBack)
    DisposableEffect(Unit) { onDispose { latestBack() } }
    var section by remember(state.entry?.key, state.comparison?.id) { mutableStateOf(AdoptionSection.Scope) }
    val sectionLabels = AdoptionSection.entries.associateWith { stringResource(when (it) {
        AdoptionSection.Scope -> R.string.portable_glossary_scope
        AdoptionSection.Zip -> R.string.portable_glossary_zip
        AdoptionSection.Account -> R.string.portable_glossary_account
        AdoptionSection.Device -> R.string.portable_glossary_device
        AdoptionSection.Confirm -> R.string.portable_glossary_confirm
    }) }
    val rowHeight = 116.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val rows = mutableListOf<AdoptionRow>()
    fun text(key: String, label: Int, value: String) {
        rows += AdoptionRow(key) { row -> OutlinedTextField(value, {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(label)) }, modifier = row) }
    }
    fun message(key: String, label: Int) { rows += AdoptionRow(key) { row -> Text(stringResource(label), modifier = row) } }
    fun entries(value: List<BookGlossarySyncEntry>?) {
        text("entry_count", R.string.portable_glossary_entry_count, value?.size?.toString() ?: "null")
        value.orEmpty().forEachIndexed { index, entry ->
            text("$index:id", R.string.book_glossary_entry_id, "${index + 1}. ${entry.id}")
            text("$index:source", R.string.glossary_source_term, entry.sourceTerm)
            text("$index:translated", R.string.glossary_translation_term, entry.translatedTerm)
            text("$index:alias", R.string.glossary_display_alias, entry.displayTerm)
            text("$index:kind", R.string.book_glossary_kind, entry.kind.name)
            text("$index:case", R.string.book_glossary_case_sensitive, entry.caseSensitive.toString())
            text("$index:enabled", R.string.book_glossary_enabled, entry.enabled.toString())
        }
    }
    fun server(value: ServerBookGlossary?) {
        val label = when {
            value == null -> R.string.portable_glossary_no_device_record
            value.version == 0L -> R.string.portable_glossary_value_absent
            value.value.entries == null -> R.string.portable_glossary_value_deleted
            else -> R.string.portable_glossary_value_present
        }
        message("presence", label)
        value?.let { entries(it.value.entries?.map { entry -> entry.syncEntry() }) }
    }
    val shown = state.comparison.takeIf { current }
    if (!current) message("signed_out", R.string.portable_export_expired)
    else if (state.error != null) text("error", R.string.portable_glossary_review, state.error)
    if (state.busy) message("busy", R.string.portable_busy)
    if (current) when (section) {
        AdoptionSection.Scope -> {
            message("description", R.string.portable_glossary_adoption_description)
            text("account", R.string.portable_glossary_destination_account, accountLabel)
            if (state.snapshots.isEmpty()) message("empty", R.string.portable_glossary_no_snapshots)
            state.snapshots.forEachIndexed { index, snapshot ->
                text("$index:provider", R.string.source_favorites_provider_id, snapshot.identity.providerId)
                text("$index:book", R.string.source_favorites_book_id, snapshot.identity.bookId)
                text("$index:language", R.string.book_glossary_target_language, snapshot.identity.targetLanguage)
                rows += AdoptionRow("$index:compare") { row -> OutlinedButton(onClick = { onCompare(index) },
                    enabled = current && !state.busy, modifier = row.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_glossary_compare)) } }
            }
            if (shown != null) message("compared", R.string.portable_glossary_compared)
        }
        AdoptionSection.Zip -> shown?.snapshot?.let { snapshot ->
            message("presence", when (snapshot.presence) {
                BookGlossarySnapshotPresence.ABSENT -> R.string.portable_glossary_absent_info
                BookGlossarySnapshotPresence.DELETED -> R.string.portable_glossary_value_deleted
                BookGlossarySnapshotPresence.PRESENT -> R.string.portable_glossary_value_present
            }); entries(snapshot.entries)
        }
        AdoptionSection.Account -> { message("description", R.string.portable_glossary_fresh_server); server(shown?.remote) }
        AdoptionSection.Device -> {
            message("description", R.string.portable_glossary_device_cache)
            text("selected", R.string.portable_glossary_device_selected, shown?.device?.selected?.toString().orEmpty())
            val value = shown?.device?.local
            message("presence", when {
                value == null -> R.string.portable_glossary_no_device_record
                shown?.device?.remote?.version == 0L -> R.string.portable_glossary_value_absent
                value.entries == null -> R.string.portable_glossary_value_deleted
                else -> R.string.portable_glossary_value_present
            })
            value?.let { entries(it.entries?.map { entry -> entry.syncEntry() }) }
        }
        AdoptionSection.Confirm -> shown?.let { comparison ->
            text("account", R.string.portable_glossary_destination_account, accountLabel)
            text("provider", R.string.source_favorites_provider_id, comparison.snapshot.identity.providerId)
            text("book", R.string.source_favorites_book_id, comparison.snapshot.identity.bookId)
            text("language", R.string.book_glossary_target_language, comparison.snapshot.identity.targetLanguage)
            if (comparison.snapshot.presence == BookGlossarySnapshotPresence.ABSENT) message("absent", R.string.portable_glossary_absent_info)
            else {
                message("warning", R.string.portable_glossary_confirm_warning)
                rows += AdoptionRow("confirm") { row -> OutlinedButton(onClick = { onConfirm(comparison.id) },
                    enabled = current && !state.busy, modifier = row.heightIn(min = 44.dp)) { Text(stringResource(
                    if (comparison.snapshot.presence == BookGlossarySnapshotPresence.DELETED) R.string.portable_glossary_confirm_delete
                    else R.string.portable_glossary_confirm_replace)) } }
            }
        }
    }
    Column(modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_identity_back)) }
        if (state.committed && current) Box(Modifier.weight(1f).fillMaxWidth()) { syncContent() }
        else {
            if (shown != null) EinkSegmentedControl(AdoptionSection.entries, section, { section = it }, enabled = !state.busy,
                label = { sectionLabels.getValue(it) })
            AdaptiveCollection(items = rows, modifier = Modifier.weight(1f).clipToBounds(), estimatedPagedItemHeight = rowHeight,
                itemKey = { it.key }) { row -> row.content(Modifier.fillMaxWidth().height(rowHeight).clipToBounds().padding(6.dp)) }
        }
    }
}
