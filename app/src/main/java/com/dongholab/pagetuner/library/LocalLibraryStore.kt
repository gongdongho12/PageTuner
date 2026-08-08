package com.dongholab.pagetuner.library

import android.content.Context
import android.net.Uri
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.document.LoadedReaderDocument
import com.dongholab.pagetuner.document.detectReaderDocumentFormat
import com.dongholab.pagetuner.document.readReaderDocument
import com.dongholab.pagetuner.document.readerDocumentDisplayName
import com.dongholab.pagetuner.source.RemoteBookItem
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalLibraryOpenResult(
    val book: LocalBook,
    val loadedDocument: LoadedReaderDocument,
    val wasDuplicateImport: Boolean = false,
)

class LocalLibraryStore(context: Context) {
    private val appContext = context.applicationContext
    private val libraryDir = File(appContext.filesDir, "local_library")
    private val booksDir = File(libraryDir, "books")
    private val metadataFile = File(libraryDir, "books.json")

    suspend fun listBooks(): List<LocalBook> = withContext(Dispatchers.IO) {
        readBooks().sortedByDescending { it.lastOpenedAtMillis }
    }

    suspend fun importBook(uri: Uri): LocalLibraryOpenResult = withContext(Dispatchers.IO) {
        ensureDirectories()

        val title = appContext.readerDocumentDisplayName(uri)
        val format = appContext.detectReaderDocumentFormat(uri, title)
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Unable to open source document.")
        importBytes(
            title = title,
            format = format,
            bytes = bytes,
        )
    }

    suspend fun importRemoteBook(
        remoteBook: RemoteBookItem,
        bytes: ByteArray,
    ): LocalLibraryOpenResult = withContext(Dispatchers.IO) {
        ensureDirectories()
        val remoteIdentity = remoteBook.remoteLibraryIdentityOrNull()
        if (remoteIdentity == null) {
            importBytes(
                title = remoteBook.title,
                format = remoteBook.format,
                bytes = bytes,
            )
        } else {
            importRemoteSeriesChapter(remoteBook, remoteIdentity, bytes)
        }
    }

    suspend fun openBook(bookId: String): LocalLibraryOpenResult = withContext(Dispatchers.IO) {
        val book = readBooks().firstOrNull { it.id == bookId }
            ?: throw IOException("Local book metadata was not found.")
        openStoredBook(book)
    }

    suspend fun updateProgress(bookId: String, pageIndex: Int) = withContext(Dispatchers.IO) {
        val books = readBooks()
        val updated = books.map { book ->
            if (book.id == bookId) {
                book.copy(
                    currentPageIndex = pageIndex.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0)),
                    lastOpenedAtMillis = System.currentTimeMillis(),
                )
            } else {
                book
            }
        }
        writeBooks(updated)
    }

    suspend fun updateBookmarks(
        bookId: String,
        bookmarks: List<LocalBookBookmark>,
    ) = withContext(Dispatchers.IO) {
        val books = readBooks()
        val updated = books.map { book ->
            if (book.id == bookId) {
                book.copy(
                    bookmarks = bookmarks
                        .filter { bookmark -> bookmark.pageIndex in 0 until book.pageCount }
                        .sortedBy { bookmark -> bookmark.pageIndex },
                    lastOpenedAtMillis = System.currentTimeMillis(),
                )
            } else {
                book
            }
        }
        writeBooks(updated)
    }

    suspend fun updateAnnotations(
        bookId: String,
        annotations: List<LocalBookAnnotation>,
    ) = withContext(Dispatchers.IO) {
        val books = readBooks()
        val updated = books.map { book ->
            if (book.id == bookId) {
                book.copy(
                    annotations = annotations
                        .filter { annotation ->
                            annotation.pageIndex in 0 until book.pageCount &&
                                annotation.text.isNotBlank()
                        }
                        .sortedWith(
                            compareBy<LocalBookAnnotation> { annotation -> annotation.pageIndex }
                                .thenBy { annotation -> annotation.createdAtMillis },
                        ),
                    lastOpenedAtMillis = System.currentTimeMillis(),
                )
            } else {
                book
            }
        }
        writeBooks(updated)
    }

    suspend fun updateOrganization(
        bookId: String,
        folder: String,
        rawTags: String,
    ) = withContext(Dispatchers.IO) {
        val books = readBooks()
        val updated = books.map { book ->
            if (book.id == bookId) {
                book.copy(
                    folder = normalizeLocalBookFolder(folder),
                    tags = parseLocalBookTags(rawTags),
                )
            } else {
                book
            }
        }
        writeBooks(updated)
    }

    suspend fun deleteBook(bookId: String): Boolean = withContext(Dispatchers.IO) {
        val books = readBooks()
        val target = books.firstOrNull { it.id == bookId } ?: return@withContext false
        safeBookFile(target).delete()
        writeBooks(books.filterNot { it.id == bookId })
        true
    }

    private fun openStoredBook(
        book: LocalBook,
        wasDuplicateImport: Boolean = false,
    ): LocalLibraryOpenResult {
        val storedFile = safeBookFile(book)
        if (!storedFile.exists()) throw IOException("Local book file was not found.")

        val loaded = appContext.readReaderDocument(
            uri = Uri.fromFile(storedFile),
            preferredTitle = book.currentChapterTitle ?: book.title,
        )
        val updatedBook = book.copy(
            pageCount = loaded.document.pageCount.coerceAtLeast(1),
            currentPageIndex = book.currentPageIndex.coerceIn(
                0,
                (loaded.document.pageCount - 1).coerceAtLeast(0),
            ),
            lastOpenedAtMillis = System.currentTimeMillis(),
        )
        writeBooks(readBooks().map { if (it.id == book.id) updatedBook else it })

        return LocalLibraryOpenResult(
            book = updatedBook,
            loadedDocument = loaded,
            wasDuplicateImport = wasDuplicateImport,
        )
    }

    private fun importBytes(
        title: String,
        format: DocumentFormat,
        bytes: ByteArray,
    ): LocalLibraryOpenResult {
        ensureDirectories()
        val contentHash = DocumentIds.sha256(bytes)
        val books = readBooks()
        val existing = books.firstOrNull { it.contentHash == contentHash }

        if (existing != null && safeBookFile(existing).exists()) {
            return openStoredBook(existing, wasDuplicateImport = true)
        }

        val fileName = "${contentHash.take(16)}-${sanitizeFileName(title, format)}"
        val storedFile = File(booksDir, fileName)
        storedFile.writeBytes(bytes)

        val loaded = appContext.readReaderDocument(
            uri = Uri.fromFile(storedFile),
            preferredTitle = title,
            preferredFormat = format,
        )
        val now = System.currentTimeMillis()
        val book = LocalBook(
            id = contentHash.take(24),
            title = loaded.document.title,
            format = loaded.document.format,
            relativePath = "books/$fileName",
            contentHash = contentHash,
            pageCount = loaded.document.pageCount.coerceAtLeast(1),
            currentPageIndex = 0,
            importedAtMillis = now,
            lastOpenedAtMillis = now,
            fileSizeBytes = bytes.size.toLong(),
        )

        writeBooks(books.filterNot { it.id == book.id || it.contentHash == book.contentHash } + book)
        return LocalLibraryOpenResult(book = book, loadedDocument = loaded)
    }

    private fun importRemoteSeriesChapter(
        remoteBook: RemoteBookItem,
        remoteIdentity: RemoteLibraryIdentity,
        bytes: ByteArray,
    ): LocalLibraryOpenResult {
        val contentHash = DocumentIds.sha256(bytes)
        val books = readBooks()
        val existing = books.firstOrNull { it.belongsTo(remoteIdentity) }
            ?: books.firstOrNull { it.contentHash == contentHash }
        val sameChapter = existing?.currentRemoteChapterId == remoteBook.identity.remoteId
        if (existing != null && sameChapter && existing.contentHash == contentHash && safeBookFile(existing).exists()) {
            return openStoredBook(existing, wasDuplicateImport = true)
        }

        val relativePath = existing?.relativePath
            ?: "books/${remoteIdentity.localBookId}-${sanitizeFileName(remoteBook.seriesTitle ?: remoteBook.title, remoteBook.format)}"
        val storedFile = File(libraryDir, relativePath)
        storedFile.parentFile?.mkdirs()
        val temporaryFile = File(requireNotNull(storedFile.parentFile), "${storedFile.name}.tmp")
        temporaryFile.writeBytes(bytes)
        if (!temporaryFile.renameTo(storedFile)) {
            storedFile.writeBytes(temporaryFile.readBytes())
            temporaryFile.delete()
        }

        val loaded = appContext.readReaderDocument(
            uri = Uri.fromFile(storedFile),
            preferredTitle = remoteBook.title,
            preferredFormat = remoteBook.format,
        )
        val now = System.currentTimeMillis()
        val pageCount = loaded.document.pageCount.coerceAtLeast(1)
        val book = LocalBook(
            id = remoteIdentity.localBookId,
            title = remoteBook.seriesTitle?.takeIf { it.isNotBlank() }
                ?: existing?.title
                ?: loaded.document.title,
            format = loaded.document.format,
            relativePath = relativePath,
            contentHash = contentHash,
            pageCount = pageCount,
            currentPageIndex = if (sameChapter) {
                existing.currentPageIndex.coerceIn(0, pageCount - 1)
            } else {
                0
            },
            importedAtMillis = existing?.importedAtMillis ?: now,
            lastOpenedAtMillis = now,
            fileSizeBytes = bytes.size.toLong(),
            folder = existing?.folder.orEmpty(),
            tags = existing?.tags.orEmpty(),
            bookmarks = if (sameChapter) existing.bookmarks else emptyList(),
            annotations = if (sameChapter) existing.annotations else emptyList(),
            remoteSourceType = remoteIdentity.sourceType,
            remoteAccountId = remoteIdentity.accountId,
            remoteSeriesId = remoteIdentity.seriesId,
            currentRemoteChapterId = remoteBook.identity.remoteId,
            currentChapterTitle = loaded.document.title,
            currentChapterNumber = remoteBook.chapterNumber,
        )

        writeBooks(
            books.filterNot {
                it.id == book.id || it.belongsTo(remoteIdentity) || it.id == existing?.id
            } + book,
        )
        return LocalLibraryOpenResult(
            book = book,
            loadedDocument = loaded,
            wasDuplicateImport = false,
        )
    }

    private fun readBooks(): List<LocalBook> {
        if (!metadataFile.exists()) return emptyList()
        return runCatching {
            LocalBookJson.decode(metadataFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun writeBooks(books: List<LocalBook>) {
        ensureDirectories()
        val tmpFile = File(libraryDir, "${metadataFile.name}.tmp")
        tmpFile.writeText(LocalBookJson.encode(books), Charsets.UTF_8)
        if (!tmpFile.renameTo(metadataFile)) {
            metadataFile.writeText(tmpFile.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmpFile.delete()
        }
    }

    private fun safeBookFile(book: LocalBook): File {
        val root = libraryDir.canonicalFile
        val file = File(root, book.relativePath).canonicalFile
        require(file.path.startsWith(root.path)) { "Invalid local book path." }
        return file
    }

    private fun ensureDirectories() {
        booksDir.mkdirs()
    }

    private fun sanitizeFileName(title: String, format: DocumentFormat): String {
        val fallbackExtension = when (format) {
            DocumentFormat.PDF -> ".pdf"
            DocumentFormat.EPUB -> ".epub"
            DocumentFormat.MARKDOWN -> ".md"
            DocumentFormat.TEXT -> ".txt"
        }
        val cleaned = title
            .ifBlank { "book$fallbackExtension" }
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .take(80)
            .ifBlank { "book$fallbackExtension" }
        return if (cleaned.contains('.')) cleaned else cleaned + fallbackExtension
    }
}
