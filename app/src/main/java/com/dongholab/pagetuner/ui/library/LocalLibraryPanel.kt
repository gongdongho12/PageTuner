package com.dongholab.pagetuner.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.library.LocalBook
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
) {
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
                books.take(4).forEach { book ->
                    LocalBookRow(
                        book = book,
                        selected = currentBookId == book.id,
                        busy = busy,
                        onOpenBook = onOpenBook,
                        onDeleteBook = onDeleteBook,
                    )
                }
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
) {
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
            TextButton(
                onClick = { onOpenBook(book) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = EinkInk,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
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
