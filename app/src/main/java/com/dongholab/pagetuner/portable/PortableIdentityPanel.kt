package com.dongholab.pagetuner.portable

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkMuted

private data class IdentityRow(val key: String, val content: @Composable (Modifier) -> Unit)

@Composable
fun PortableIdentityPanel(state: PortableIdentityState, connected: Boolean, currentSession: Boolean,
    onBack: () -> Unit, onRecord: (String) -> Unit, onCheck: () -> Unit, modifier: Modifier = Modifier) {
    val rowHeight = 116.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
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
