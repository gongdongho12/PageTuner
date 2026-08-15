package com.dongholab.pagetuner.ui.screen

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.library.LibraryViewModel
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.ui.library.LocalDirectoryBrowserPanel
import com.dongholab.pagetuner.ui.library.LocalLibraryPanel
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl

private enum class LocalSection(val title: String) {
    Library("Library"),
    Files("Device files"),
}

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
    var selectedSection by remember { mutableStateOf(LocalSection.Library) }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EinkSegmentedControl(
            options = LocalSection.entries,
            selected = selectedSection,
            onSelect = { selectedSection = it },
            enabled = !busy,
            label = LocalSection::title,
        )
        when (selectedSection) {
            LocalSection.Library -> LocalLibraryPanel(
                books = books,
                currentBookId = currentBookId,
                busy = busy,
                onOpenBook = onOpenBook,
                onDeleteBook = onDeleteBook,
                onUpdateBookOrganization = onUpdateBookOrganization,
            )
            LocalSection.Files -> LocalDirectoryBrowserPanel(
                busy = busy,
                onImportFile = onImportFile,
            )
        }
    }
}
