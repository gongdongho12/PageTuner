package com.dongholab.pagetuner.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.ui.source.FavoritesPanel

@Composable
fun FavoritesScreen(
    favorites: List<RemoteBookItem>,
    displayMode: DisplayMode,
    busy: Boolean,
    onOpenNovelDetail: (RemoteBookItem) -> Unit,
    onRemoveFavorite: (RemoteBookItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    FavoritesPanel(
        favorites = favorites,
        displayMode = displayMode,
        busy = busy,
        onOpenNovelDetail = onOpenNovelDetail,
        onRemoveFavorite = onRemoveFavorite,
    )
}
