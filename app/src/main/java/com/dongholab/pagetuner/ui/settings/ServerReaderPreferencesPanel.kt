package com.dongholab.pagetuner.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.translation.sync.*
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted

/** The five portable values stay reachable on a bounded, paged E-Ink viewport. */
@Composable
fun ServerReaderPreferencesPanel(state: ReaderPreferencesUiState, sync: ServerReaderPreferencesSync) {
    val choice = state.choice
    val status = when {
        state.staleChoiceRejected -> R.string.reader_preferences_sync_stale
        !state.enabled && choice != null -> R.string.reader_preferences_sync_initial
        else -> when (state.phase) {
            ReadingProgressPhase.Inactive -> R.string.reader_preferences_sync_connect
            ReadingProgressPhase.Loading -> R.string.reader_preferences_sync_loading
            ReadingProgressPhase.Synced -> R.string.reader_preferences_sync_synced
            ReadingProgressPhase.Pending -> R.string.reader_preferences_sync_pending
            ReadingProgressPhase.Offline -> R.string.reader_preferences_sync_offline
            ReadingProgressPhase.RateLimited -> R.string.reader_preferences_sync_rate_limited
            ReadingProgressPhase.Conflict -> R.string.reader_preferences_sync_conflict
            ReadingProgressPhase.Unavailable -> R.string.reader_preferences_sync_unavailable
            ReadingProgressPhase.DeviceError -> R.string.reader_preferences_sync_device_error
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(status), style = MaterialTheme.typography.labelLarge, color = EinkInk)
        Text(stringResource(R.string.reader_preferences_sync_description), style = MaterialTheme.typography.bodySmall, color = EinkMuted)
        if (state.phase in setOf(ReadingProgressPhase.Offline, ReadingProgressPhase.Unavailable,
                ReadingProgressPhase.RateLimited, ReadingProgressPhase.DeviceError)) {
            TextButton(onClick = sync::retry, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.reading_progress_retry)) }
        }
        if (choice != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { sync.choose(state.session, true, choice) }, enabled = state.phase != ReadingProgressPhase.DeviceError,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_preferences_sync_use_device)) }
                TextButton(onClick = { sync.choose(state.session, false, choice) },
                    enabled = choice.remote.preferences != null && state.phase != ReadingProgressPhase.DeviceError,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.reader_preferences_sync_use_account)) }
            }
        }
        val local = choice?.local ?: state.overlay
        val localValues = local?.let { preferenceValues(it) }.orEmpty()
        val remoteValues = choice?.remote?.preferences?.let { preferenceValues(it) }
        val labels = listOf(R.string.reader_preferences_sync_font, R.string.reader_preferences_sync_line,
            R.string.reader_preferences_sync_margin, R.string.reader_preferences_sync_touch, R.string.reader_preferences_sync_list)
        AdaptiveCollection(items = if (local == null) emptyList() else labels.indices.toList(),
            estimatedPagedItemHeight = 76.dp, modifier = Modifier.weight(1f), itemKey = { it },
            emptyContent = { Text(stringResource(if (state.phase == ReadingProgressPhase.Inactive) R.string.reader_preferences_sync_connect
                else R.string.reader_preferences_sync_loading), color = EinkMuted) }) { index ->
            Column(Modifier.fillMaxWidth().height(76.dp).border(1.dp, EinkLine).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center) {
                Text(stringResource(labels[index]), color = EinkInk, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                if (choice != null) {
                    Text(stringResource(R.string.reader_preferences_sync_device_value, localValues[index]), color = EinkInk,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.reader_preferences_sync_account_value, remoteValues?.get(index)
                        ?: stringResource(R.string.reader_preferences_sync_none)), color = EinkInk,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else Text(localValues[index], color = EinkInk, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun preferenceValues(value: SharedReaderPreferences): List<String> = listOf(
    value.fontSize.toString(), "${value.lineHeightPercent}%", value.pageMargin.toString(),
    stringResource(when (value.touchDirection) {
        "left-next" -> R.string.page_turn_left_next_right_previous
        "buttons-only" -> R.string.page_turn_buttons_only
        else -> R.string.page_turn_left_previous_right_next
    }), stringResource(if (value.listMode == "scroll") R.string.list_layout_scroll else R.string.list_layout_paged))
