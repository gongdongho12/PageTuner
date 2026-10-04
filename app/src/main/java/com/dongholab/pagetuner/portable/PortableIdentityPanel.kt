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
import com.dongholab.pagetuner.core.backup.exchange.DocumentIdentityKind
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncValidation
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkMuted

private data class IdentityRow(val key: String, val content: @Composable (Modifier) -> Unit)

@Composable
fun PortableIdentityPanel(state: PortableIdentityState, connected: Boolean, currentSession: Boolean,
    onBack: () -> Unit, onRecord: (String) -> Unit, onCheck: () -> Unit, onBind: () -> Unit = {}, onReadServer: () -> Unit = {}, onUnbind: () -> Unit = {},
    exportState: PortableLibraryState = PortableLibraryState(), onExportGlossary: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val rowHeight = 116.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    var glossaryLanguage by remember(state.entry?.key, state.session, state.identity) { mutableStateOf(state.identity?.targetLanguage ?: "ko") }
    val exportEnabled = connected && currentSession && !state.busy && !exportState.busy && state.identity?.let { identity ->
        runCatching { BookGlossarySyncValidation.validateIdentity(BookGlossarySyncIdentity(identity.contentProviderId, identity.bookId, glossaryLanguage)) }.isSuccess
    } == true
    val rows = mutableListOf<IdentityRow>()
    fun field(key: String, label: Int, value: String) {
        rows += IdentityRow(key) { row -> OutlinedTextField(value, {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(label)) }, modifier = row) }
    }
    rows += IdentityRow("description") { row -> Text(stringResource(R.string.portable_identity_description), modifier = row, color = EinkMuted) }
    rows += IdentityRow("status") { row -> Text(stringResource(if (!currentSession) R.string.portable_identity_sign_in else state.message), modifier = row, color = EinkInk) }
    field("book", R.string.portable_identity_book, state.entry?.document?.bookTitle.orEmpty())
    rows += IdentityRow("record") { row -> OutlinedTextField(state.recordId, onRecord, singleLine = true,
        label = { Text(stringResource(R.string.portable_identity_record)) }, enabled = currentSession && !state.busy,
        supportingText = { Text(stringResource(R.string.portable_identity_record_hint)) }, modifier = row) }
    rows += IdentityRow("verify") { row -> OutlinedButton(onClick = onCheck,
        enabled = connected && currentSession && !state.busy && state.identity != null, modifier = row.heightIn(min = 44.dp)) {
        Text(stringResource(R.string.portable_identity_check))
    } }
    rows += IdentityRow("bind") { row -> OutlinedButton(onClick = onBind,
        enabled = connected && currentSession && !state.busy && state.verified, modifier = row.heightIn(min = 44.dp)) {
        Text(stringResource(R.string.portable_identity_bind))
    } }
    rows += IdentityRow("read_server") { row -> OutlinedButton(onClick = onReadServer,
        enabled = connected && currentSession && !state.busy && state.identity != null, modifier = row.heightIn(min = 44.dp)) {
        Text(stringResource(R.string.portable_identity_read_server))
    } }
    rows += IdentityRow("unbind") { row -> OutlinedButton(onClick = onUnbind,
        enabled = connected && currentSession && !state.busy, modifier = row.heightIn(min = 44.dp)) {
        Text(stringResource(R.string.portable_identity_unbind))
    } }
    rows += IdentityRow("glossary_export_description") { row -> Text(stringResource(R.string.portable_glossary_export_description), modifier = row, color = EinkMuted) }
    rows += IdentityRow("glossary_export_replace") { row -> Text(stringResource(R.string.portable_glossary_export_replace), modifier = row, color = EinkMuted) }
    rows += IdentityRow("glossary_export_language") { row -> OutlinedTextField(glossaryLanguage, { glossaryLanguage = it },
        label = { Text(stringResource(R.string.portable_glossary_export_language)) }, singleLine = true,
        readOnly = state.identity?.kind == DocumentIdentityKind.TRANSLATION,
        enabled = currentSession && !state.busy && !exportState.busy, modifier = row) }
    rows += IdentityRow("glossary_export") { row -> OutlinedButton(onClick = { onExportGlossary(glossaryLanguage) },
        enabled = exportEnabled, modifier = row.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_glossary_export)) } }
    val exportStatus = when {
        exportState.busy -> stringResource(R.string.portable_busy)
        exportState.error != null -> exportState.error
        exportState.status != null -> stringResource(exportState.status)
        exportState.glossaryPresence != null -> stringResource(when (exportState.glossaryPresence) {
            BookGlossarySnapshotPresence.ABSENT -> R.string.portable_glossary_absent
            BookGlossarySnapshotPresence.DELETED -> R.string.portable_glossary_deleted
            BookGlossarySnapshotPresence.PRESENT -> R.string.portable_glossary_present
        })
        else -> null
    }
    exportStatus?.let { text -> rows += IdentityRow("glossary_export_status") { row -> Text(text, modifier = row, color = EinkInk) } }
    state.identity?.let { identity ->
        field("kind", R.string.portable_identity_kind, identity.kind.name)
        field("provider", R.string.portable_identity_provider, identity.contentProviderId)
        field("source_book", R.string.portable_identity_source_book, identity.bookId)
        field("chapter", R.string.portable_identity_chapter, identity.chapterId)
        field("source_language", R.string.portable_identity_source_language, identity.sourceLanguage)
        identity.targetLanguage?.let { field("target_language", R.string.portable_identity_target_language, it) }
        field("revision", R.string.portable_identity_revision, identity.sourceRevision)
        field("hash", R.string.portable_identity_hash, identity.paragraphHash)
    }
    Column(modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_identity_back)) }
        AdaptiveCollection(items = rows, modifier = Modifier.weight(1f).clipToBounds(),
            estimatedPagedItemHeight = rowHeight, itemKey = { it.key }) { row ->
            row.content(Modifier.fillMaxWidth().height(rowHeight).clipToBounds().padding(6.dp))
        }
    }
}
