package com.dongholab.pagetuner.portable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.core.backup.exchange.DocumentIdentity
import com.dongholab.pagetuner.core.backup.exchange.LibraryOrganizationSnapshotPresence
import com.dongholab.pagetuner.translation.sync.LibraryOrganization
import com.dongholab.pagetuner.translation.sync.ServerLibraryOrganization
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkOperationIndicator
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.common.rememberEinkPagingState
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper

private enum class OrganizationAdoptionSection { Scope, Zip, Account, Device, Confirm }
private data class OrganizationAdoptionRow(val key: String, val content: @Composable (Modifier) -> Unit)

/** A passive comparison never applies account data; the final action is always separate. */
@Composable
internal fun PortableOrganizationAdoptionPanel(
    comparison: PortableOrganizationComparison?,
    accountLabel: String,
    current: Boolean,
    busy: Boolean,
    committed: Boolean,
    error: String?,
    sessionKey: Any,
    onBack: () -> Unit,
    onCompare: () -> Unit,
    onConfirm: (String) -> Unit,
    onDisposeCaptured: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Capture cleanup for this exact lease. An old disposal must never close a replacement session.
    DisposableEffect(sessionKey) {
        val closeCaptured = onDisposeCaptured
        onDispose { closeCaptured() }
    }
    val shown = comparison.takeIf { current }
    var section by remember(sessionKey, shown?.id) { mutableStateOf(OrganizationAdoptionSection.Scope) }
    val paging = rememberEinkPagingState(sessionKey, shown?.id, section, current, busy, committed, error)
    val labels = OrganizationAdoptionSection.entries.associateWith { stringResource(when (it) {
        OrganizationAdoptionSection.Scope -> R.string.portable_organization_scope
        OrganizationAdoptionSection.Zip -> R.string.portable_organization_zip
        OrganizationAdoptionSection.Account -> R.string.portable_organization_account
        OrganizationAdoptionSection.Device -> R.string.portable_organization_device
        OrganizationAdoptionSection.Confirm -> R.string.portable_organization_confirm
    }) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val rowHeight = 128.dp * fontScale
    val rows = mutableListOf<OrganizationAdoptionRow>()
    fun field(key: String, label: Int, value: String?) {
        rows += OrganizationAdoptionRow(key) { row ->
            // Read-only single-line fields keep the exact string selectable, including empty values.
            OutlinedTextField(value ?: stringResource(R.string.portable_organization_not_recorded), {},
                readOnly = true, singleLine = true, label = { Text(stringResource(label), color = EinkInk) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = EinkInk,
                    unfocusedTextColor = EinkInk, focusedBorderColor = EinkInk, unfocusedBorderColor = EinkLine),
                modifier = row)
        }
    }
    fun message(key: String, label: Int) {
        rows += OrganizationAdoptionRow(key) { row -> Text(stringResource(label), color = EinkInk, modifier = row) }
    }
    fun flag(key: String, label: Int, value: Boolean) {
        rows += OrganizationAdoptionRow(key) { row ->
            OutlinedTextField(stringResource(if (value) R.string.portable_organization_yes else R.string.portable_organization_no), {},
                readOnly = true, singleLine = true, label = { Text(stringResource(label), color = EinkInk) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = EinkInk,
                    unfocusedTextColor = EinkInk, focusedBorderColor = EinkInk, unfocusedBorderColor = EinkLine),
                modifier = row)
        }
    }
    fun identity(value: DocumentIdentity) {
        field("identity:version", R.string.portable_organization_identity_version, value.version.toString())
        field("identity:kind", R.string.portable_identity_kind, value.kind.name)
        field("identity:provider", R.string.portable_identity_provider, value.contentProviderId)
        field("identity:book", R.string.portable_identity_source_book, value.bookId)
        field("identity:chapter", R.string.portable_identity_chapter, value.chapterId)
        field("identity:sourceLanguage", R.string.portable_identity_source_language, value.sourceLanguage)
        field("identity:sourceRevision", R.string.portable_identity_revision, value.sourceRevision)
        field("identity:paragraphHash", R.string.portable_identity_hash, value.paragraphHash)
        field("identity:targetLanguage", R.string.portable_identity_target_language, value.targetLanguage)
        field("identity:translationProvider", R.string.portable_organization_translation_provider, value.translationProviderId)
        field("identity:model", R.string.portable_organization_model, value.modelId)
        field("identity:prompt", R.string.portable_organization_prompt_revision, value.promptRevision)
        field("identity:glossary", R.string.portable_organization_glossary_revision, value.glossaryRevision)
        field("identity:artifact", R.string.portable_organization_artifact_id, value.artifactId)
        field("identity:revision", R.string.portable_organization_translation_revision, value.revision)
        field("identity:payloadHash", R.string.portable_organization_payload_hash, value.payloadHash)
    }
    fun organization(prefix: String, value: LibraryOrganization?) {
        if (value == null) {
            message("$prefix:absent", R.string.portable_organization_no_value)
            return
        }
        message("$prefix:present", if (value.folder.isEmpty() && value.tags.isEmpty() && !value.favorite)
            R.string.portable_organization_present_empty else R.string.portable_organization_value_present)
        field("$prefix:folder", R.string.portable_organization_folder, value.folder)
        field("$prefix:tagCount", R.string.portable_organization_tag_count, value.tags.size.toString())
        value.tags.forEachIndexed { index, tag ->
            rows += OrganizationAdoptionRow("$prefix:tag:$index") { row ->
                OutlinedTextField(tag, {}, readOnly = true, singleLine = true,
                    label = { Text(stringResource(R.string.portable_organization_tag_number, index + 1), color = EinkInk) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = EinkInk,
                        unfocusedTextColor = EinkInk, focusedBorderColor = EinkInk, unfocusedBorderColor = EinkLine),
                    modifier = row)
            }
        }
        flag("$prefix:favorite", R.string.portable_organization_favorite, value.favorite)
    }
    fun server(prefix: String, value: ServerLibraryOrganization?) {
        if (value == null) {
            message("$prefix:noCachedValue", R.string.portable_organization_no_cached_value)
            return
        }
        field("$prefix:version", R.string.portable_organization_account_version, value.version.toString())
        field("$prefix:updatedAt", R.string.portable_organization_updated_at, value.updatedAt)
        organization(prefix, value.organization)
    }
    if (!current) message("expired", R.string.portable_export_expired)
    if (error != null && current) field("error", R.string.portable_organization_error, error)
    if (busy && current) rows += OrganizationAdoptionRow("busy") { row ->
        EinkOperationIndicator(true, stringResource(R.string.portable_organization_loading), modifier = row)
    }
    if (committed && current) {
        message("committed", R.string.portable_organization_queued)
        message("committed:receipt", R.string.portable_organization_no_server_receipt)
    }
    if (current) when (section) {
        OrganizationAdoptionSection.Scope -> {
            message("description", R.string.portable_organization_adoption_description)
            message("description:filePermission", R.string.portable_organization_file_permission)
            field("account", R.string.portable_organization_destination, accountLabel)
            rows += OrganizationAdoptionRow("compare") { row ->
                OutlinedButton(onClick = onCompare, enabled = !busy && !committed,
                    border = BorderStroke(1.dp, if (!busy && !committed) EinkInk else EinkLine),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EinkInk, disabledContentColor = EinkMuted),
                    modifier = row.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_organization_compare)) }
            }
            shown?.let {
                message("compared", R.string.portable_organization_compared)
                field("record", R.string.portable_identity_record, it.remote.recordId)
                identity(it.snapshot.identity)
            }
        }
        OrganizationAdoptionSection.Zip -> shown?.snapshot?.let { snapshot ->
            if (snapshot.presence == LibraryOrganizationSnapshotPresence.ABSENT)
                message("zip:absent", R.string.portable_organization_absent_info)
            else snapshot.organization?.let { organization("zip", LibraryOrganization(it.folder, it.tags, it.favorite)) }
        }
        OrganizationAdoptionSection.Account -> {
            message("account:description", R.string.portable_organization_latest_account)
            server("account", shown?.remote)
        }
        OrganizationAdoptionSection.Device -> {
            message("device:description", R.string.portable_organization_device_description)
            message("device:pending", R.string.portable_organization_pending_description)
            val device = shown?.device
            if (device == null) message("device:noRecord", R.string.portable_organization_no_device_record)
            else {
                message("device:existingRecord", R.string.portable_organization_existing_device_record)
                server("device", device.remote)
            }
        }
        OrganizationAdoptionSection.Confirm -> shown?.let {
            field("destination", R.string.portable_organization_destination, accountLabel)
            field("record", R.string.portable_identity_record, it.remote.recordId)
            if (it.snapshot.presence == LibraryOrganizationSnapshotPresence.ABSENT)
                message("confirm:absent", R.string.portable_organization_absent_info)
            else {
                message("confirm:warning", R.string.portable_organization_confirm_warning)
                message("confirm:emptyWarning", R.string.portable_organization_empty_warning)
                rows += OrganizationAdoptionRow("confirm:apply") { row ->
                    OutlinedButton(onClick = { onConfirm(it.id) }, enabled = !busy && !committed,
                        border = BorderStroke(1.dp, if (!busy && !committed) EinkInk else EinkLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = EinkInk, disabledContentColor = EinkMuted),
                        modifier = row.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_organization_apply)) }
                }
            }
        }
    }
    Column(modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onBack, border = BorderStroke(1.dp, EinkInk),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = EinkInk), modifier = Modifier.heightIn(min = 44.dp)) {
            Text(stringResource(R.string.portable_organization_back))
        }
        if (shown != null) EinkSegmentedControl(OrganizationAdoptionSection.entries, section,
            { section = it }, enabled = !busy, itemHeight = 46.dp * fontScale, label = { labels.getValue(it) })
        AdaptiveCollection(items = rows, pagingState = paging, busy = busy,
            estimatedPagedItemHeight = rowHeight, modifier = Modifier.weight(1f).clipToBounds(), itemKey = { it.key }) { row ->
            Surface(color = EinkPaper, border = BorderStroke(1.dp, EinkLine),
                modifier = Modifier.fillMaxWidth().height(rowHeight).clipToBounds()) {
                row.content(Modifier.fillMaxSize().padding(6.dp))
            }
        }
    }
}
