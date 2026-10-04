package com.dongholab.pagetuner.sharing

import com.dongholab.pagetuner.core.sharing.SharedDocument
import com.dongholab.pagetuner.core.sharing.SharedLibraryPage
import java.io.InputStream

/** Platform adapter. A GET must never update reading progress or import/change a book. */
interface SharingLibrary {
    fun list(offset: Int, limit: Int): SharedLibraryPage
    fun document(id: String): SharedDocument?
    /** Revision pins binary assets to the exact document opened by the browser. */
    fun asset(documentId: String, revision: String, assetId: String): SharedBinary?
}

data class SharedBinary(val mimeType: String, val byteLength: Long, val open: () -> InputStream)

/** Only generated web assets may be resolved here, never arbitrary application files. */
fun interface SharingWebAssets {
    fun open(path: String): SharedBinary?
}

class SharingUnavailableException(val code: String) : RuntimeException(code)
