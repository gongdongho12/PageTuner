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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PortableLibraryState(val entries: List<PortableLibraryEntry> = emptyList(), val busy: Boolean = false, val status: Int? = null,
    val error: String? = null, val glossaryPresence: BookGlossarySnapshotPresence? = null,
    val organizationPresence: LibraryOrganizationSnapshotPresence? = null,
    val progressPresence: ReadingProgressSnapshotPresence? = null)
data class PortableOpened(val entry: PortableLibraryEntry, val loaded: LoadedReaderDocument, val mapping: PortableReaderMapping,
    val pageIndex: Int, val bookmarks: List<ReaderBookmark>, val annotations: List<ReaderAnnotation>, val pdf: Boolean = false,
    val serverReading: ServerReadingDocument? = null, val characterOffset: Int = 0, val validateOpen: (() -> Unit)? = null)

class PortableLibraryViewModel(private val context: Context, private val local: LocalLibraryStore) : ViewModel() {
    private val bindings = PortableServerBindingStore(File(context.filesDir, "portable_server_bindings"))
    fun bindingSnapshot(accountKey: String, entry: PortableLibraryEntry) = textBindings.snapshot(accountKey, entry)
    fun saveBinding(entry: PortableLibraryEntry, value: PortableServerBinding, expected: PortableServerBindingSnapshot,
        current: () -> Unit) { glossaryAdoption.close(); textBindings.save(entry, value, expected, current) }
    fun removeBinding(accountKey: String, entry: PortableLibraryEntry) { glossaryAdoption.close(); bindings.remove(accountKey, entry) }

    private val store = PortableLibraryStore(File(context.filesDir, "portable_library"))
    private val textBindings = PortableTextBindingAccess(store, bindings)
    private val pdfBindings = PortablePdfBindingStore(store, File(context.filesDir, "portable_pdf_bindings"))
    val pdfStorage = PortablePdfStorage(viewModelScope, ::currentPdfContent, pdfBindings, ::openVerifiedPdf)
    private val mutableState = MutableStateFlow(PortableLibraryState())
    val state = mutableState.asStateFlow()
    private val mutableOpened = MutableSharedFlow<PortableOpened>(extraBufferCapacity = 1)
    val opened = mutableOpened.asSharedFlow()
    private val exportTickets = PortableExportTickets()
    private val fileExportSelection = PortableFileExportSelection()
    private val glossaryJournal = FileServerBookGlossaryStore(File(context.filesDir, "server-book-glossaries"))
    private val organizationJournal = FileServerLibraryOrganizationStore(File(context.filesDir, "server-library-organization"))
    private val progressJournal = FileServerReadingProgressStore(File(context.filesDir, "server-reading-progress"))
    val glossaryAdoption = PortableGlossaryAdoption(viewModelScope, ::currentDocument,
        { entry -> store.read(entry).documents[entry.documentIndex] }, bindings::read, glossaryJournal::read)
    private var exportConnection: ServerReadingConnection? = null
    @Volatile private var exportAuthority: PortableTextAccountAuthority? = null
    private class AccountExportReady(val request: PortableExportRequest, val check: () -> Unit)
    private val accountExportReady = AtomicReference<AccountExportReady?>(null)
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
            val loaded = loadPortablePdf(document, asset)
            try { guard() } catch (error: Throwable) { loaded.pdfSnapshot?.close(); throw error }
            val reader = loaded.document.copy(id = entry.readerId)
            val mapping = PortableReaderMapping(reader, List(reader.pageCount) { null })
            val pageState = PortablePageMetadata.read(document, mapping, pdf = true)
            PortableOpened(entry.copy(document = document), loaded.copy(document = reader), mapping, pageState.pageIndex ?: 0,
                pageState.bookmarks, pageState.annotations, pdf = true, validateOpen = guard)
        }
        guard()
        mutableOpened.emit(opened)
    }

    private fun loadPortablePdf(document: ExchangeDocument, asset: ExchangeAsset): LoadedReaderDocument {
        // Keep the old label only for reader/cache ID compatibility; the decoder receives actual ZIP bytes.
        val label = Uri.fromFile(File(context.cacheDir, "portable-pdf/${asset.sha256}.pdf")).toString()
        val decoded = PdfDocumentReader.readInput(context, PdfDecoderInput.capture(asset.bytes), label, document.bookTitle,
            context.getString(R.string.document_untitled))
        require(decoded.context.originalFileSha256 == asset.sha256)
        return LoadedReaderDocument(decoded.document, pdfSnapshot = decoded)
    }

    init { refresh() }
    fun connect(value: ServerReadingConnection?) {
        val previous = accountExportReady.get()
        glossaryAdoption.connect(value)
        exportTickets.connect(value)
        if (exportConnection != value) {
            exportConnection = value
            accountExportReady.compareAndSet(previous, null)
            mutableState.update { it.copy(glossaryPresence = null, organizationPresence = null, progressPresence = null, status = null) }
        }
    }
    /** A new publication expires old account exports even if its connection values are equal. */
    internal fun connectAuthority(value: PortableTextAccountAuthority?) {
        if (exportAuthority !== value) {
            val previous = accountExportReady.get()
            exportAuthority = value
            exportTickets.connect(null)
            accountExportReady.compareAndSet(previous, null)
            mutableState.update { it.copy(glossaryPresence = null, organizationPresence = null, progressPresence = null, status = null) }
        }
        connect(value?.connection)
    }
    internal fun matchesExportAuthority(value: PortableTextAccountAuthority?) = exportAuthority === value
    fun cancelExport(id: String) { exportTickets.cancel(id); clearAccountExportReady(id) }

    private fun clearAccountExportReady(id: String) {
        while (true) {
            val previous = accountExportReady.get()?.takeIf { it.request.id == id } ?: return
            if (accountExportReady.compareAndSet(previous, null)) return
        }
    }
    fun selectFileExport(key: String?) { fileExportSelection.select(key) }
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

    /** Factory only: the committed UI selection owns activation, callbacks and terminal disposal. */
    internal fun organizationAdoption(entry: PortableLibraryEntry, authority: PortableTextAccountAuthority,
        ticket: PortableTextOperationTicket): PortableOrganizationAdoptionCommand =
        PortableOrganizationAdoption(entry, authority, ticket, ::currentDocument, textBindings,
            textBindings::capture, organizationJournal)

    fun prepareNativeExport(book: LocalBook, translation: Boolean = false, settings: TranslationSettings? = null,
        cacheProviderId: String? = null, currentGlossary: BookGlossary? = null) = operation {
        val request = exportTickets.begin("PageTurner-${book.title.portableFilename()}.ptlibrary.zip")
        val result = if (book.format == DocumentFormat.PDF) local.preparePdfExport(book.id) else local.openBook(book.id)
        try {
            val document = result.loadedDocument.document
            if (translation && document.format == DocumentFormat.PDF) {
                requireNotNull(result.loadedDocument.pdfSnapshot).validateCompleteText(document)
            }
            val pdfBytes = if (document.format == DocumentFormat.PDF) {
                val uri = Uri.parse(requireNotNull(result.loadedDocument.pdfSourceUri))
                context.contentResolver.openInputStream(uri)?.use(::readPortableBytes) ?: error(context.getString(R.string.portable_error_asset))
            } else null
            val glossary = BookGlossaryStore(context).load(book.id)
            var value = PortableDocumentMapper.native(result.book, document, pdfBytes, glossary, result.loadedDocument.pdfSnapshot)
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
        } finally { result.loadedDocument.pdfSnapshot?.close() }
    }

    fun prepareDocumentFile(entry: PortableLibraryEntry, format: PortableDocumentFileFormat) {
        val current = fileExportSelection.guard("portable:${entry.key}")
        operation {
            val exportContext = currentCoroutineContext()
            val active = { exportContext.ensureActive(); current() }
            current()
            awaitReaderWrites(entry)
            current()
            val prepared = PortableDocumentFiles.fromArchive(store.read(entry), entry.documentIndex, format, active)
            publishDocumentFile(prepared, current)
        }
    }

    fun prepareNativeDocumentFile(book: LocalBook, format: PortableDocumentFileFormat) {
        val current = fileExportSelection.guard("native:${book.id}")
        operation {
            val exportContext = currentCoroutineContext()
            val active = { exportContext.ensureActive(); current() }
            current()
            val snapshot = requireNotNull(local.readSnapshot(book.id, LibraryExchangeLimits.ARCHIVE_BYTES)) {
                context.getString(R.string.document_file_source_changed)
            }
            require(snapshot.book.contentHash == book.contentHash && snapshot.book.format == book.format &&
                snapshot.book.currentRemoteChapterId == book.currentRemoteChapterId) {
                context.getString(R.string.document_file_source_changed)
            }
            current()
            val prepared = if (book.format == DocumentFormat.PDF) {
                require(format == PortableDocumentFileFormat.PDF) { context.getString(R.string.document_file_pdf_only) }
                val result = local.preparePdfExport(book.id)
                try {
                    val decoded = requireNotNull(result.loadedDocument.pdfSnapshot)
                    val bytes = decoded.exportOriginal(result.loadedDocument.document, snapshot.bytes)
                    PortableDocumentFiles.pdf(snapshot.book.title, snapshot.book.currentChapterTitle ?: snapshot.book.title, bytes)
                } finally { result.loadedDocument.pdfSnapshot?.close() }
            } else PortableDocumentFiles.fromNative(snapshot, format, active)
            current()
            publishDocumentFile(prepared, current)
        }
    }

    private suspend fun publishDocumentFile(value: PreparedDocumentFile, current: () -> Unit) {
        current()
        val request = exportTickets.begin(value.filename, mimeType = value.mimeType)
        try {
            exportTickets.prepare(request, value.bytes, null, current)
            current()
            mutableExportReady.emit(request)
        } catch (error: Exception) { exportTickets.cancel(request.id); throw error }
    }

    /** Future explicit upload UI must await queued reader edits before preparing the committed ZIP. */
    suspend fun currentPdfContent(entry: PortableLibraryEntry): ValidatedPdfContent {
        awaitReaderWrites(entry)
        return withContext(Dispatchers.IO) { store.preparePdfContent(entry) }
    }

    /** An explicit passive copy, with the same selection/account grant retained throughout SAF. */
    internal fun prepareAccountMetadataExport(entry: PortableLibraryEntry, targetLanguage: String?, includeOrganization: Boolean,
        includeProgress: Boolean, grant: PortableAccountExportGrant,
        checkSelection: () -> Unit) = operation(beforeStart = {
            check(exportAuthority === grant.authority)
            grant.checkCurrent(); checkSelection()
        }, reportError = {
            exportAuthority === grant.authority && runCatching { grant.checkCurrent(); checkSelection() }.isSuccess
        }) {
        val preparationContext = currentCoroutineContext()
        val connection = grant.authority.connection
        fun selection() {
            check(exportAuthority === grant.authority) { "The text account changed. Select the document again." }
            grant.checkCurrent()
            checkSelection()
            grant.checkCurrent()
        }
        preparationContext.ensureActive()
        selection()
        require(targetLanguage != null || includeOrganization || includeProgress) { "Select account data to include." }
        val request = exportTickets.begin("PageTurner-${entry.document.bookTitle.portableFilename()}-account.ptlibrary.zip", connection)
        val ready = AccountExportReady(request, ::selection)
        accountExportReady.set(ready)
        // This outer guard may acquire ticket/selection owners. Never pass it inside the progress reader's gate/journal checks.
        val current = { selection(); exportTickets.check(request, connection) }
        val readDocument = { selected: PortableLibraryEntry -> store.read(selected).documents[selected.documentIndex] }
        val progressExport = if (includeProgress) PortableAccountReadingProgressExport(entry, grant.authority, grant.ticket,
            ::currentDocument, textBindings, progressJournal, checkOperation = { grant.checkCurrent() }) else null
        try {
            current()
            val glossary = targetLanguage?.let { language -> PortableAccountGlossaryExport(::currentDocument,
                readDocument, { account, selected -> textBindings.capture(account, selected).binding },
                glossaryJournal::read).prepare(entry, language, connection, current) }
            preparationContext.ensureActive()
            current()
            val organization = if (includeOrganization) PortableAccountOrganizationExport(::currentDocument,
                readDocument, { account, selected -> textBindings.capture(account, selected).binding },
                organizationJournal::read).prepare(entry, connection, current) else null
            preparationContext.ensureActive()
            current()
            val progress = progressExport?.prepare()
            preparationContext.ensureActive()
            current()
            val prepared = combineAccountMetadataExports(glossary, organization, progress, current)
            val bytes = LibraryExchangeCodec.write(LibraryExchangePackage(portableTimestamp(), listOf(prepared.document)))
            preparationContext.ensureActive()
            current()
            exportTickets.prepare(request, bytes, connection, prepared.checkBeforeWrite)
            preparationContext.ensureActive()
            exportTickets.checkBeforeWrite(request.id) { connection }
            preparationContext.ensureActive()
            current()
            // update may retry after connectAuthority has reset the state; do not republish old presence then.
            mutableState.update { state ->
                if (exportAuthority === grant.authority && runCatching { grant.checkCurrent(); checkSelection() }.isSuccess)
                    state.copy(glossaryPresence = glossary?.snapshot?.presence, organizationPresence = organization?.snapshot?.presence,
                        progressPresence = progress?.snapshot?.presence)
                else state
            }
            preparationContext.ensureActive()
            current()
            mutableExportReady.emit(request)
        } catch (error: Exception) {
            progressExport?.close()
            exportTickets.cancel(request.id)
            accountExportReady.compareAndSet(ready, null)
            throw error
        }
    }

    /** Revalidate a delayed event before showing a picker. No memory ticket survives process recreation. */
    internal suspend fun checkExportReady(request: PortableExportRequest,
        currentConnection: () -> ServerReadingConnection?): Boolean = try {
        val collectorContext = currentCoroutineContext()
        withContext(Dispatchers.IO) {
            collectorContext.ensureActive()
            exportTickets.checkBeforeWrite(request.id) {
                collectorContext.ensureActive()
                currentConnection().also { collectorContext.ensureActive() }
            }
            collectorContext.ensureActive()
            true
        }
    } catch (cancelled: CancellationException) {
        cancelExport(request.id)
        throw cancelled
    } catch (_: Exception) {
        cancelExport(request.id)
        false
    }

    /** UI-dispatch return check only: no canonical IO, lifetime gate, or completed prepare job. */
    internal fun assertExportReadyCurrent(request: PortableExportRequest, currentConnection: () -> ServerReadingConnection?) {
        exportTickets.check(request, currentConnection())
        val ready = accountExportReady.get()?.takeIf { it.request == request }
        ready?.check?.invoke()
        exportTickets.check(request, currentConnection())
        // Account tickets are invalidated before their matching memory grant is removed on owner change.
        if (ready != null && accountExportReady.get() !== ready) throw PortableExportExpired()
    }

    fun writeExport(uri: Uri?, requestId: String?, currentConnection: () -> ServerReadingConnection?) {
        if (requestId == null) return
        if (uri == null) { cancelExport(requestId); return }
        val writerAuthority = exportAuthority
        val ready = accountExportReady.get()?.takeIf { it.request.id == requestId }
        fun stillCurrent() = exportAuthority === writerAuthority && runCatching { ready?.check?.invoke() }.isSuccess
        // A picker can return before its producer's finally clears busy. Keep this fixed callback,
        // rather than dropping it or looking up another request after the preparation completes.
        viewModelScope.launch {
            while (state.value.busy) state.first { !it.busy }
            var stale = false
            operation(beforeStart = {
                exportTickets.checkReadyId(requestId, currentConnection())
                ready?.check?.invoke()
            }, reportError = { !stale && stillCurrent() }) {
                val saved = try {
                    writePortableExport(exportTickets, requestId, currentConnection) {
                        context.contentResolver.openOutputStream(uri, "wt")
                    }
                } catch (expired: PortableExportExpired) {
                    stale = true
                    throw expired
                } finally {
                    clearAccountExportReady(requestId)
                }
                // Saving succeeded; an expired UI owner may suppress its notice, never roll it back.
                mutableState.update { current -> if (stillCurrent()) current.copy(
                    status = if (saved.mimeType == "application/zip") R.string.portable_exported else R.string.document_file_saved)
                    else current }
            }
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
                val guard = { textBindings.validate(currentEntry, binding) {
                    require(isCurrent()) { "The reading selection changed." }
                } }
                try {
                    guard()
                    currentConnection.client.verifyPortableIdentity(binding.recordId, binding.identity)
                    guard()
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
                    guard()
                    // A canonical projection has its own reader ID and server journals. ZIP notes and location stay untouched.
                    val reading = ServerReadingDocument.create(currentConnection.accountKey, source)
                    mutableOpened.emit(PortableOpened(currentEntry, LoadedReaderDocument(reading.mapping.document), reading.mapping,
                        0, emptyList(), emptyList(), serverReading = reading, validateOpen = guard))
                    return@operation
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (!isCurrent()) return@operation
                    if (error is PortableIdentityVerificationException && error.reason != PortableIdentityFailure.Unavailable ||
                        error is TranslationStoreException && error.failure == TranslationStoreFailure.NOT_FOUND) {
                        // A replaced/deleted archive or a newer binding must survive this old response.
                        runCatching { textBindings.removeIfCurrent(currentEntry, binding) }
                    }
                    throw error // Opening a saved binding cannot silently become an unrelated local reading session.
                }
            }
            if (originalPdf || document.paragraphs.isEmpty() && document.assets.any { it.role == "pdf" }) {
                val ref = requireNotNull(document.assets.firstOrNull { it.role == "pdf" })
                val asset = requireNotNull(value.assets.firstOrNull { it.path == ref.path })
                val loaded = loadPortablePdf(document, asset)
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

    private fun operation(beforeStart: (() -> Unit)? = null, reportError: (() -> Boolean)? = null, work: suspend () -> Unit) {
        var admitted = false
        mutableState.update { previous ->
            val admission = admitPortableOperation(previous, beforeStart)
            admitted = admission.admitted
            admission.state
        }
        if (!admitted) return
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { work() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (reportError == null || runCatching(reportError).getOrDefault(false))
                    mutableState.update { state -> if (reportError == null || runCatching(reportError).getOrDefault(false))
                        state.copy(error = if (error is PortableExportExpired) context.getString(R.string.portable_export_expired)
                            else error.message ?: context.getString(R.string.portable_error_read)) else state }
            }
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
