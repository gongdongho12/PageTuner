package com.dongholab.pagetuner.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.library.parseLocalBookTags
import com.dongholab.pagetuner.ui.text.localizedName
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft

@Composable
fun LocalLibraryPanel(
    books: List<LocalBook>,
    currentBookId: String?,
    busy: Boolean,
    onOpenBook: (LocalBook) -> Unit,
    onDeleteBook: (LocalBook) -> Unit,
    onUpdateBookOrganization: (LocalBook, String, String) -> Unit,
) {
    var libraryQuery by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf("") }
    val folders = localLibraryFolders(books)
    val visibleBooks = filterLocalLibraryBooks(
        books = books,
        query = libraryQuery,
        folder = selectedFolder,
    )

    LaunchedEffect(folders, selectedFolder) {
        if (selectedFolder.isNotBlank() &&
            folders.none { folder -> folder.equals(selectedFolder, ignoreCase = true) }
        ) {
            selectedFolder = ""
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.titleMedium,
                color = EinkInk,
                fontWeight = FontWeight.SemiBold,
            )
            if (books.isEmpty()) {
                Text(
                    text = stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = EinkMuted,
                )
            } else {
                LibraryFilterControls(
                    query = libraryQuery,
                    folders = folders,
                    selectedFolder = selectedFolder,
                    busy = busy,
                    onQueryChange = { libraryQuery = it },
                    onFolderChange = { selectedFolder = it },
                )
                if (visibleBooks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.library_filter_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = EinkMuted,
                    )
                }
                groupedLocalLibraryBooks(visibleBooks).forEach { (folder, folderBooks) ->
                    val folderLabel = if (folder.isBlank()) {
                        stringResource(R.string.library_folder_uncategorized)
                    } else {
                        folder
                    }
                    Text(
                        text = stringResource(
                            R.string.library_folder_header,
                            folderLabel,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = EinkInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                    com.dongholab.pagetuner.ui.common.EinkPagingContainer(
                        items = folderBooks,
                        pageSize = 5,
                        busy = busy,
                    ) { book ->
                        LocalBookRow(
                            book = book,
                            selected = currentBookId == book.id,
                            busy = busy,
                            onOpenBook = onOpenBook,
                            onDeleteBook = onDeleteBook,
                            onUpdateBookOrganization = onUpdateBookOrganization,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryFilterControls(
    query: String,
    folders: List<String>,
    selectedFolder: String,
    busy: Boolean,
    onQueryChange: (String) -> Unit,
    onFolderChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text(stringResource(R.string.field_library_search)) },
            singleLine = true,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = selectedFolder.isBlank(),
                onClick = { onFolderChange("") },
                enabled = !busy,
                label = {
                    Text(
                        text = stringResource(R.string.library_filter_all_folders),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            folders.forEach { folder ->
                FilterChip(
                    selected = selectedFolder.equals(folder, ignoreCase = true),
                    onClick = { onFolderChange(folder) },
                    enabled = !busy,
                    label = {
                        Text(
                            text = folder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalBookRow(
    book: LocalBook,
    selected: Boolean,
    busy: Boolean,
    onOpenBook: (LocalBook) -> Unit,
    onDeleteBook: (LocalBook) -> Unit,
    onUpdateBookOrganization: (LocalBook, String, String) -> Unit,
) {
    var folderDraft by remember(book.id, book.folder) { mutableStateOf(book.folder) }
    var tagsDraft by remember(book.id, book.tags) { mutableStateOf(book.tags.joinToString(", ")) }
    val parsedTags = parseLocalBookTags(tagsDraft)
    val organizationChanged = folderDraft.trim() != book.folder || parsedTags != book.tags

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) EinkSoft else EinkPanel,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, if (selected) EinkInk else EinkLine),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = { onOpenBook(book) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = EinkInk,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.library_book_meta,
                                book.format.localizedName(),
                                book.safeCurrentPageIndex + 1,
                                book.pageCount,
                                book.readingProgressPercent,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = EinkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (book.tags.isEmpty()) {
                                stringResource(R.string.library_tags_empty)
                            } else {
                                stringResource(R.string.library_tags_value, book.tags.joinToString(", "))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = EinkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = folderDraft,
                        onValueChange = { folderDraft = it },
                        modifier = Modifier.weight(0.8f),
                        enabled = !busy,
                        label = { Text(stringResource(R.string.library_folder_label)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = tagsDraft,
                        onValueChange = { tagsDraft = it },
                        modifier = Modifier.weight(1.2f),
                        enabled = !busy,
                        label = { Text(stringResource(R.string.library_tags_label)) },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = { onUpdateBookOrganization(book, folderDraft, tagsDraft) },
                        enabled = !busy && organizationChanged,
                    ) {
                        Text(stringResource(R.string.action_save_organization))
                    }
                }
            }
            IconButton(
                onClick = { onDeleteBook(book) },
                enabled = !busy,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete_book),
                    tint = EinkInk,
                )
            }
        }
    }
}
