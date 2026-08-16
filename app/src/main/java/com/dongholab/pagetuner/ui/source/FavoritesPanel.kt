package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkViewportSurface
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft

@Composable
fun FavoritesPanel(
    favorites: List<RemoteBookItem>,
    displayMode: DisplayMode,
    busy: Boolean,
    onOpenNovelDetail: (RemoteBookItem) -> Unit,
    onRemoveFavorite: (RemoteBookItem) -> Unit,
) {
    EinkViewportSurface(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = EinkInk, modifier = Modifier.size(20.dp))
                Text(
                    text = "Bookmarked Favorites (${favorites.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = EinkInk,
                )
            }

            if (favorites.isEmpty()) {
                Text(
                    text = "No bookmarked favorite novels yet. Tap '★ Add Favorite' on any novel page to save it here for instant access!",
                    style = MaterialTheme.typography.bodySmall,
                    color = EinkMuted,
                )
            } else {
                AdaptiveCollection(
                    items = favorites,
                    modifier = Modifier.weight(1f),
                    estimatedPagedItemHeight = 116.dp,
                    fallbackPageSize = 3,
                    busy = busy,
                ) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(116.dp),
                        color = EinkSoft,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, EinkLine),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Book, contentDescription = null, tint = EinkInk, modifier = Modifier.size(22.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = !busy) { onOpenNovelDetail(item) },
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = EinkInk,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Author: ${item.authors.firstOrNull() ?: "WTR-Lab Author"} | Language: ${item.language ?: "en"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EinkMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(
                                    onClick = { onOpenNovelDetail(item) },
                                    enabled = !busy,
                                ) {
                                    Text("Details & Chapters 📂")
                                }
                                TextButton(
                                    onClick = { onRemoveFavorite(item) },
                                    enabled = !busy,
                                ) {
                                    Icon(Icons.Filled.Star, contentDescription = "Remove Favorite", tint = EinkInk, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Remove ★", style = MaterialTheme.typography.labelSmall, color = EinkInk)
                                }
                            }
                        }
                    }
                }
            }
    }
}
