package com.dongholab.pagetuner.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.translation.sync.*
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.common.EinkChoiceStepper
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkPaper

private enum class ServerPanel(val label: Int) {
    Connection(R.string.server_panel_connection), Account(R.string.server_panel_account),
    Library(R.string.server_panel_library), Jobs(R.string.server_job_panel), Document(R.string.server_panel_document),
}
private enum class AccountPanel(val label: Int) { Form(R.string.server_account_form), Languages(R.string.server_account_languages) }

@Composable
fun ServerLibraryScreen(
    state: ServerLibraryState,
    documentTitle: String,
    externalBusy: Boolean,
    onEndpoint: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRegister: () -> Unit,
    onProfileDraft: (ServerAccountDraft) -> Unit,
    onSaveProfile: () -> Unit,
    onLanguages: () -> Unit,
    onApplyTargetLanguage: (String) -> Unit,
    onPage: (Int, ServerLibraryKind) -> Unit,
    onRead: (ServerLibraryEntry, Boolean) -> Unit,
    onPrepareTranslation: (ServerLibraryEntry) -> Unit,
    onJobDraft: (ServerJobDraft) -> Unit,
    onJobProvider: (String) -> Unit,
    onSubmitJob: () -> Unit,
    onJobsPage: (Int) -> Unit,
    onCancelJob: (ServerTranslationJob) -> Unit,
    onRetryJob: (ServerTranslationJob) -> Unit,
    onReadJob: (ServerTranslationJob) -> Unit,
    onPublish: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var panel by remember { mutableStateOf(ServerPanel.Connection) }
    var accountPanel by remember { mutableStateOf(AccountPanel.Form) }
    val strings = LocalResources.current
    val busy = state.busy || externalBusy
    Column(modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EinkChoiceStepper(ServerPanel.entries, panel, { panel = it
            if (it == ServerPanel.Library && state.connected && state.page == null && !busy) onPage(0, state.kind)
            if (it == ServerPanel.Jobs && state.connected && state.jobs == null && !busy) onJobsPage(0)
        }, label = { strings.getString(it.label) })
        val message = state.error ?: state.status
        Text(strings.getString(message.resource, *message.arguments.toTypedArray()), color = EinkInk, style = MaterialTheme.typography.bodySmall,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (state.busy) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(44.dp)) { Text(strings.getString(R.string.server_cancel)) }
        }
        when (panel) {
            ServerPanel.Connection -> {
                AdaptiveCollection(items = listOf(0, 1, 2), estimatedPagedItemHeight = 76.dp,
                    modifier = Modifier.weight(1f), busy = busy) { field ->
                    val value = when (field) { 0 -> state.connection.endpoint; 1 -> state.connection.username; else -> state.connection.password }
                    val change = when (field) { 0 -> onEndpoint; 1 -> onUsername; else -> onPassword }
                    OutlinedTextField(value = value, onValueChange = change, enabled = !busy, singleLine = true,
                        label = { Text(strings.getString(when (field) { 0 -> R.string.server_endpoint; 1 -> R.string.server_username; else -> R.string.server_password })) },
                        visualTransformation = if (field == 2) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth().height(76.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onConnect, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) { Text(strings.getString(R.string.server_connect)) }
                    OutlinedButton(onClick = onDisconnect, enabled = !externalBusy, modifier = Modifier.weight(1f).height(48.dp)) { Text(strings.getString(R.string.server_disconnect)) }
                }
            }
            ServerPanel.Account -> {
                EinkSegmentedControl(AccountPanel.entries, accountPanel, { accountPanel = it }, label = { strings.getString(it.label) })
                if (accountPanel == AccountPanel.Form) {
                    Text(if (state.profile == null) strings.getString(R.string.server_account_hint)
                        else strings.getString(R.string.server_profile_hint, state.profile.username),
                        style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    AdaptiveCollection(items = listOf(0, 1, 2), estimatedPagedItemHeight = 76.dp,
                        modifier = Modifier.weight(1f), busy = busy) { field ->
                        val draft = state.profileDraft
                        val value = when (field) { 0 -> draft.displayName; 1 -> draft.locale; else -> draft.targetLanguage }
                        OutlinedTextField(value, { changed -> onProfileDraft(when (field) {
                            0 -> draft.copy(displayName = changed)
                            1 -> draft.copy(locale = changed)
                            else -> draft.copy(targetLanguage = changed)
                        }) }, enabled = !busy, singleLine = true,
                            label = { Text(strings.getString(when (field) { 0 -> R.string.server_display_name; 1 -> R.string.server_locale; else -> R.string.server_target })) },
                            modifier = Modifier.fillMaxWidth().height(76.dp))
                    }
                    state.profile?.let { profile ->
                        Text(strings.getString(R.string.server_effective_locale, profile.locale, profile.effectiveLocale),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = if (state.connected) onSaveProfile else onRegister, enabled = !busy,
                            modifier = Modifier.weight(1f).height(48.dp)) {
                            Text(strings.getString(if (state.connected) R.string.server_save_profile else R.string.server_register))
                        }
                        state.profile?.let { profile ->
                            OutlinedButton(onClick = { onApplyTargetLanguage(profile.targetLanguage) }, enabled = !busy,
                                modifier = Modifier.weight(1f).height(48.dp)) { Text(strings.getString(R.string.server_apply_target), maxLines = 2) }
                        }
                    }
                } else {
                    AdaptiveCollection(items = state.languages?.items.orEmpty(), estimatedPagedItemHeight = 108.dp,
                        modifier = Modifier.weight(1f), busy = busy, itemKey = { it.tag }) { language ->
                        OutlinedButton(onClick = { onProfileDraft(state.profileDraft.copy(locale = language.tag)); accountPanel = AccountPanel.Form },
                            enabled = !busy, modifier = Modifier.fillMaxWidth().height(108.dp)) {
                            Column {
                                Text("${language.nativeName} · ${language.tag}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (language.available) strings.getString(R.string.server_language_ready)
                                    else strings.getString(R.string.server_language_fallback, language.fallbackTag),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(strings.getString(R.string.server_select_locale), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    OutlinedButton(onClick = onLanguages, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(strings.getString(R.string.server_load_languages))
                    }
                }
            }
            ServerPanel.Library -> {
                EinkSegmentedControl(ServerLibraryKind.entries, state.kind,
                    { onPage(0, it) }, enabled = state.connected && !busy,
                    label = { strings.getString(if (it == ServerLibraryKind.Translations) R.string.server_kind_translations else R.string.server_kind_originals) })
                val rowHeight = if (state.kind == ServerLibraryKind.Originals) 160.dp else 112.dp
                AdaptiveCollection(items = state.page?.items.orEmpty(), estimatedPagedItemHeight = rowHeight,
                    modifier = Modifier.weight(1f), busy = busy, itemKey = { it.recordId },
                    emptyContent = { Text(strings.getString(if (state.connected) R.string.server_empty else R.string.server_connect_first)) }) { entry ->
                    Surface(color = EinkPaper, border = BorderStroke(1.dp, EinkLine), modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(entry.title, color = EinkInk, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(strings.getString(R.string.server_document_info, entry.language, entry.paragraphCount), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { onRead(entry, false) }, enabled = !busy, modifier = Modifier.weight(1f).height(44.dp)) { Text(strings.getString(R.string.server_read)) }
                                OutlinedButton(onClick = { onRead(entry, true) }, enabled = !busy, modifier = Modifier.weight(1f).height(44.dp)) { Text(strings.getString(R.string.server_save_device)) }
                            }
                            if (entry.kind == ServerLibraryKind.Originals) {
                                OutlinedButton(onClick = { panel = ServerPanel.Jobs; onPrepareTranslation(entry) }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().height(44.dp)) { Text(strings.getString(R.string.server_job_new)) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val page = state.page
                    OutlinedButton(onClick = { onPage((page?.page ?: 0) - 1, state.kind) }, enabled = !busy && (page?.page ?: 0) > 0,
                        modifier = Modifier.weight(1f).height(48.dp)) { Text(strings.getString(R.string.action_previous)) }
                    OutlinedButton(onClick = { onPage(page?.page ?: 0, state.kind) }, enabled = !busy && state.connected,
                        modifier = Modifier.weight(1.4f).height(48.dp)) { Text(strings.getString(R.string.server_refresh_page, (page?.page ?: 0) + 1, page?.totalPages?.coerceAtLeast(1) ?: 1)) }
                    OutlinedButton(onClick = { onPage((page?.page ?: 0) + 1, state.kind) }, enabled = !busy && page?.hasNext == true,
                        modifier = Modifier.weight(1f).height(48.dp)) { Text(strings.getString(R.string.action_next)) }
                }
            }
            ServerPanel.Jobs -> ServerTranslationJobsPanel(state, busy, onJobDraft, onJobProvider, onSubmitJob,
                onJobsPage, onCancelJob, onRetryJob, onReadJob, Modifier.weight(1f))
            ServerPanel.Document -> {
                AdaptiveCollection(items = listOf("publish", "restore"), estimatedPagedItemHeight = 154.dp,
                    modifier = Modifier.weight(1f), busy = busy) { action ->
                    Column(Modifier.fillMaxWidth().height(154.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(documentTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = EinkInk)
                        Text(strings.getString(if (action == "publish") R.string.server_publish_hint else R.string.server_restore_hint),
                            maxLines = 3, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = if (action == "publish") onPublish else onRestore,
                            enabled = state.connected && !busy && (action == "publish" || state.selected?.storedTranslation != null),
                            modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(strings.getString(if (action == "publish") R.string.server_publish else R.string.server_restore))
                        }
                    }
                }
            }
        }
    }
}
