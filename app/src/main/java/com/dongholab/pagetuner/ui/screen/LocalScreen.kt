package com.dongholab.pagetuner.ui.screen

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.library.LibraryViewModel
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.ui.library.LocalDirectoryBrowserPanel
import com.dongholab.pagetuner.ui.library.LocalLibraryPanel

@Composable
fun LocalScreen(
    books: List<LocalBook>,
    currentBookId: String?,
    busy: Boolean,
    onOpenBook: (LocalBook) -> Unit,
    onDeleteBook: (LocalBook) -> Unit,
    onUpdateBookOrganization: (com.dongholab.pagetuner.library.LocalBook, String, String) -> Unit,
    onImportFile: (java.io.File) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LocalLibraryPanel(
            books = books,
            currentBookId = currentBookId,
            busy = busy,
            onOpenBook = onOpenBook,
            onDeleteBook = onDeleteBook,
            onUpdateBookOrganization = onUpdateBookOrganization,
        )
        LocalDirectoryBrowserPanel(
            busy = busy,
            onImportFile = onImportFile,
        )
    }
}
