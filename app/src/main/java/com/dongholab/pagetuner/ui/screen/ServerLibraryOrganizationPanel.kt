package com.dongholab.pagetuner.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.translation.sync.*
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.theme.EinkInk

/** A bounded editor keeps the original CAS base while a user is typing and remote polls continue. */
@Composable
fun ServerLibraryOrganizationPanel(state: LibraryOrganizationUiState, sync: ServerLibraryOrganizationSync, onBack: () -> Unit,
    modifier: Modifier = Modifier) {
    var base by remember(state.target) { mutableStateOf(state.base) }
    var draft by remember(state.target) { mutableStateOf(state.base.local ?: LibraryOrganization()) }
    var dirty by remember(state.target) { mutableStateOf(false) }
    var newTag by remember(state.target) { mutableStateOf("") }
    var invalid by remember(state.target) { mutableStateOf(false) }
    var remoteSide by remember(state.target) { mutableStateOf(false) }
    val strings = LocalResources.current
    LaunchedEffect(state.base) { if (!dirty) { base = state.base; draft = state.base.local ?: LibraryOrganization() } }
    val choice = state.choice
    val editing = choice == null && state.target != null && state.base.remote != null && state.phase != ReadingProgressPhase.DeviceError
    val shown = if (choice == null) draft else if (remoteSide) choice.remote.organization ?: LibraryOrganization() else choice.local
    val status = when {
        invalid -> R.string.library_organization_invalid
        state.staleActionRejected -> R.string.library_organization_stale
        else -> when (state.phase) {
            ReadingProgressPhase.Inactive -> R.string.server_connect_first
            ReadingProgressPhase.Loading -> R.string.library_organization_loading
            ReadingProgressPhase.Synced -> if (state.base.remote?.version == 0L) R.string.library_organization_absent else R.string.library_organization_synced
            ReadingProgressPhase.Pending -> R.string.library_organization_pending
            ReadingProgressPhase.Offline -> R.string.library_organization_offline
            ReadingProgressPhase.RateLimited -> R.string.library_organization_rate_limited
            ReadingProgressPhase.Conflict -> R.string.library_organization_conflict
            ReadingProgressPhase.Unavailable -> R.string.library_organization_unavailable
            ReadingProgressPhase.DeviceError -> R.string.library_organization_device_error
        }
    }
    Column(modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(44.dp)) { Text(stringResource(R.string.library_organization_back)) }
        Text(stringResource(R.string.library_organization_scope), color = EinkInk, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(status), color = EinkInk, style = MaterialTheme.typography.bodySmall)
        if (choice != null) {
            EinkSegmentedControl(listOf(false, true), remoteSide, { remoteSide = it },
                label = { strings.getString(if (it) R.string.library_organization_account else R.string.library_organization_device) })
        }
        AdaptiveCollection(items = (0 until 2 + shown.tags.size + if (editing) 1 else 0).toList(),
            estimatedPagedItemHeight = 76.dp, modifier = Modifier.weight(1f), itemKey = { it }) { index ->
            Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    index == 0 -> OutlinedTextField(shown.folder, { draft = draft.copy(folder = it); dirty = true; invalid = false },
                        readOnly = !editing, singleLine = true, label = { Text(stringResource(R.string.library_organization_folder)) },
                        modifier = Modifier.fillMaxWidth().height(76.dp))
                    index == 1 -> OutlinedButton(onClick = { draft = draft.copy(favorite = !draft.favorite); dirty = true; invalid = false },
                        enabled = editing, modifier = Modifier.fillMaxWidth().height(76.dp)) {
                        Text(stringResource(if (shown.favorite) R.string.library_organization_favorite_on else R.string.library_organization_favorite_off))
                    }
                    index < 2 + shown.tags.size -> {
                        val tag = shown.tags[index - 2]
                        OutlinedTextField(tag, {}, readOnly = true, singleLine = true,
                            label = { Text(stringResource(R.string.library_organization_tag)) }, modifier = Modifier.weight(1f).height(76.dp))
                        if (editing) OutlinedButton(onClick = { draft = draft.copy(tags = draft.tags - tag); dirty = true; invalid = false },
                            modifier = Modifier.height(76.dp)) { Text(stringResource(R.string.library_organization_remove)) }
                    }
                    else -> {
                        OutlinedTextField(newTag, { newTag = it; invalid = false }, singleLine = true,
                            label = { Text(stringResource(R.string.library_organization_new_tag)) }, modifier = Modifier.weight(1f).height(76.dp))
                        OutlinedButton(onClick = {
                            val value = draft.copy(folder = trimLibraryOrganizationText(draft.folder), tags = draft.tags + trimLibraryOrganizationText(newTag))
                            if (runCatching { ServerLibraryOrganizationJson.validate(value) }.isSuccess) {
                                draft = value; newTag = ""; dirty = true; invalid = false
                            } else invalid = true
                        }, modifier = Modifier.height(76.dp)) { Text(stringResource(R.string.library_organization_add)) }
                    }
                }
            }
        }
        if (choice != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { dirty = false; sync.choose(state.session, true, choice) },
                    enabled = state.phase != ReadingProgressPhase.DeviceError, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(stringResource(R.string.library_organization_use_device))
                }
                OutlinedButton(onClick = { dirty = false; sync.choose(state.session, false, choice) },
                    enabled = state.phase != ReadingProgressPhase.DeviceError, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(stringResource(R.string.library_organization_use_account))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    val value = draft.copy(folder = trimLibraryOrganizationText(draft.folder))
                    if (newTag.isEmpty() && runCatching { ServerLibraryOrganizationJson.validate(value) }.isSuccess) {
                        sync.edit(state.session, value, base)
                    } else invalid = true
                }, enabled = editing && dirty, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.library_organization_save)) }
                OutlinedButton(onClick = { dirty = false; invalid = false; newTag = ""; base = state.base; draft = state.base.local ?: LibraryOrganization(); sync.retry() },
                    modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.library_organization_reload)) }
            }
            // A saved draft can become clean only after the actor accepts that exact local value.
            LaunchedEffect(state.base.local) {
                if (dirty && state.base.local == draft.copy(folder = trimLibraryOrganizationText(draft.folder))) {
                    dirty = false; base = state.base; draft = state.base.local ?: LibraryOrganization()
                }
            }
        }
    }
}
