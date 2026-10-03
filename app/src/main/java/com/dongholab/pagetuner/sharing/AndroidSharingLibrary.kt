package com.dongholab.pagetuner.sharing

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.sharing.*
import com.dongholab.pagetuner.document.*
import com.dongholab.pagetuner.library.LocalLibraryStore
import com.dongholab.pagetuner.library.LocalBookSnapshotTooLargeException
import com.dongholab.pagetuner.portable.PortableDocumentMapper
import com.dongholab.pagetuner.portable.PortableLibraryStore
import com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Read-only bridge to the same stores used by the native app. No remote account or API request is made. */
class AndroidSharingLibrary(context: Context) : SharingLibrary {
    private val cacheDirectory = context.cacheDir
    private val local = LocalLibraryStore(context.applicationContext)
    private val portable = PortableLibraryStore(context.filesDir.resolve("portable_library"))
    private val offline = OfflineNovelStorageStore(context.applicationContext)
    private data class Entry(val summary: SharedBookSummary, val load: () -> SharedSnapshot)
    private val entries = SharingInventory<Entry>({ it.summary.id }, ::inventory)
    private val snapshots = linkedMapOf<String, SharedSnapshot>()

    @Synchronized override fun list(offset: Int, limit: Int): SharedLibraryPage {
        val inventory = entries.refresh()
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, LocalSharingContract.MAX_PAGE_SIZE)
        return SharedLibraryPage(inventory.drop(safeOffset).take(safeLimit).map { it.summary }, inventory.size, safeOffset, safeLimit)
    }

    @Synchronized override fun document(id: String): SharedDocument? {
        val entry = entries.find(id) ?: return null
        val snapshot = try { entry.load() }
        catch (error: SharingUnavailableException) { throw error }
        catch (_: LocalBookSnapshotTooLargeException) { throw SharingUnavailableException("document_too_large") }
        catch (_: EpubReadLimitException) { throw SharingUnavailableException("document_too_large") }
        catch (_: Exception) { throw SharingUnavailableException("document_unavailable") }
        snapshots.remove(id)
        while (snapshots.isNotEmpty() && (snapshots.size >= 3 || snapshots.values.sumOf { it.retainedBytes } + snapshot.retainedBytes > 64L * 1024 * 1024)) {
            snapshots.remove(snapshots.keys.first())
        }
        snapshots[id] = snapshot
        return snapshot.document
    }

    @Synchronized override fun asset(documentId: String, revision: String, assetId: String): SharedBinary? {
        val snapshot = snapshots[documentId] ?: run { document(documentId); snapshots[documentId] } ?: return null
        if (snapshot.document.revision != revision) throw SharingUnavailableException("document_changed")
        val descriptor = snapshot.document.assets.firstOrNull { it.id == assetId } ?: return null
        val bytes = snapshot.binaries[assetId] ?: return null
        return SharedBinary(descriptor.mimeType, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }

    private fun inventory(): List<Entry> {
        val native = runBlocking { local.listBooks() }.map { book ->
            val id = sharingId("native:${book.id}")
            val format = sharingFormat(book.format)
            Entry(SharedBookSummary(id, book.title, format, if (book.contentIsTranslated) "translation" else "original")) {
                val snapshot = runBlocking { local.readSnapshot(book.id, LocalSharingContract.MAX_ASSET_BYTES.toInt()) }
                    ?: throw SharingUnavailableException("document_unavailable")
                val title = snapshot.book.currentChapterTitle ?: snapshot.book.title
                if (snapshot.book.format == DocumentFormat.PDF) {
                    val asset = ExchangeAsset(snapshot.bytes, "application/pdf")
                    val pdf = sharedPdfDocument(id, snapshot.book.title, snapshot.book.contentLanguage ?: "und", snapshot.book.contentIsTranslated, asset,
                        pdfPages(snapshot.bytes), snapshot.book.currentPageIndex)
                    return@Entry sharedProjection(id, pdf, listOf(asset), "pdf")
                }
                val reader = when (snapshot.book.format) {
                    DocumentFormat.PDF -> ReaderDocument(snapshot.book.contentHash, title, DocumentFormat.PDF, emptyList())
                    DocumentFormat.EPUB -> {
                        validateSharingEpub(snapshot.bytes)
                        EpubDocumentReader.parse(title, snapshot.bytes, "Untitled", EpubReadLimits(4_000_000, 1024, 48L * 1024 * 1024))
                    }
                    else -> {
                        val text = snapshot.bytes.toString(Charsets.UTF_8)
                        if (text.length > LocalSharingContract.MAX_DOCUMENT_CHARACTERS) throw SharingUnavailableException("document_too_large")
                        PlainTextDocumentParser.parse(title, text, snapshot.book.format)
                    }
                }
                val exported = PortableDocumentMapper.native(snapshot.book, reader, snapshot.bytes.takeIf { snapshot.book.format == DocumentFormat.PDF })
                sharedProjection(id, exported.documents.single(), exported.assets, format)
            }
        }
        val archived = portable.listForSharing().map { entry ->
            val id = sharingId("portable:${entry.key}")
            val format = entry.format
            Entry(SharedBookSummary(id, entry.title, format, if (entry.translated) "translation" else "original")) {
                entry.errorCode?.let { throw SharingUnavailableException(it) }
                val value = portable.readForSharing(entry)
                val document = value.documents[entry.documentIndex]
                if (format == "pdf") {
                    val ref = requireNotNull(document.assets.firstOrNull { it.role == "pdf" })
                    val asset = requireNotNull(value.assets.firstOrNull { it.path == ref.path })
                    val pdf = sharedPortablePdfDocument(id, document, asset, pdfPages(asset.bytes))
                    sharedProjection(id, pdf, listOf(asset), "pdf")
                } else sharedProjection(id, document, value.assets, format)
            }
        }
        val downloaded = offline.listForSharing().flatMap { entry ->
            val editions = listOf("original" to entry.language) + entry.translatedLanguages.map { "translation" to it }
            editions.map { (edition, language) ->
                val id = sharingId("offline:${entry.identity}:$edition:$language")
                Entry(SharedBookSummary(id, entry.title, "txt", edition)) {
                    entry.errorCode?.let { throw SharingUnavailableException(it) }
                    val chapter = offline.readForSharing(entry.path) ?: throw SharingUnavailableException("document_unavailable")
                    val text = if (edition == "original") chapter.originalText else requireNotNull(chapter.translations.values.firstOrNull { it.language == language }).text
                    if (text.length > LocalSharingContract.MAX_DOCUMENT_CHARACTERS) throw SharingUnavailableException("document_too_large")
                    val parsed = PlainTextDocumentParser.parse(chapter.chapterTitle, text)
                    val paragraphs = parsed.pages.flatMap { it.segments }.map { ExchangeParagraph(it.id, it.text) }
                    sharedProjection(id, ExchangeDocument(id, chapter.chapterTitle, chapter.chapterTitle, language, edition, paragraphs), emptyList(), "txt")
                }
            }
        }
        return native + archived + downloaded
    }

    private fun pdfPages(bytes: ByteArray): Int {
        if (bytes.size.toLong() > LocalSharingContract.MAX_ASSET_BYTES) throw SharingUnavailableException("asset_too_large")
        val file = java.io.File.createTempFile("share-pdf-", ".pdf", cacheDirectory)
        try {
            file.writeBytes(bytes)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { it.pageCount } }
        } finally { file.delete() }
    }
}

private fun sharingFormat(format: DocumentFormat): String = when (format) {
    DocumentFormat.TEXT -> "txt"
    DocumentFormat.MARKDOWN -> "markdown"
    DocumentFormat.EPUB -> "epub"
    DocumentFormat.PDF -> "pdf"
}

/** Reject archive expansion before invoking the native EPUB parser. */
internal fun validateSharingEpub(bytes: ByteArray) {
    var total = 0L
    var count = 0
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        val buffer = ByteArray(8192)
        while (zip.nextEntry != null) {
            if (++count > 2048) throw SharingUnavailableException("document_too_large")
            var entrySize = 0L
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                total += read; entrySize += read
                if (total > 48L * 1024 * 1024 || entrySize > LocalSharingContract.MAX_ASSET_BYTES) throw SharingUnavailableException("asset_too_large")
            }
        }
    }
}

class AndroidSharingWebAssets(private val context: Context) : SharingWebAssets {
    override fun open(path: String): SharedBinary? {
        if (path.isBlank() || path.startsWith('/') || path.split('/').any { it == ".." || it == "." } || '\\' in path) return null
        val mime = when (path.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "wasm" -> "application/wasm"
            "bcmap", "pfb" -> "application/octet-stream"
            "ico" -> "image/x-icon"
            else -> return null
        }
        val bytes = runCatching { context.assets.open("local-sharing/$path").use { it.readBytes() } }.getOrNull() ?: return null
        return SharedBinary(mime, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }
}
