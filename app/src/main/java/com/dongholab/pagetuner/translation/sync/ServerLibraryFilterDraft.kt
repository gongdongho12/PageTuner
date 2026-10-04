package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.model.library.LibraryFilter

enum class ServerLibraryFolderFilter { All, Unfiled, Exact }

/** Drafts remain in the current account session; changing document kind resets them. */
data class ServerLibraryFilterDraft(
    val q: String = "",
    val folderMode: ServerLibraryFolderFilter = ServerLibraryFolderFilter.All,
    val folder: String = "",
    val tag: String = "",
    val favorite: Boolean? = null,
) {
    fun applied(): LibraryFilter = LibraryFilter(
        q = LibraryFilter.trim(q).takeIf { it.isNotEmpty() },
        folder = when (folderMode) {
            ServerLibraryFolderFilter.All -> null
            ServerLibraryFolderFilter.Unfiled -> ""
            ServerLibraryFolderFilter.Exact -> LibraryFilter.trim(folder).also { require(it.isNotEmpty()) }
        },
        tag = LibraryFilter.trim(tag).takeIf { it.isNotEmpty() },
        favorite = favorite,
    ).also { it.validate() }
}
