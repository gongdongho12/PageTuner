package com.dongholab.pagetuner.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.translation.sync.ServerLibraryFilterDraft
import com.dongholab.pagetuner.translation.sync.ServerLibraryFolderFilter
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkChoiceStepper
import com.dongholab.pagetuner.ui.theme.EinkInk

/** A separate viewport keeps search controls from displacing the document list. */
@Composable
internal fun ServerLibraryFilterPanel(
    draft: ServerLibraryFilterDraft,
    enabled: Boolean,
    onDraft: (ServerLibraryFilterDraft) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowHeight = (100 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val strings = LocalResources.current
    Column(modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AdaptiveCollection(items = listOf(0, 1, 2, 3, 4), estimatedPagedItemHeight = rowHeight,
            modifier = Modifier.weight(1f), itemKey = { it }) { field ->
            Column(Modifier.fillMaxWidth().height(rowHeight), verticalArrangement = Arrangement.Center) {
                when (field) {
                    0, 2, 3 -> OutlinedTextField(
                        value = when (field) { 0 -> draft.q; 2 -> draft.folder; else -> draft.tag },
                        onValueChange = { changed -> onDraft(when (field) {
                            0 -> draft.copy(q = changed)
                            2 -> draft.copy(folder = changed)
                            else -> draft.copy(tag = changed)
                        }) },
                        enabled = enabled && (field != 2 || draft.folderMode == ServerLibraryFolderFilter.Exact),
                        label = { Text(stringResource(when (field) {
                            0 -> R.string.server_filter_title
                            2 -> R.string.server_filter_folder_exact
                            else -> R.string.server_filter_tag
                        })) },
                        supportingText = { Text(stringResource(if (field == 3) R.string.server_filter_tag_hint else R.string.server_filter_text_hint)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    1 -> {
                        Text(stringResource(R.string.server_filter_folder), color = EinkInk, style = MaterialTheme.typography.bodySmall)
                        EinkChoiceStepper(ServerLibraryFolderFilter.entries, draft.folderMode,
                            { onDraft(draft.copy(folderMode = it)) }, enabled = enabled,
                            label = { when (it) {
                                ServerLibraryFolderFilter.All -> strings.getString(R.string.server_filter_all_folders)
                                ServerLibraryFolderFilter.Unfiled -> strings.getString(R.string.server_filter_unfiled)
                                ServerLibraryFolderFilter.Exact -> strings.getString(R.string.server_filter_named_folder)
                            } })
                    }
                    else -> {
                        Text(stringResource(R.string.server_filter_favorite), color = EinkInk, style = MaterialTheme.typography.bodySmall)
                        EinkChoiceStepper(listOf(null, true, false), draft.favorite,
                            { onDraft(draft.copy(favorite = it)) }, enabled = enabled,
                            label = { strings.getString(when (it) {
                                null -> R.string.server_filter_all_favorites
                                true -> R.string.server_filter_only_favorites
                                false -> R.string.server_filter_not_favorites
                            }) })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onApply, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.server_filter_apply))
            }
            OutlinedButton(onClick = onReset, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.server_filter_reset))
            }
        }
    }
}
