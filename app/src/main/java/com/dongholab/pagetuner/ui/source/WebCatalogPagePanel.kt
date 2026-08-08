package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.ui.common.EinkPagingContainer
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.common.EinkOperationIndicator

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WebCatalogPagePanel(
    catalogUrl: String,
    query: String,
    items: List<RemoteBookItem>,
    coverThumbnails: Map<String, ByteArray>,
    displayMode: DisplayMode,
    busy: Boolean,
    statusText: String?,
    onQueryChange: (String) -> Unit,
    onRefreshCatalog: () -> Unit,
    onOpenDetail: (RemoteBookItem) -> Unit,
    onImportItem: (RemoteBookItem) -> Unit,
    onBackToSourceManager: () -> Unit,
) {
    var selectedLanguageFilter by remember { mutableStateOf("All") }
    var selectedGenreFilter by remember { mutableStateOf("All") }

    val filteredItems = remember(items, selectedLanguageFilter, selectedGenreFilter) {
        items.filter { item ->
            val matchLang = when (selectedLanguageFilter) {
                "All" -> true
                "en" -> (item.language ?: "en").contains("en", ignoreCase = true)
                "ko" -> (item.language ?: "").contains("ko", ignoreCase = true)
                else -> true
            }
            val matchGenre = when (selectedGenreFilter) {
                "All" -> true
                "Fantasy" -> listOf("fantasy", "magic", "sword", "reincarnat", "isekai", "hero", "lord", "dragon", "mage", "witch", "level").any { kw ->
                    item.title.contains(kw, ignoreCase = true) || item.downloadUrl.contains(kw, ignoreCase = true)
                }
                "Action" -> listOf("action", "battle", "martial", "wuxia", "fight", "war", "kill", "master", "blade", "fist").any { kw ->
                    item.title.contains(kw, ignoreCase = true) || item.downloadUrl.contains(kw, ignoreCase = true)
                }
                "Romance" -> listOf("romance", "love", "heart", "marriage", "wife", "husband", "duchess", "empress", "villainess").any { kw ->
                    item.title.contains(kw, ignoreCase = true) || item.downloadUrl.contains(kw, ignoreCase = true)
                }
                "System" -> listOf("system", "stat", "status", "game", "player", "dungeon", "tower", "rank", "skill").any { kw ->
                    item.title.contains(kw, ignoreCase = true) || item.downloadUrl.contains(kw, ignoreCase = true)
                }
                else -> item.title.contains(selectedGenreFilter, ignoreCase = true) || item.downloadUrl.contains(selectedGenreFilter, ignoreCase = true)
            }
            matchLang && matchGenre
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top Navigation Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBackToSourceManager, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = EinkInk, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("◄ Back to Sources Manager", style = MaterialTheme.typography.labelLarge, color = EinkInk, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onRefreshCatalog,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EinkInk,
                        contentColor = EinkPanel,
                    ),
                    shape = RoundedCornerShape(2.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh Page 🔄", style = MaterialTheme.typography.labelSmall)
                }
            }

            Text(
                text = "Catalog Page: $catalogUrl",
                style = MaterialTheme.typography.labelSmall,
                color = EinkMuted,
            )

            // Search Bar
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text("Search catalog novels...") },
                singleLine = true,
            )

            // Language & Genre Filter Chips
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Language Filter:", style = MaterialTheme.typography.labelSmall, color = EinkMuted)
                    Text("Auto-fit pages", style = MaterialTheme.typography.labelSmall, color = EinkMuted)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "en", "ko").forEach { lang ->
                        FilterChip(
                            selected = selectedLanguageFilter == lang,
                            onClick = { selectedLanguageFilter = lang },
                            enabled = !busy,
                            label = { Text(if (lang == "All") "All Languages" else lang.uppercase()) },
                        )
                    }
                }

                Text("Genre Filter:", style = MaterialTheme.typography.labelSmall, color = EinkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Fantasy", "Action", "Romance", "System").forEach { genre ->
                        FilterChip(
                            selected = selectedGenreFilter == genre,
                            onClick = { selectedGenreFilter = genre },
                            enabled = !busy,
                            label = { Text(genre) },
                        )
                    }
                }
            }

            EinkOperationIndicator(
                visible = busy,
                title = "Loading catalog page…",
                detail = statusText,
            )

            // Catalog Items List with E-Ink Dynamic Auto-Fit Discrete Pagination
            com.dongholab.pagetuner.ui.common.EinkAutoFitPagingContainer(
                items = filteredItems,
                estimatedItemHeight = 104.dp,
                busy = busy,
                modifier = Modifier.weight(1f),
                emptyContent = {
                    Text(
                        text = if (items.isEmpty()) "Loading catalog page items..." else "No novels match your filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EinkMuted,
                    )
                },
            ) { item ->
                RemoteBookRow(
                    item = item,
                    coverBytes = item.coverUrl?.let { coverThumbnails[it] },
                    displayMode = displayMode,
                    busy = busy,
                    onOpenDetail = { onOpenDetail(item) },
                    onImportItem = onImportItem,
                )
            }
        }
    }
}
