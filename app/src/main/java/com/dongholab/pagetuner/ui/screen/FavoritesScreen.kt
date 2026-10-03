package com.dongholab.pagetuner.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.translation.sync.*
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.source.FavoritesPanel

private enum class FavoriteSection { Local, Account, Adopt, Sync }

@Composable
fun FavoritesScreen(
    favorites: List<RemoteBookItem>, displayMode: DisplayMode, busy: Boolean,
    onOpenNovelDetail: (RemoteBookItem) -> Unit, onRemoveFavorite: (RemoteBookItem) -> Unit,
    sync: SourceBookFavoritesSync, accountKey: String?, localError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val observed by sync.state.collectAsState()
    // A connection change renders an empty account view before its actor processes the event.
    val state = observed.takeIf { it.accountKey == accountKey } ?: SourceFavoritesUiState()
    var section by rememberSaveable { mutableStateOf(FavoriteSection.Local) }
    val labels = listOf(R.string.source_favorites_local, R.string.source_favorites_account,
        R.string.source_favorites_adopt, R.string.source_favorites_sync).map { stringResource(it) }
    Column(modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EinkSegmentedControl(FavoriteSection.entries, section, { section = it }, label = { labels[it.ordinal] })
        when (section) {
            FavoriteSection.Local -> {
                if (localError) Text(stringResource(R.string.source_favorites_local_error))
                Box(Modifier.weight(1f)) { FavoritesPanel(favorites, displayMode, busy || localError, onOpenNovelDetail, onRemoveFavorite) }
            }
            FavoriteSection.Account -> {
                if (accountKey == null) Text(stringResource(R.string.source_favorites_sign_in))
                else Box(Modifier.weight(1f)) { FavoritesPanel(state.rows.mapNotNull { it.desired.remoteBook() }, displayMode, busy,
                    onOpenNovelDetail, { book ->
                        val row = state.rows.firstOrNull { it.desired.identity.providerId == book.sourceProviderId && it.desired.identity.bookId == book.sourceBookId }
                        if (row != null) sync.change(state.session, row.desired.copy(deleted = true, book = null), row)
                    }, canRemove = state.loaded && state.phase != ReadingProgressPhase.DeviceError) }
            }
            FavoriteSection.Adopt -> {
                Text(stringResource(R.string.source_favorites_adopt_scope), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f)) { FavoritesPanel(favorites, displayMode, busy || localError,
                    { book -> book.sourceFavorite()?.let { sync.change(state.session, it); section = FavoriteSection.Sync } }, {},
                    openLabel = stringResource(R.string.source_favorites_add_to_account), canOpen = { state.loaded && state.accountKey != null && state.phase != ReadingProgressPhase.DeviceError && it.sourceFavorite() != null }, canRemove = false) }
            }
            FavoriteSection.Sync -> Box(Modifier.weight(1f)) { FavoriteSyncStatus(state, sync) }
        }
    }
}

private data class FavoriteSyncField(val key: String, val label: String, val value: String = "",
    val choice: SourceFavoriteChoice? = null, val status: Boolean = false)

@Composable
private fun FavoriteSyncStatus(state: SourceFavoritesUiState, sync: SourceBookFavoritesSync) {
    val localLabel = stringResource(R.string.source_favorites_local)
    val accountLabel = stringResource(R.string.source_favorites_account)
    val titleLabel = stringResource(R.string.source_favorites_title)
    val authorsLabel = stringResource(R.string.source_favorites_authors)
    val languageLabel = stringResource(R.string.source_favorites_language)
    val urlLabel = stringResource(R.string.source_favorites_url)
    val deletedLabel = stringResource(R.string.source_favorites_deleted)
    val fields = buildList {
        add(FavoriteSyncField("status", stringResource(R.string.source_favorites_status, favoritePhaseText(state.phase), state.pendingKeys.size, state.conflicts.size), status = true))
        if (state.staleActionRejected) add(FavoriteSyncField("stale", stringResource(R.string.source_favorites_stale_title), stringResource(R.string.source_favorites_stale_body)))
        state.conflicts.forEach { choice ->
            val key = choice.local.identity.key
            add(FavoriteSyncField("$key:provider", stringResource(R.string.source_favorites_provider_id), choice.local.identity.providerId))
            add(FavoriteSyncField("$key:book", stringResource(R.string.source_favorites_book_id), choice.local.identity.bookId))
            listOf("local" to choice.local.book, "account" to choice.remote.book).forEach { (side, book) ->
                val sideLabel = if (side == "local") localLabel else accountLabel
                if (book == null) add(FavoriteSyncField("$key:$side:deleted", sideLabel, deletedLabel))
                else {
                    add(FavoriteSyncField("$key:$side:title", "$sideLabel · $titleLabel", book.title))
                    add(FavoriteSyncField("$key:$side:authors", "$sideLabel · $authorsLabel", book.authors.joinToString()))
                    add(FavoriteSyncField("$key:$side:language", "$sideLabel · $languageLabel", book.language))
                    add(FavoriteSyncField("$key:$side:url", "$sideLabel · $urlLabel", book.url))
                }
            }
            add(FavoriteSyncField("$key:choice", "", choice = choice))
        }
    }
    val rowHeight = 100.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    AdaptiveCollection(items = fields, modifier = Modifier.fillMaxSize(), estimatedPagedItemHeight = rowHeight,
        fallbackPageSize = 3, itemKey = { it.key }) { field ->
        val choice = field.choice
        when {
            field.status -> Column(Modifier.fillMaxWidth().height(rowHeight)) {
                Text(field.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = sync::retry, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.source_favorites_retry)) }
            }
            choice != null -> Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { sync.resolve(state.session, true, choice) }, modifier = Modifier.weight(1f).height(rowHeight),
                    enabled = choice.remote.version < 9_007_199_254_740_991L && state.phase != ReadingProgressPhase.DeviceError) { Text(stringResource(R.string.source_favorites_use_local)) }
                TextButton(onClick = { sync.resolve(state.session, false, choice) }, modifier = Modifier.weight(1f).height(rowHeight),
                    enabled = state.phase != ReadingProgressPhase.DeviceError) { Text(stringResource(R.string.source_favorites_use_account)) }
            }
            else -> OutlinedTextField(field.value, {}, label = { Text(field.label) }, readOnly = true, singleLine = true,
                modifier = Modifier.fillMaxWidth().height(rowHeight))
        }
    }
}

@Composable
private fun favoritePhaseText(phase: ReadingProgressPhase) = stringResource(when (phase) {
    ReadingProgressPhase.Inactive -> R.string.source_favorites_inactive
    ReadingProgressPhase.Loading -> R.string.source_favorites_loading
    ReadingProgressPhase.Synced -> R.string.source_favorites_synced
    ReadingProgressPhase.Pending -> R.string.source_favorites_pending
    ReadingProgressPhase.Conflict -> R.string.source_favorites_conflict
    ReadingProgressPhase.Offline -> R.string.source_favorites_offline
    ReadingProgressPhase.RateLimited -> R.string.source_favorites_rate_limited
    ReadingProgressPhase.DeviceError -> R.string.source_favorites_device_error
    else -> R.string.source_favorites_unavailable
})
