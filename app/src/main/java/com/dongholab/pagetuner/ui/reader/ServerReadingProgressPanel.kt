package com.dongholab.pagetuner.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.translation.sync.ReadingProgressPhase
import com.dongholab.pagetuner.translation.sync.ReadingProgressUiState
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkPanel

@Composable
fun ServerReadingProgressPanel(state: ReadingProgressUiState, onRetry: () -> Unit, onLocal: () -> Unit, onServer: () -> Unit) {
    if (state.phase == ReadingProgressPhase.Inactive) return
    var showConflict by remember(state.readerId) { mutableStateOf(false) }
    val label = when (state.phase) {
        ReadingProgressPhase.Inactive -> return
        ReadingProgressPhase.Loading -> R.string.reading_progress_loading
        ReadingProgressPhase.Synced -> R.string.reading_progress_synced
        ReadingProgressPhase.Pending -> R.string.reading_progress_pending
        ReadingProgressPhase.Offline -> R.string.reading_progress_offline
        ReadingProgressPhase.RateLimited -> R.string.reading_progress_rate_limited
        ReadingProgressPhase.Conflict -> R.string.reading_progress_conflict
        ReadingProgressPhase.Unavailable -> R.string.reading_progress_unavailable
        ReadingProgressPhase.DeviceError -> R.string.reading_progress_device_error
    }
    Row(Modifier.fillMaxWidth().background(EinkPanel).padding(horizontal = 12.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), Modifier.weight(1f), color = EinkInk, style = MaterialTheme.typography.labelMedium)
        if (state.phase == ReadingProgressPhase.Conflict) {
            TextButton(onClick = { showConflict = true }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.reading_progress_choose)) }
        } else if (state.phase in setOf(ReadingProgressPhase.Offline, ReadingProgressPhase.Unavailable, ReadingProgressPhase.RateLimited)) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.reading_progress_retry)) }
        }
    }
    if (showConflict && state.phase == ReadingProgressPhase.Conflict) {
        AlertDialog(onDismissRequest = { showConflict = false }, containerColor = EinkPanel, titleContentColor = EinkInk, textContentColor = EinkInk,
            title = { Text(stringResource(R.string.reading_progress_conflict)) },
            text = { Text(stringResource(R.string.reading_progress_conflict_detail, (state.localPage ?: 0) + 1, (state.serverPage ?: 0) + 1)) },
            confirmButton = { TextButton(onClick = { showConflict = false; onLocal() }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.reading_progress_use_local))
            } },
            dismissButton = { TextButton(onClick = { showConflict = false; onServer() }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.reading_progress_use_server))
            } },
        )
    }
}
