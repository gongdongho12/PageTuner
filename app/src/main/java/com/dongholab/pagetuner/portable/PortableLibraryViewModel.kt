package com.dongholab.pagetuner.portable

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.document.*
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.library.LocalLibraryStore
import com.dongholab.pagetuner.reader.*
import com.dongholab.pagetuner.translation.JsonFileTranslationCache
import com.dongholab.pagetuner.translation.TranslationSettings
import com.dongholab.pagetuner.translation.glossary.BookGlossary
import com.dongholab.pagetuner.translation.glossary.BookGlossaryStore
import com.dongholab.pagetuner.translation.sync.ServerDocumentMapping
import com.dongholab.pagetuner.translation.sync.ServerLibraryDocument
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PortableLibraryState(val entries: List<PortableLibraryEntry> = emptyList(), val busy: Boolean = false, val status: Int? = null, val error: String? = null)
data class PortableOpened(val entry: PortableLibraryEntry, val loaded: LoadedReaderDocument, val mapping: PortableReaderMapping,
    val pageIndex: Int, val bookmarks: List<ReaderBookmark>, val annotations: List<ReaderAnnotation>, val pdf: Boolean = false)

class PortableLibraryViewModel(private val context: Context, private val local: LocalLibraryStore) : ViewModel() {
    private val store = PortableLibraryStore(File(context.filesDir, "portable_library"))
    private val mutableState = MutableStateFlow(PortableLibraryState())
    val state = mutableState.asStateFlow()
    private val mutableOpened = MutableSharedFlow<PortableOpened>(extraBufferCapacity = 1)
    val opened = mutableOpened.asSharedFlow()
    private var preparedExport: ByteArray? = null
    private val readerGenerations = mutableMapOf<String, Long>()
    private val readerWrites = mutableMapOf<String, Deferred<Result<Unit>>>()
    private val mutableExportReady = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val exportReady = mutableExportReady.asSharedFlow()

    init { refresh() }
    fun refresh() = operation { mutableState.update { it.copy(entries = store.list()) } }

    fun importArchive(uri: Uri) = operation {
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readPortableBytes) ?: error(context.getString(R.string.portable_error_read))
        val result = store.importArchive(bytes)
        mutableState.update { it.copy(entries = store.list(), status = if (result.duplicate) R.string.portable_duplicate else R.string.portable_imported) }
    }

    /** Preserve server paragraph identities at download time, before the native text reader reflows them. */
    suspend fun retainServerDownload(value: ServerLibraryDocument) = withContext(Dispatchers.IO) {
        store.importArchive(LibraryExchangeCodec.write(PortableDocumentMapper.server(value)))
        mutableState.update { it.copy(entries = store.list()) }
    }

    fun prepareExport(entry: PortableLibraryEntry) {
        operation {
            awaitReaderWrites(entry)
            preparedExport = store.export(entry)
            mutableExportReady.emit("PageTurner-${entry.document.bookTitle.portableFilename()}.ptlibrary.zip")
        }
    }

    fun prepareNativeExport(book: LocalBook, translation: Boolean = false, settings: TranslationSettings? = null,
        cacheProviderId: String? = null, currentGlossary: BookGlossary? = null) = operation {
        val result = local.openBook(book.id)
        val document = result.loadedDocument.document
        val pdfBytes = if (document.format == DocumentFormat.PDF) {
            val uri = Uri.parse(requireNotNull(result.loadedDocument.pdfSourceUri))
            context.contentResolver.openInputStream(uri)?.use(::readPortableBytes) ?: error(context.getString(R.string.portable_error_asset))
        } else null
        val glossary = BookGlossaryStore(context).load(book.id)
        var value = PortableDocumentMapper.native(result.book, document, pdfBytes, glossary)
        if (translation) {
            val mapping = ServerDocumentMapping.create(document, requireNotNull(settings), currentGlossary, requireNotNull(cacheProviderId))
            val cached = JsonFileTranslationCache(context, book.relativePath).getMany(mapping.keys)
            require(cached.size == mapping.keys.size && mapping.keys.isNotEmpty()) { context.getString(R.string.portable_error_incomplete) }
            val artifact = mapping.toArtifact(cached)
            val original = value.documents.single()
            val translated = original.copy(id = "${original.id}:translation:${artifact.payloadHash}", language = artifact.targetLanguage, kind = "translation",
                paragraphs = artifact.paragraphs.map { ExchangeParagraph(it.paragraphId, it.text) },
                // Native notes are source-page based, not translated-text ranges.
                position = null, notes = emptyList(), assets = emptyList(),
                extensionsJson = JSONObject().put("translation", JSONObject().put("sourceDocumentId", original.id)
                    .put("sourceRevision", artifact.sourceRevision).put("providerId", artifact.providerId)
                    .put("sourceLanguage", artifact.sourceLanguage).put("targetLanguage", artifact.targetLanguage)
                    .put("modelId", artifact.modelId).put("promptRevision", artifact.promptRevision).put("glossaryRevision", artifact.glossaryRevision)).toString())
            value = value.copy(documents = value.documents + translated)
        }
        preparedExport = LibraryExchangeCodec.write(value)
        mutableExportReady.emit("PageTurner-${book.title.portableFilename()}.ptlibrary.zip")
    }

    fun writeExport(uri: Uri?) {
        if (uri == null) { preparedExport = null; return }
        operation {
            val bytes = requireNotNull(preparedExport)
            try { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error(context.getString(R.string.portable_error_write)) }
            finally { preparedExport = null }
            mutableState.update { it.copy(status = R.string.portable_exported) }
        }
    }

    fun open(entry: PortableLibraryEntry, originalPdf: Boolean = false) {
        operation {
            awaitReaderWrites(entry)
            val value = store.read(entry)
            val document = value.documents[entry.documentIndex]
            val currentEntry = entry.copy(document = document)
            if (originalPdf || document.paragraphs.isEmpty() && document.assets.any { it.role == "pdf" }) {
                val ref = requireNotNull(document.assets.firstOrNull { it.role == "pdf" })
                val asset = requireNotNull(value.assets.firstOrNull { it.path == ref.path })
                val file = File(context.cacheDir, "portable-pdf/${asset.sha256}.pdf")
                file.parentFile?.mkdirs()
                if (!file.exists() || DocumentIds.sha256(file.readBytes()) != asset.sha256) file.writeBytes(asset.bytes)
                val loaded = context.readReaderDocument(Uri.fromFile(file), document.bookTitle, DocumentFormat.PDF)
                val reader = loaded.document.copy(id = entry.readerId)
                val mapping = PortableReaderMapping(reader, List(reader.pageCount) { null })
                val pageState = PortablePageMetadata.read(document, mapping, pdf = true)
                mutableOpened.emit(PortableOpened(currentEntry, loaded.copy(document = reader), mapping, pageState.pageIndex ?: 0,
                    pageState.bookmarks, pageState.annotations, true))
            } else {
                val mapping = PortableDocumentMapper.reader(document, value.assets, entry.readerId)
                val pageState = PortablePageMetadata.read(document, mapping, pdf = false)
                mutableOpened.emit(PortableOpened(currentEntry, LoadedReaderDocument(mapping.document), mapping,
                    pageState.pageIndex ?: document.position?.let(mapping::pageFor) ?: 0,
                    PortableDocumentMapper.bookmarks(document, mapping) + pageState.bookmarks,
                    PortableDocumentMapper.annotations(document, mapping) + pageState.annotations))
            }
        }
    }

    fun persistReader(opened: PortableOpened, pageIndex: Int, bookmarks: List<ReaderBookmark>, annotations: List<ReaderAnnotation>) {
        val generation = synchronized(readerGenerations) {
            (readerGenerations.getOrDefault(opened.entry.key, 0L) + 1L).also { readerGenerations[opened.entry.key] = it }
        }
        val write = viewModelScope.async {
            try {
                withContext(Dispatchers.IO) {
                    store.update(opened.entry) { value ->
                        if (synchronized(readerGenerations) { readerGenerations[opened.entry.key] } != generation) return@update value
                        val pageState = PortablePageMetadata.merge(value, opened.mapping, pageIndex, bookmarks, annotations, opened.pdf)
                        if (opened.pdf) pageState else PortableDocumentMapper.mergeReader(pageState, opened.mapping, pageIndex, bookmarks, annotations)
                    }
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.update { it.copy(error = error.message ?: context.getString(R.string.portable_error_write)) }
                Result.failure(error)
            }
        }
        synchronized(readerWrites) { readerWrites[opened.entry.key] = write }
    }

    private suspend fun awaitReaderWrites(entry: PortableLibraryEntry) {
        while (true) {
            val pending = synchronized(readerWrites) { readerWrites[entry.key] } ?: return
            pending.await().getOrThrow()
            // A later page/annotation update may have superseded the write while it was pending.
            if (synchronized(readerWrites) { readerWrites[entry.key] } === pending) return
        }
    }

    private fun operation(work: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, status = null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { work() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(error = error.message ?: context.getString(R.string.portable_error_read)) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    class Factory(private val context: Context, private val local: LocalLibraryStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PortableLibraryViewModel(context.applicationContext, local) as T
    }
}

internal fun String.portableFilename(): String {
    val normalized = replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
    var end = minOf(normalized.length, 80)
    if (end < normalized.length && end > 0 && normalized[end - 1].isHighSurrogate() && normalized[end].isLowSurrogate()) end--
    return normalized.substring(0, end).ifBlank { "library" }
}
