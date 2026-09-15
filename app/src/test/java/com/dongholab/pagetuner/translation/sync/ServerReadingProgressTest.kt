package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerReadingProgressTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val timestamp = "2026-09-16T00:00:00Z"

    @Test fun getRestoresExactParagraphWithoutPublishingTheInitialOrRestoredPage() = runTest {
        val store = MemoryStore()
        var writes = 0
        val connection = connection { request ->
            if (request.method == "PUT") writes++
            response(progress(4, "p3", 2))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        val document = document()
        sync.open(document, connection, 0)
        sync.pageChanged(document.readerId, 0)
        val state = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(2, state.restore!!.pageIndex)
        sync.pageChanged(document.readerId, 2)
        advanceTimeBy(1000); runCurrent()
        assertEquals(0, writes)
        assertNull(store.records[document.key]!!.pending)
        // The restored intra-paragraph offset is retained until the user actually moves pages.
        assertEquals(ServerReadingAnchor("p3", 2), store.records[document.key]!!.remote!!.anchor)
    }

    @Test fun userMovementDuringFetchIsKeptAndConflictsInsteadOfBeingOverwritten() = runTest {
        val getEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val connection = connection { request ->
            if (request.method == "GET") { getEntered.complete(Unit); release.await(); response(progress(2, "p4")) }
            else conflict(progress(2, "p4"))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        val document = document()
        sync.open(document, connection, 0); getEntered.await()
        sync.pageChanged(document.readerId, 1)
        store.await { it.pending != null }
        release.complete(Unit)
        val state = sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        assertNull(state.restore)
        assertEquals(1, state.localPage)
        assertEquals(3, state.serverPage)
        assertEquals(0L, store.records[document.key]!!.pending!!.expectedVersion)
    }

    @Test fun userGoingBackToTheFirstPageBeforeComposeObservesItPreventsLateRestore() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val connection = connection { request ->
            if (request.method == "GET") { entered.complete(Unit); release.await(); response(progress(4, "p4")) }
            else response(progress(5, "p1"))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        val document = document()
        val reader = com.dongholab.pagetuner.reader.ReaderViewModel(document.mapping.document)
        sync.open(document, connection, 0); entered.await()
        reader.changePage(1); reader.changePage(0)
        release.complete(Unit)
        val state = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertFalse(applyServerReadingProgressRestore(reader, sync, state.restore!!))
        assertEquals(0, reader.uiState.value.safePageIndex)
        store.await { it.pending?.anchor == ServerReadingAnchor("p1", 0) }
        assertEquals(4L, store.records[document.key]!!.pending!!.expectedVersion)
    }

    @Test fun delayedCallbacksFromTwoProgrammaticRestoresCannotPublishTheOlderPosition() = runTest {
        val document = document()
        val store = MemoryStore().apply { records[document.key] = DeviceReadingProgress(progress(1, "p2")) }
        var puts = 0
        val connection = connection { request -> if (request.method == "PUT") puts++; response(progress(2, "p4")) }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        sync.open(document, connection, 0)
        sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.pageChanged(document.readerId, 1, revision = 0)
        sync.pageChanged(document.readerId, 3, revision = 0)
        advanceTimeBy(1000); runCurrent()
        assertEquals(0, puts)
        assertNull(store.records[document.key]!!.pending)
        assertEquals("p4", store.records[document.key]!!.remote!!.anchor!!.paragraphId)
    }

    @Test fun timeoutAndReopenReplayTheSameMutationBeforeNewerQueuedPosition() = runTest {
        val store = MemoryStore()
        val mutations = mutableListOf<ServerReadingMutation>()
        var timeout = true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connection = connection { request ->
            if (request.method == "GET") response(progress(0))
            else {
                val mutation = ServerReadingProgressJson.mutation(JSONObject(request.body!!))
                mutations += mutation
                if (timeout) { entered.complete(Unit); release.await(); throw SocketTimeoutException() }
                response(progress(mutation.expectedVersion + 1, mutation.anchor.paragraphId, mutation.anchor.characterOffset))
            }
        }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        val document = document()
        sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.pageChanged(document.readerId, 1); sync.state.first { it.phase == ReadingProgressPhase.Pending }
        advanceTimeBy(701); runCurrent(); entered.await()
        sync.pageChanged(document.readerId, 2); store.await { it.queuedAnchor != null }
        release.complete(Unit); sync.state.first { it.phase == ReadingProgressPhase.Offline }
        sync.connect(null); sync.state.first { it.phase == ReadingProgressPhase.Inactive }
        timeout = false
        sync.open(document, connection, 0)
        sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(3, mutations.size)
        assertEquals(mutations[0], mutations[1])
        assertEquals(1L, mutations[2].expectedVersion)
        assertEquals("p3", mutations[2].anchor.paragraphId)
        assertNotEquals(mutations[0].mutationId, mutations[2].mutationId)
        assertNull(store.records[document.key]!!.pending)
        assertEquals("p3", store.records[document.key]!!.remote!!.anchor!!.paragraphId)
    }

    @Test fun rapidUnsentPageChangesPublishOnlyTheLatestPosition() = runTest {
        val store = MemoryStore()
        val mutations = mutableListOf<ServerReadingMutation>()
        val connection = connection { request ->
            if (request.method == "GET") response(progress(0)) else {
                val mutation = ServerReadingProgressJson.mutation(JSONObject(request.body!!))
                mutations += mutation
                response(progress(1, mutation.anchor.paragraphId))
            }
        }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        val document = document()
        sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.pageChanged(document.readerId, 1); store.await { it.pending?.anchor?.paragraphId == "p2" }
        sync.pageChanged(document.readerId, 2); store.await { it.pending?.anchor?.paragraphId == "p3" }
        // Wait until the serialized journal has also scheduled the debounce timer.
        runCurrent(); advanceTimeBy(701); runCurrent()
        sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(1, mutations.size)
        assertEquals("p3", mutations.single().anchor.paragraphId)
    }

    @Test fun closingReaderFlushesLatestPendingPageWithoutReopeningTheBook() = runTest {
        val store = MemoryStore()
        val published = CompletableDeferred<ServerReadingMutation>()
        val connection = connection { request ->
            if (request.method == "GET") response(progress(0)) else {
                val mutation = ServerReadingProgressJson.mutation(JSONObject(request.body!!))
                published.complete(mutation)
                response(progress(1, mutation.anchor.paragraphId))
            }
        }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        val document = document()
        sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.pageChanged(document.readerId, 1); store.await { it.pending != null }
        sync.close()
        assertEquals("p2", published.await().anchor.paragraphId)
        store.await { it.pending == null && it.remote?.version == 1L }
        assertEquals(ReadingProgressPhase.Inactive, sync.state.value.phase)
    }

    @Test fun accountLoginDrainsOnlyItsDurableOutboxWithoutLoadingAnyBook() = runTest {
        val document = document()
        val mutation = ServerReadingMutation(3, UUID.randomUUID().toString(), ServerReadingAnchor("p2", 2))
        val store = MemoryStore().apply { records[document.key] = DeviceReadingProgress(progress(3, "p1"), mutation, ServerReadingAnchor("p3", 0)) }
        val mutations = mutableListOf<ServerReadingMutation>()
        val sync = ServerReadingProgressSync(backgroundScope, store)
        sync.connect(connection("https://reader.example", "other") { error("Another account must not publish this outbox") })
        store.scans.receive()
        val connection = connection { request ->
            assertEquals("PUT", request.method)
            val pending = ServerReadingProgressJson.mutation(JSONObject(request.body!!)); mutations += pending
            response(progress(pending.expectedVersion + 1, pending.anchor.paragraphId, pending.anchor.characterOffset))
        }
        sync.connect(connection)
        store.await { it.remote?.version == 5L && it.pending == null }
        assertEquals(2, mutations.size)
        assertEquals(mutation, mutations.first())
        assertEquals("p3", mutations.last().anchor.paragraphId)
        assertNull(sync.state.value.readerId)
    }

    @Test fun backgroundConflictKeepsBothPositionsUntilTheBookIsOpenedAndResolved() = runTest {
        val document = document()
        val pending = ServerReadingMutation(1, UUID.randomUUID().toString(), ServerReadingAnchor("p2", 0))
        val store = MemoryStore().apply { records[document.key] = DeviceReadingProgress(progress(1, "p1"), pending) }
        var calls = 0
        val connection = connection { calls++; conflict(progress(2, "p4")) }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        sync.connect(connection)
        store.await { it.conflict != null }
        sync.open(document, connection, 0)
        val state = sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        assertEquals(1, state.localPage)
        assertEquals(3, state.serverPage)
        assertEquals(1, calls)
        assertEquals(pending, store.records[document.key]!!.pending)
    }

    @Test fun loggingOutDuringOutboxSendRetainsTheMutationForNextLogin() = runTest {
        val document = document()
        val pending = ServerReadingMutation(1, UUID.randomUUID().toString(), ServerReadingAnchor("p2", 0))
        val store = MemoryStore().apply { records[document.key] = DeviceReadingProgress(progress(1, "p1"), pending) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val connection = connection {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }; exited.complete(Unit)
            response(progress(2, "p2"))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        sync.connect(connection); entered.await()
        sync.connect(null); runCurrent()
        release.complete(Unit); exited.await(); runCurrent()
        assertEquals(pending, store.records[document.key]!!.pending)
        sync.connect(connection { request ->
            assertEquals(pending, ServerReadingProgressJson.mutation(JSONObject(request.body!!)))
            response(progress(2, "p2"))
        })
        store.await { it.pending == null }
        assertEquals(2L, store.records[document.key]!!.remote!!.version)
    }

    @Test fun foregroundRateLimitSurvivesReopenAndManualRetryCannotShortenItsDeadline() = runTest {
        val document = document()
        val store = MemoryStore()
        val mutations = mutableListOf<ServerReadingMutation>()
        val connection = connection { request ->
            if (request.method == "GET") response(progress(0)) else {
                mutations += ServerReadingProgressJson.mutation(JSONObject(request.body!!))
                if (mutations.size == 1) TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf("60")))
                else response(progress(1, "p2"))
            }
        }
        val sync = ServerReadingProgressSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.pageChanged(document.readerId, 1); sync.state.first { it.phase == ReadingProgressPhase.Pending }
        advanceTimeBy(701); runCurrent()
        sync.state.first { it.phase == ReadingProgressPhase.RateLimited }
        val deadline = store.records[document.key]!!.retryAfterUntil!!
        sync.retry(); advanceTimeBy(1000); runCurrent()
        assertEquals(1, mutations.size)
        sync.connect(null); sync.state.first { it.phase == ReadingProgressPhase.Inactive }
        sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.RateLimited }
        advanceTimeBy(deadline - testScheduler.currentTime - 1); runCurrent()
        assertEquals(1, mutations.size)
        advanceTimeBy(1); runCurrent()
        sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(2, mutations.size)
        assertEquals(mutations.first(), mutations.last())
        assertNull(store.records[document.key]!!.retryAfterUntil)
    }

    @Test fun backgroundLoginHonorsPersistedRateLimitBeforeReplayingTheSameMutation() = runTest {
        val document = document()
        val pending = ServerReadingMutation(0, UUID.randomUUID().toString(), ServerReadingAnchor("p2", 0))
        val store = MemoryStore().apply { records[document.key] = DeviceReadingProgress(pending = pending, retryAfterUntil = 60_000) }
        var calls = 0
        val connection = connection { request ->
            calls++; assertEquals(pending, ServerReadingProgressJson.mutation(JSONObject(request.body!!)))
            response(progress(1, "p2"))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(connection); store.scans.receive(); runCurrent()
        sync.retry(); advanceTimeBy(59_999); runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(15_001); runCurrent()
        store.await { it.pending == null }
        assertEquals(1, calls)
    }

    @Test fun terminalOutboxFailuresWaitForANewConnectionOrExplicitRetry() = runTest {
        for (status in listOf(401, 403, 404, 400)) {
            val document = document()
            val pending = ServerReadingMutation(0, UUID.randomUUID().toString(), ServerReadingAnchor("p2", 0))
            val store = MemoryStore().apply { records[document.key] = DeviceReadingProgress(pending = pending) }
            var calls = 0
            val entered = Channel<Unit>(Channel.UNLIMITED)
            val connection = connection { calls++; entered.trySend(Unit); TranslationStoreHttpResponse(status) }
            val sync = ServerReadingProgressSync(backgroundScope, store) { testScheduler.currentTime }
            sync.connect(connection); entered.receive()
            // pumpOutbox scans once after suppressing this terminal failure.
            store.scans.receive(); store.scans.receive()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, calls)
            assertEquals(pending, store.records[document.key]!!.pending)
            sync.retry(); entered.receive()
            assertEquals(2, calls)
            sync.connect(null); runCurrent()
        }
    }

    @Test fun terminalForegroundFailureKeepsNewPagesLocallyUntilReopenOrExplicitRetry() = runTest {
        for (status in listOf(401, 403, 404, 400)) {
            val document = document()
            val store = MemoryStore()
            val mutations = mutableListOf<ServerReadingMutation>()
            var allowSuccess = false
            val connection = connection { request ->
                if (request.method == "GET") response(progress(0)) else {
                    val pending = ServerReadingProgressJson.mutation(JSONObject(request.body!!))
                    mutations += pending
                    if (allowSuccess) response(progress(pending.expectedVersion + 1, pending.anchor.paragraphId, pending.anchor.characterOffset))
                    else TranslationStoreHttpResponse(status)
                }
            }
            val sync = ServerReadingProgressSync(backgroundScope, store) { testScheduler.currentTime }
            sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.Synced }
            sync.pageChanged(document.readerId, 1); sync.state.first { it.phase == ReadingProgressPhase.Pending }
            advanceTimeBy(701); runCurrent()
            sync.state.first { it.phase == ReadingProgressPhase.Unavailable }
            val originalMutation = mutations.single()
            sync.pageChanged(document.readerId, 2)
            sync.pageChanged(document.readerId, 3)
            store.await { it.queuedAnchor?.paragraphId == "p4" }
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, mutations.size)
            assertEquals(ReadingProgressPhase.Unavailable, sync.state.value.phase)
            assertEquals(originalMutation, store.records[document.key]!!.pending)
            sync.close(); sync.state.first { it.phase == ReadingProgressPhase.Inactive }
            advanceTimeBy(15_000); runCurrent()
            assertEquals(1, mutations.size)

            // Explicit reopening permits one new attempt with the same uncertain mutation.
            sync.open(document.copy(openId = UUID.randomUUID().toString()), connection, 3)
            sync.state.first { it.phase == ReadingProgressPhase.Unavailable }
            assertEquals(listOf(originalMutation, originalMutation), mutations)
            allowSuccess = true
            sync.retry(); sync.state.first { it.phase == ReadingProgressPhase.Synced }
            assertEquals(4, mutations.size)
            assertEquals(originalMutation, mutations[2])
            assertEquals("p4", mutations[3].anchor.paragraphId)
            assertEquals(1L, mutations[3].expectedVersion)
            assertNull(store.records[document.key]!!.pending)
            sync.connect(null); runCurrent()
        }
    }

    @Test fun conflictIsPersistedAcrossReopenAndBothResolutionsAreExplicit() = runTest {
        val document = document()
        val local = ServerReadingMutation(1, UUID.randomUUID().toString(), ServerReadingAnchor("p2", 0))
        val store = MemoryStore().apply { records[document.key] = DeviceReadingProgress(progress(1, "p1"), local,
            ServerReadingAnchor("p3", 0), progress(4, "p4")) }
        var writes = 0
        val connection = connection { request ->
            assertEquals("PUT", request.method); writes++
            val mutation = ServerReadingProgressJson.mutation(JSONObject(request.body!!))
            assertEquals(4L, mutation.expectedVersion); assertEquals("p3", mutation.anchor.paragraphId)
            assertNotEquals(local.mutationId, mutation.mutationId)
            response(progress(5, "p3"))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        sync.open(document, connection, 0)
        sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        assertEquals(0, writes)
        sync.chooseLocal(); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(1, writes)

        sync.close(); sync.state.first { it.phase == ReadingProgressPhase.Inactive }
        store.records[document.key] = DeviceReadingProgress(progress(1, "p1"), local, conflict = progress(4, "p4"))
        sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        sync.chooseServer()
        val state = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(3, state.restore!!.pageIndex)
        assertEquals(1, writes)
        assertNull(store.records[document.key]!!.pending)
    }

    @Test fun staleCancelledResponseCannotWriteOrRestoreIntoAnotherAccount() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val old = connection { entered.complete(Unit); withContext(NonCancellable) { release.await() }; exited.complete(Unit); response(progress(5, "p4")) }
        val next = connection("https://reader.example", "other") { response(progress(1, "p2")) }
        val sync = ServerReadingProgressSync(backgroundScope, store)
        sync.open(document(), old, 0); entered.await()
        val nextDocument = document(next.accountKey)
        sync.open(nextDocument, next, 0)
        sync.state.first { it.phase == ReadingProgressPhase.Synced }
        release.complete(Unit); exited.await(); runCurrent()
        assertEquals(nextDocument.readerId, sync.state.value.readerId)
        assertEquals(1, sync.state.value.restore!!.pageIndex)
        assertFalse(store.records.containsKey(document().key))
    }

    @Test fun failedDeviceWriteStopsPublishingAndDoesNotPretendThePositionWasSaved() = runTest {
        var writes = 0
        val store = MemoryStore()
        val sync = ServerReadingProgressSync(backgroundScope, store)
        sync.open(document(), connection { request -> if (request.method == "PUT") writes++; response(progress(0)) }, 0)
        sync.state.first { it.phase == ReadingProgressPhase.Synced }
        store.fail = true
        sync.pageChanged(document().readerId, 1)
        sync.state.first { it.phase == ReadingProgressPhase.DeviceError }
        advanceTimeBy(2000); runCurrent()
        assertEquals(0, writes)
    }

    @Test fun scopeNormalizesEquivalentOriginsButSeparatesAccountOriginAndDocumentKind() {
        assertEquals(serverReadingAccountKey("https://READER.example:443/", "Reader"), serverReadingAccountKey("https://reader.example", "reader"))
        assertNotEquals(serverReadingAccountKey("https://reader.example:444", "reader"), document().accountKey)
        assertNotEquals(document().key, document(serverReadingAccountKey("https://reader.example", "other")).key)
        val translated = document().source.copy(entry = document().source.entry.copy(kind = ServerLibraryKind.Translations))
        assertNotEquals(document().key, ServerReadingDocument(document().accountKey, translated, document().mapping).key)
    }

    @Test fun exactParagraphMappingRetainsWhitespaceAndDoesNotSplitSurrogates() {
        val text = "  " + "A".repeat(1097) + "😀" + " trailing  "
        val document = document(texts = listOf(text, "Second"))
        assertEquals(3, document.mapping.document.pageCount)
        assertEquals(1099, document.anchor(1)!!.characterOffset)
        assertEquals(text, document.mapping.document.pages.take(2).joinToString("") { it.segments.single().text })
        assertThrows(IllegalArgumentException::class.java) { document.validate(ServerReadingAnchor("p1", 1100)) }
        assertEquals(1, document.page(ServerReadingAnchor("p1", 1101)))
    }

    @Test fun translatedPreviewUsesArtifactParagraphIdsAndTargetTextOffsets() {
        val fixture = requireNotNull(javaClass.classLoader?.getResource("translation-v1/stored-response.json")).readText()
        val stored = TranslationStoreJson.decode(JSONObject(fixture))
        val artifact = stored.artifact
        val entry = ServerLibraryEntry(stored.recordId, "Translated", artifact.targetLanguage, artifact.paragraphs.size,
            artifact.sourceRevision, ServerLibraryKind.Translations)
        val source = ServerLibraryDocument(entry, artifact.paragraphs.map { it.text }, stored)
        val reading = ServerReadingDocument.create(document().accountKey, source)
        assertTrue(reading.key.contains(":TRANSLATION:"))
        artifact.paragraphs.forEach { paragraph ->
            reading.validate(ServerReadingAnchor(paragraph.paragraphId, paragraph.text.length))
            assertThrows(IllegalArgumentException::class.java) { reading.validate(ServerReadingAnchor(paragraph.paragraphId, paragraph.text.length + 1)) }
        }
        assertEquals(artifact.paragraphs.first().paragraphId, reading.anchor(0)!!.paragraphId)
    }

    @Test fun persistedPendingRoundTripsAndRejectsAnotherIdentityOrInvalidAnchor() {
        val document = document()
        val value = DeviceReadingProgress(progress(2, "p1"), ServerReadingMutation(2, UUID.randomUUID().toString(), ServerReadingAnchor("p2", 0)),
            ServerReadingAnchor("p3", 1), progress(3, "p4"))
        val json = encodeDeviceReadingProgress(document, value)
        assertEquals(value, decodeDeviceReadingProgress(JSONObject(json.toString()), document))
        assertThrows(IllegalArgumentException::class.java) { decodeDeviceReadingProgress(JSONObject(json.toString()).put("key", "another-account"), document) }
        json.getJSONObject("queuedAnchor").put("characterOffset", 10000)
        assertThrows(IllegalArgumentException::class.java) { decodeDeviceReadingProgress(json, document) }
    }

    private fun document(accountKey: String = serverReadingAccountKey("https://reader.example", "reader"), texts: List<String> = List(4) { "Paragraph ${it + 1}" }): ServerReadingDocument {
        val content = ChapterContent(ChapterIdentity(BookIdentity("source", "book"), "chapter"), "Chapter", "en",
            texts.mapIndexed { index, text -> ContentParagraph("p${index + 1}", index, text) })
        val entry = ServerLibraryEntry(id, "Book", "en", texts.size, content.sourceRevision, ServerLibraryKind.Originals)
        return ServerReadingDocument.create(accountKey, ServerLibraryDocument(entry, texts, sourceContent = content))
    }
    private fun progress(version: Long, paragraph: String = "p1", offset: Int = 0) = ServerReadingProgress("ORIGINAL", id, version,
        if (version == 0L) null else ServerReadingAnchor(paragraph, offset), if (version == 0L) null else timestamp)
    private fun response(value: ServerReadingProgress) = TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(value).toString())
    private fun conflict(value: ServerReadingProgress) = TranslationStoreHttpResponse(409,
        body = JSONObject().put("code", "READING_PROGRESS_CONFLICT").put("current", ServerReadingProgressJson.encode(value)).toString())
    private fun connection(endpoint: String = "https://reader.example", username: String = "reader",
        handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) = ServerReadingConnection(serverReadingAccountKey(endpoint, username),
        HttpTranslationStore(endpoint, TranslationStoreBasicAuth(username, "password"), TranslationStoreHttpTransport { request ->
            if (request.url.endsWith("/csrf")) TranslationStoreHttpResponse(200, mapOf("Set-Cookie" to listOf("JSESSIONID=test; Path=/; HttpOnly")),
                """{"headerName":"X-CSRF-TOKEN","token":"token"}""") else handler(request)
        }))
    private class MemoryStore : ServerReadingProgressStore {
        val records = java.util.concurrent.ConcurrentHashMap<String, DeviceReadingProgress>()
        val changes = Channel<DeviceReadingProgress>(Channel.UNLIMITED)
        val scans = Channel<String>(Channel.UNLIMITED)
        var fail = false
        override fun read(document: ServerReadingDocument) = records[document.key] ?: DeviceReadingProgress()
        override fun read(target: ServerReadingTarget) = records[target.key] ?: DeviceReadingProgress()
        override fun write(document: ServerReadingDocument, value: DeviceReadingProgress) = write(document.target(), value)
        override fun write(target: ServerReadingTarget, value: DeviceReadingProgress) {
            if (fail) error("Device full")
            records[target.key] = value; changes.trySend(value)
        }
        override fun pending(accountKey: String): List<ServerReadingTarget> {
            scans.trySend(accountKey)
            return records.filter { it.key.startsWith("$accountKey:") && it.value.pending != null }.keys.map {
                val parts = it.split(':'); ServerReadingTarget(parts[0], parts[1], parts[2])
            }
        }
        suspend fun await(predicate: (DeviceReadingProgress) -> Boolean) { while (!predicate(changes.receive())) Unit }
    }
}
