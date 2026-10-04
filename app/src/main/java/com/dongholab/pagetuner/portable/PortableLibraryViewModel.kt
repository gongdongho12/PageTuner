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
import com.dongholab.pagetuner.translation.sync.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PortableLibraryState(val entries: List<PortableLibraryEntry> = emptyList(), val busy: Boolean = false, val status: Int? = null,
    val error: String? = null, val glossaryPresence: BookGlossarySnapshotPresence? = null)
data class PortableOpened(val entry: PortableLibraryEntry, val loaded: LoadedReaderDocument, val mapping: PortableReaderMapping,
    val pageIndex: Int, val bookmarks: List<ReaderBookmark>, val annotations: List<ReaderAnnotation>, val pdf: Boolean = false,
    val serverReading: ServerReadingDocument? = null, val characterOffset: Int = 0, val validateOpen: (() -> Unit)? = null)

class PortableLibraryViewModel(private val context: Context, private val local: LocalLibraryStore) : ViewModel() {
    private val bindings = PortableServerBindingStore(File(context.filesDir, "portable_server_bindings"))
    fun saveBinding(value: PortableServerBinding) { glossaryAdoption.close(); bindings.save(value) }
    fun removeBinding(accountKey: String, entry: PortableLibraryEntry) { glossaryAdoption.close(); bindings.remove(accountKey, entry) }

    private val store = PortableLibraryStore(File(context.filesDir, "portable_library"))
    private val pdfBindings = PortablePdfBindingStore(store, File(context.filesDir, "portable_pdf_bindings"))
    val pdfStorage = PortablePdfStorage(viewModelScope, ::currentPdfContent, pdfBindings, ::openVerifiedPdf)
    private val mutableState = MutableStateFlow(PortableLibraryState())
    val state = mutableState.asStateFlow()
    private val mutableOpened = MutableSharedFlow<PortableOpened>(extraBufferCapacity = 1)
    val opened = mutableOpened.asSharedFlow()
    private val exportTickets = PortableExportTickets()
    private val glossaryJournal = FileServerBookGlossaryStore(File(context.filesDir, "server-book-glossaries"))
    val glossaryAdoption = PortableGlossaryAdoption(viewModelScope, ::currentDocument,
        { entry -> store.read(entry).documents[entry.documentIndex] }, bindings::read, glossaryJournal::read)
    private var exportConnection: ServerReadingConnection? = null
    private val readerGenerations = mutableMapOf<String, Long>()
    private val readerWrites = mutableMapOf<String, Deferred<Result<Unit>>>()
    private val mutableExportReady = MutableSharedFlow<PortableExportRequest>(extraBufferCapacity = 1)
    val exportReady = mutableExportReady.asSharedFlow()

    private suspend fun openVerifiedPdf(entry: PortableLibraryEntry, archive: LibraryExchangePackage, guard: () -> Unit) {
        val opened = withContext(Dispatchers.IO) {
            guard()
            val document = archive.documents[entry.documentIndex]
            val ref = document.assets.single { it.role == "pdf" }
            val asset = archive.assets.single { it.path == ref.path }
            val file = File(context.cacheDir, "portable-pdf/${asset.sha256}.pdf")
            file.parentFile?.mkdirs()
            val cachedHash = if (file.exists() && file.length() <= PdfContentValidation.MAX_PAYLOAD_BYTES) runCatching {
                file.inputStream().use { DocumentIds.sha256(readPortableBytes(it, PdfContentValidation.MAX_PAYLOAD_BYTES)) }
            }.getOrNull() else null
            if (cachedHash != asset.sha256) {
                val temporary = File(file.parentFile, "${java.util.UUID.randomUUID()}.tmp")
                try {
                    temporary.writeBytes(asset.bytes)
                    guard()
                    com.dongholab.pagetuner.storage.replaceFileAtomically(temporary, file)
                } finally { temporary.delete() }
            }
            guard()
            val loaded = context.readReaderDocument(Uri.fromFile(file), document.bookTitle, DocumentFormat.PDF)
            guard()
            val reader = loaded.document.copy(id = entry.readerId)
            val mapping = PortableReaderMapping(reader, List(reader.pageCount) { null })
            val pageState = PortablePageMetadata.read(document, mapping, pdf = true)
            PortableOpened(entry.copy(document = document), loaded.copy(document = reader), mapping, pageState.pageIndex ?: 0,
                pageState.bookmarks, pageState.annotations, pdf = true, validateOpen = guard)
        }
        guard()
        mutableOpened.emit(opened)
    }

    init { refresh() }
    fun connect(value: ServerReadingConnection?) {
        glossaryAdoption.connect(value)
        exportTickets.connect(value)
        if (exportConnection != value) {
            exportConnection = value
            mutableState.update { it.copy(glossaryPresence = null, status = null) }
        }
    }
    fun cancelExport(id: String) { exportTickets.cancel(id) }
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
            val request = exportTickets.begin("PageTurner-${entry.document.bookTitle.portableFilename()}.ptlibrary.zip")
            awaitReaderWrites(entry)
            exportTickets.prepare(request, store.export(entry), null)
            mutableExportReady.emit(request)
        }
    }

    /** Re-read the validated archive after pending reader writes before any server comparison. */
    suspend fun currentDocument(entry: PortableLibraryEntry): ExchangeDocument {
        awaitReaderWrites(entry)
        return withContext(Dispatchers.IO) { store.read(entry).documents[entry.documentIndex] }
    }

    fun prepareNativeExport(book: LocalBook, translation: Boolean = false, settings: TranslationSettings? = null,
        cacheProviderId: String? = null, currentGlossary: BookGlossary? = null) = operation {
        val request = exportTickets.begin("PageTurner-${book.title.portableFilename()}.ptlibrary.zip")
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
        exportTickets.prepare(request, LibraryExchangeCodec.write(value), null)
        mutableExportReady.emit(request)
    }

    /** Future explicit upload UI must await queued reader edits before preparing the committed ZIP. */
    suspend fun currentPdfContent(entry: PortableLibraryEntry): ValidatedPdfContent {
        awaitReaderWrites(entry)
        return withContext(Dispatchers.IO) { store.preparePdfContent(entry) }
    }

    /** The account connection is read live after every suspension and again when SAF returns. */
    fun prepareAccountGlossaryExport(entry: PortableLibraryEntry, targetLanguage: String, connection: ServerReadingConnection,
        currentConnection: () -> ServerReadingConnection?) = operation {
        val request = exportTickets.begin("PageTurner-${entry.document.bookTitle.portableFilename()}-account-glossary.ptlibrary.zip", connection)
        val adapter = PortableAccountGlossaryExport(::currentDocument,
            { selected -> store.read(selected).documents[selected.documentIndex] }, bindings::read, glossaryJournal::read)
        try {
            val prepared = adapter.prepare(entry, targetLanguage, connection) { exportTickets.check(request, currentConnection()) }
            val bytes = LibraryExchangeCodec.write(LibraryExchangePackage(portableTimestamp(), listOf(prepared.document)))
            exportTickets.prepare(request, bytes, currentConnection(), prepared.checkBeforeWrite)
            exportTickets.check(request, currentConnection())
            mutableState.update { it.copy(glossaryPresence = prepared.snapshot.presence) }
            mutableExportReady.emit(request)
        } catch (error: Exception) { exportTickets.cancel(request.id); throw error }
    }

    fun writeExport(uri: Uri?, requestId: String?, currentConnection: () -> ServerReadingConnection?) {
        if (requestId == null) return
        if (uri == null) { exportTickets.cancel(requestId); return }
        operation {
            exportTickets.write(requestId, currentConnection) { context.contentResolver.openOutputStream(uri, "wt") }
            mutableState.update { it.copy(status = R.string.portable_exported) }
        }
    }

    fun open(entry: PortableLibraryEntry, originalPdf: Boolean = false, connection: ServerReadingConnection? = null,
        isCurrent: () -> Boolean = { true }) {
        operation {
            awaitReaderWrites(entry)
            val value = store.read(entry)
            val document = value.documents[entry.documentIndex]
            val currentEntry = entry.copy(document = document)
            val binding = connection?.let { bindings.read(it.accountKey, currentEntry) }
            if (connection != null && binding == null) error(context.getString(R.string.portable_identity_binding_required))
            if (!originalPdf && binding != null) {
                val currentConnection = requireNotNull(connection)
                try {
                    currentConnection.client.verifyPortableIdentity(binding.recordId, binding.identity)
                    val source = if (binding.identity.kind == DocumentIdentityKind.ORIGINAL) currentConnection.client.original(binding.recordId)
                    else {
                        val stored = currentConnection.client.get(binding.recordId)
                        val artifact = stored.artifact
                        ServerLibraryDocument(ServerLibraryEntry(binding.recordId, document.bookTitle, artifact.targetLanguage,
                            artifact.paragraphs.size, artifact.sourceRevision, ServerLibraryKind.Translations),
                            artifact.paragraphs.map { it.text }, stored)
                    }
                    require(DocumentIdentityJson.fromDocument(PortableDocumentMapper.server(source).documents.single()) == binding.identity)
                    if (!isCurrent()) return@operation
                    // A canonical projection has its own reader ID and server journals. ZIP notes and location stay untouched.
                    val reading = ServerReadingDocument.create(currentConnection.accountKey, source)
                    mutableOpened.emit(PortableOpened(currentEntry, LoadedReaderDocument(reading.mapping.document), reading.mapping,
                        0, emptyList(), emptyList(), serverReading = reading))
                    return@operation
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (!isCurrent()) return@operation
                    if (error is PortableIdentityVerificationException && error.reason != PortableIdentityFailure.Unavailable ||
                        error is TranslationStoreException && error.failure == TranslationStoreFailure.NOT_FOUND) bindings.remove(currentConnection.accountKey, currentEntry)
                    throw error // Opening a saved binding cannot silently become an unrelated local reading session.
                }
            }
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
                val position = mapping.initialPosition(document.position, pageState.pageIndex)
                mutableOpened.emit(PortableOpened(currentEntry, LoadedReaderDocument(mapping.document), mapping,
                    position.pageIndex,
                    PortableDocumentMapper.bookmarks(document, mapping) + pageState.bookmarks,
                    PortableDocumentMapper.annotations(document, mapping) + pageState.annotations, characterOffset = position.characterOffset))
            }
        }
    }

    fun persistReader(opened: PortableOpened, pageIndex: Int, bookmarks: List<ReaderBookmark>, annotations: List<ReaderAnnotation>,
        characterOffset: Int? = null) {
        val generation = synchronized(readerGenerations) {
            ((readerGenerations[opened.entry.key] ?: 0L) + 1L).also { readerGenerations[opened.entry.key] = it }
        }
        val write = viewModelScope.async {
            try {
                withContext(Dispatchers.IO) {
                    store.update(opened.entry) { value ->
                        if (synchronized(readerGenerations) { readerGenerations[opened.entry.key] } != generation) return@update value
                        val pageState = PortablePageMetadata.merge(value, opened.mapping, pageIndex, bookmarks, annotations, opened.pdf)
                        if (opened.pdf) pageState else PortableDocumentMapper.mergeReader(pageState, opened.mapping, pageIndex, bookmarks, annotations, characterOffset)
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
        mutableState.update { it.copy(busy = true, error = null, status = null, glossaryPresence = null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { work() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(error = if (error is PortableExportExpired)
                context.getString(R.string.portable_export_expired) else error.message ?: context.getString(R.string.portable_error_read)) } }
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
