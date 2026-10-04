package com.dongholab.pagetuner.translation.sync

import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.dongholab.pagetuner.translation.sync.NotesFixture.recordId
import com.dongholab.pagetuner.translation.sync.NotesFixture.noteId
import com.dongholab.pagetuner.translation.sync.NotesFixture.otherId
import com.dongholab.pagetuner.translation.sync.NotesFixture.time
import com.dongholab.pagetuner.translation.sync.NotesFixture.document
import com.dongholab.pagetuner.translation.sync.NotesFixture.content
import com.dongholab.pagetuner.translation.sync.NotesFixture.item
import com.dongholab.pagetuner.translation.sync.NotesFixture.page
import com.dongholab.pagetuner.translation.sync.NotesFixture.response
import com.dongholab.pagetuner.translation.sync.NotesFixture.feed
import com.dongholab.pagetuner.translation.sync.NotesFixture.conflict
import com.dongholab.pagetuner.translation.sync.NotesFixture.requestMutation
import com.dongholab.pagetuner.translation.sync.NotesFixture.ack
import com.dongholab.pagetuner.translation.sync.NotesFixture.connection
import com.dongholab.pagetuner.translation.sync.NotesFixture.Store

@OptIn(ExperimentalCoroutinesApi::class)
class ServerReadingNotesTest {
    @Test fun viewportOffsetKeepsBookmarkAndHighlightCanonicalRangesExact() {
        val doc = document(texts = listOf("A".repeat(1100) + "😀 tail"))
        val bookmark = doc.noteContent(ServerReadingNoteKind.BOOKMARK, 1, "Reading here", "", 3)
        assertEquals(ServerReadingAnchor("p1", 1103), bookmark.anchor)
        assertEquals("tail", bookmark.excerpt)
        val highlight = doc.noteContent(ServerReadingNoteKind.HIGHLIGHT, 1, "Remaining source", "", 3)
        assertEquals(ServerReadingAnchor("p1", 1103), highlight.range!!.start)
        assertEquals(ServerReadingAnchor("p1", 1107), highlight.range!!.end)
        assertEquals("tail", highlight.excerpt)
        assertThrows(IllegalArgumentException::class.java) { doc.noteContent(ServerReadingNoteKind.BOOKMARK, 1, "Invalid", "", 1) }
    }

    @Test fun pointNotesRetainTerminalAndEmptyAnchorsWhileHighlightsCannotRemap() {
        val doc = document(texts = listOf("First", "Second", ""))
        assertEquals(0 to 5, doc.notePosition(0, 5))
        val bookmark = doc.noteContent(ServerReadingNoteKind.BOOKMARK, 0, "Paragraph end", "", 5)
        assertEquals(ServerReadingAnchor("p1", 5), bookmark.anchor)
        assertEquals("", bookmark.excerpt)
        val end = doc.noteContent(ServerReadingNoteKind.NOTE, 1, "End", "My note", 6)
        assertEquals(ServerReadingAnchor("p2", 6), end.anchor)
        assertEquals("", end.excerpt)
        val empty = doc.noteContent(ServerReadingNoteKind.BOOKMARK, 2, "Empty", "", 0)
        assertEquals(ServerReadingAnchor("p3", 0), empty.anchor)
        assertEquals("", empty.excerpt)
        assertThrows(IllegalArgumentException::class.java) { doc.noteContent(ServerReadingNoteKind.HIGHLIGHT, 0, "End", "", 5) }
        assertThrows(IllegalArgumentException::class.java) { doc.noteContent(ServerReadingNoteKind.HIGHLIGHT, 2, "Empty", "", 0) }
        assertThrows(IllegalArgumentException::class.java) { doc.noteContent(ServerReadingNoteKind.BOOKMARK, 0, "Out of bounds", "", 6) }
    }

    @Test fun incrementalPagesAreAtomicPreserveRepeatedIdsAndDoNotUploadRestoredNotes() = runTest(timeout = 10.seconds) {
        val store = Store(); val urls = mutableListOf<String>(); var writes = 0
        val client = connection { request ->
            urls += request.url
            if (request.method == "PUT") { writes++; ack(request) }
            else if (urls.size == 1) response(page(listOf(item(1, 1)), 1, 3, true))
            else response(page(listOf(item(2, 2), item(1, 3, otherId)), 3, 3))
        }
        val doc = document(); val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); val state = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(2, state.items.size); assertEquals(2L, state.items.first { it.noteId == noteId }.version)
        assertEquals(3L, store.records.getValue(doc.key).afterRevision)
        assertTrue(urls[1].endsWith("afterRevision=1&limit=50&untilRevision=3")); assertEquals(0, writes)
    }
    @Test fun createDuringInitialPullSurvivesAndSamePageBookmarksStayIndependent() = runTest(timeout = 10.seconds) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val puts = mutableListOf<TranslationStoreHttpRequest>()
        val store = Store(); val doc = document()
        val client = connection { request ->
            if (request.method == "GET") { entered.complete(Unit); release.await(); response(page(listOf(item()))) }
            else { puts += request; ack(request, (puts.size + 1).toLong()) }
        }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); entered.await()
        sync.create(doc.readerId, ServerReadingNoteKind.BOOKMARK, 0, "Bookmark one", "")
        sync.create(doc.readerId, ServerReadingNoteKind.BOOKMARK, 0, "Bookmark two", "")
        store.await { it.pending.size == 2 }; release.complete(Unit)
        val state = sync.state.first { it.phase == ReadingProgressPhase.Synced && it.items.size == 3 }
        assertEquals(2, puts.size); assertEquals(3, state.items.map { it.noteId }.distinct().size)
        assertEquals(1L, store.records.getValue(doc.key).afterRevision) // PUT ACK cannot skip unrelated feed revisions.
    }
    @Test fun lostAcknowledgementReplaysIdenticalMutationThenQueuedDeletionAfterLogout() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val puts = mutableListOf<ServerReadingNoteMutation>(); var fail = true
        val client = connection { request ->
            if (request.method == "GET") feed(request) else {
                puts += requestMutation(request)
                if (fail) { entered.complete(Unit); release.await(); throw SocketTimeoutException() }
                ack(request, puts.size.toLong())
            }
        }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.create(doc.readerId, ServerReadingNoteKind.NOTE, 0, "First", "Body"); entered.await()
        val id = sync.state.value.items.single().noteId
        sync.delete(doc.readerId, id); store.await { it.queued[id]?.deleted == true }
        release.complete(Unit); sync.state.first { it.phase == ReadingProgressPhase.Offline }
        sync.connect(null); sync.state.first { it.phase == ReadingProgressPhase.Inactive }
        fail = false; sync.connect(client)
        store.await { it.items[id]?.deleted == true && it.pending.isEmpty() }
        assertEquals(3, puts.size); assertEquals(puts[0], puts[1]); assertTrue(puts[2].deleted)
        assertEquals(1L, puts[2].expectedVersion); assertNotEquals(puts[0].mutationId, puts[2].mutationId)
    }
    @Test fun olderFeedCannotRollbackNewerPutAcknowledgementAndHealthyReaderPolls() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document(); var gets = 0
        val client = connection { request -> if (request.method == "GET") {
            gets++; feed(request, listOf(item()))
        } else ack(request, 2) }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(doc.readerId, noteId, "Updated", "Updated body")
        sync.state.first { it.phase == ReadingProgressPhase.Synced && it.items.single().version == 2L }
        // Simulate restoring a cursor from before the ACK: incoming v1 must leave the v2 cache intact.
        val journal = store.records.getValue(doc.key)
        store.records[doc.key] = journal.copy(afterRevision = 0)
        sync.open(doc.copy(openId = "reopen"), client)
        store.await { it.afterRevision == 1L }
        val state = sync.state.first { it.phase == ReadingProgressPhase.Synced && gets >= 2 }
        assertEquals("Updated", state.items.single().note!!.title)
        assertEquals(1L, store.records.getValue(doc.key).afterRevision)
        // Active polling requests a new watermark, never reuses the completed one.
        advanceTimeBy(30_001); runCurrent()
        sync.state.first { gets >= 3 && it.phase != ReadingProgressPhase.Loading }
    }
    @Test fun conflictDeletionKeepsLocalContentAndRequiresExplicitResurrection() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document(); var puts = 0
        val client = connection { request -> if (request.method == "GET") feed(request, listOf(item())) else {
            puts++; if (puts == 1) conflict(item(2, 2, content = null)) else ack(request, 3)
        } }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(doc.readerId, noteId, "Keep my note", "New body")
        val state = sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        assertTrue(state.conflicts.single().remote.deleted); assertEquals("Keep my note", state.items.single().note!!.title)
        sync.resolve(doc.readerId, noteId, true, state.conflicts.single())
        val resolved = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(3L, resolved.items.single().version); assertEquals(2, puts)
    }
    @Test fun staleConflictChoiceCannotEraseNewerLocalEditOrRemoteChange() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document(); var gets = 0
        val client = connection { request -> if (request.method == "GET") {
            gets++; feed(request, if (gets == 1) listOf(item()) else listOf(item(3, 3, content = content("Newest account"))))
        } else conflict(item(2, 2, content = content("Account change"))) }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(doc.readerId, noteId, "Device change", "Body")
        val shown = sync.state.first { it.phase == ReadingProgressPhase.Conflict }.conflicts.single()
        sync.edit(doc.readerId, noteId, "Newer device change", "New body")
        store.await { it.queued[noteId]?.note?.title == "Newer device change" }
        // Storage writes on Dispatchers.IO signal before persist resumes and publishes the UI.
        // runCurrent only drains the test dispatcher, so wait for the actor's published edit
        // and rejection instead of racing its return from the real IO dispatcher.
        sync.state.first { it.conflicts.singleOrNull()?.local?.note?.title == "Newer device change" }
        sync.resolve(doc.readerId, noteId, false, shown)
        val rejected = sync.state.first { it.staleActionRejected }
        assertEquals("Newer device change", rejected.items.single().note!!.title)
        advanceTimeBy(30_001); runCurrent()
        val newer = sync.state.first { it.conflicts.singleOrNull()?.remote?.version == 3L }.conflicts.single()
        sync.resolve(doc.readerId, noteId, false, shown); runCurrent()
        assertEquals(1, sync.state.value.conflicts.size)
        sync.resolve(doc.readerId, noteId, false, newer)
        val resolved = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals("Newest account", resolved.items.single().note!!.title)
        assertTrue(store.records.getValue(doc.key).pending.isEmpty())
    }
    @Test fun staleEditFormUsesItsObservedVersionEvenAfterBackgroundPull() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document(); var gets = 0; var version: Long? = null
        val client = connection { request -> if (request.method == "GET") { gets++; response(page(listOf(item(gets.toLong(), gets.toLong())))) }
            else { version = requestMutation(request).expectedVersion; conflict(item(2, 2)) } }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); val observed = sync.state.first { it.phase == ReadingProgressPhase.Synced }.items.single()
        advanceTimeBy(30_001); runCurrent(); sync.state.first { it.items.singleOrNull()?.version == 2L }
        sync.edit(doc.readerId, noteId, "From old form", "Body", observed)
        sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        assertEquals(1L, version)
    }
    @Test fun staleEditAndDeleteCannotReplaceNewerUnsentDeviceContentAtTheSameVersion() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<ServerReadingNoteMutation>()
        val client = connection { request -> if (request.method == "GET") feed(request, listOf(item())) else {
            sent += requestMutation(request)
            if (sent.size == 1) { entered.complete(Unit); release.await() }
            ack(request, (sent.size + 1).toLong())
        } }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); val original = sync.state.first { it.phase == ReadingProgressPhase.Synced }.items.single()
        sync.edit(doc.readerId, noteId, "First device edit", "Body", original); entered.await()
        val first = sync.state.value.items.single()
        sync.edit(doc.readerId, noteId, "Latest device edit", "Latest body", first)
        store.await { it.queued[noteId]?.note?.title == "Latest device edit" }
        sync.edit(doc.readerId, noteId, "Stale edit", "Old form", original)
        sync.delete(doc.readerId, noteId, first)
        runCurrent()
        assertEquals("Latest device edit", sync.state.value.items.single().note!!.title)
        assertTrue(sync.state.value.staleActionRejected)
        assertEquals("Latest device edit", store.records.getValue(doc.key).queued[noteId]?.note?.title)
        release.complete(Unit)
        val saved = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals("Latest device edit", saved.items.single().note!!.title)
        assertEquals(listOf("First device edit", "Latest device edit"), sent.map { it.note!!.title })
    }
    @Test fun staleDeleteConfirmationUsesObservedServerVersionAfterBackgroundPull() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document(); var gets = 0; var deletion: ServerReadingNoteMutation? = null
        val client = connection { request -> if (request.method == "GET") {
            gets++; response(page(listOf(item(gets.toLong(), gets.toLong(), content = content("Version $gets")))))
        } else { deletion = requestMutation(request); conflict(item(2, 2, content = content("Version 2"))) } }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); val observed = sync.state.first { it.phase == ReadingProgressPhase.Synced }.items.single()
        advanceTimeBy(30_001); runCurrent(); sync.state.first { it.items.singleOrNull()?.version == 2L }
        sync.delete(doc.readerId, noteId, observed)
        val state = sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        assertEquals(1L, deletion!!.expectedVersion); assertTrue(deletion!!.deleted)
        assertEquals("Version 2", state.conflicts.single().remote.note!!.title)
        assertTrue(state.conflicts.single().local.deleted)
    }
    @Test fun closedReaderOutboxRetriesTransientFailureWithoutReopening() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document(); var puts = 0
        val client = connection { request -> if (request.method == "GET") feed(request) else {
            puts++; if (puts == 1) throw SocketTimeoutException() else ack(request)
        } }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.create(doc.readerId, ServerReadingNoteKind.BOOKMARK, 1, "Closed reader", "")
        sync.state.first { it.phase == ReadingProgressPhase.Offline }; sync.close(); sync.state.first { it.readerId == null }
        advanceTimeBy(15_001); runCurrent(); store.await { it.pending.isEmpty() && it.items.isNotEmpty() }
        assertEquals(2, puts)
    }
    @Test fun rateLimitPersistsAcrossLoginAndManualRetryHonorsDeadline() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document(); var puts = 0
        val client = connection { request -> if (request.method == "GET") feed(request) else {
            puts++; if (puts == 1) TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf("60"))) else ack(request)
        } }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.create(doc.readerId, ServerReadingNoteKind.BOOKMARK, 0, "Limited", "")
        sync.state.first { it.phase == ReadingProgressPhase.RateLimited }
        val first = store.records.getValue(doc.key).pending.values.single()
        sync.connect(null); sync.state.first { it.phase == ReadingProgressPhase.Inactive }
        sync.connect(client); sync.retry(); advanceTimeBy(59_999); runCurrent()
        assertEquals(1, puts); assertEquals(first, store.records.getValue(doc.key).pending.values.single())
        advanceTimeBy(2); runCurrent(); store.await { it.pending.isEmpty() && it.items.isNotEmpty() }
        assertEquals(2, puts)
    }
    @Test fun terminalFailureIsLatchedForNewEditsAndClosedOutboxUntilExplicitRetry() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document(); var puts = 0; var fail = true
        val client = connection { request -> if (request.method == "GET") feed(request, listOf(item())) else {
            puts++; if (fail) TranslationStoreHttpResponse(404) else ack(request, puts.toLong() + 1)
        } }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, client); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(doc.readerId, noteId, "First", "Memo"); sync.state.first { it.phase == ReadingProgressPhase.Unavailable }
        sync.edit(doc.readerId, noteId, "Second", "Memo"); store.await { it.queued[noteId] != null }
        advanceTimeBy(90_000); runCurrent(); assertEquals(1, puts)
        sync.close(); runCurrent(); advanceTimeBy(90_000); runCurrent(); assertEquals(1, puts)
        fail = false; sync.retry(); store.await { it.pending.isEmpty() && it.items[noteId]?.note?.title == "Second" }
        assertEquals(3, puts)
    }
    @Test fun oldAccountReplyCannotPopulateNewAccountOrPublishItsOutbox() = runTest(timeout = 10.seconds) {
        val store = Store(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var newPuts = 0
        val old = connection { withContext(NonCancellable) { entered.complete(Unit); release.await(); response(page(listOf(item()))) } }
        val next = connection("other") { if (it.method == "PUT") newPuts++; response(page()) }
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(document(), old); entered.await()
        sync.open(document("other"), next); sync.state.first { it.readerId == document("other").readerId && it.phase == ReadingProgressPhase.Synced }
        release.complete(Unit); runCurrent()
        assertTrue(sync.state.value.items.isEmpty()); assertEquals(0, newPuts)
        assertFalse(store.records.containsKey(document().key))
    }
    @Test fun failedAtomicPageWriteLeavesCursorAndAllItemsUntouched() = runTest(timeout = 10.seconds) {
        val store = Store(); store.fail = true
        val doc = document(); var puts = 0
        val sync = ServerReadingNotesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(doc, connection { if (it.method == "PUT") puts++; response(page(listOf(item()))) })
        sync.state.first { it.phase == ReadingProgressPhase.DeviceError }
        sync.create(doc.readerId, ServerReadingNoteKind.BOOKMARK, 0, "Must not send", "")
        runCurrent(); assertFalse(store.records.containsKey(doc.key)); assertEquals(0, puts)
    }
    @Test fun malformedSourceRangeDoesNotPartiallyApplyAPage() = runTest(timeout = 10.seconds) {
        val store = Store(); val doc = document()
        val invalid = content().copy(anchor = ServerReadingAnchor("p1", 7)) // inside the emoji surrogate pair
        val sync = ServerReadingNotesSync(backgroundScope, store)
        sync.open(doc, connection { response(page(listOf(item(), item(1, 2, otherId, invalid)))) })
        sync.state.first { it.phase == ReadingProgressPhase.DeviceError }
        assertFalse(store.records.containsKey(doc.key))
    }
    @Test fun unicodeCurrentPageHighlightAndImportedCanonicalRangeStayExact() {
        val doc = document(texts = listOf("A".repeat(1099) + "😀" + " tail", "Second"))
        val highlight = doc.noteContent(ServerReadingNoteKind.HIGHLIGHT, 1, "Emoji page", "")
        assertEquals(1099, highlight.anchor.characterOffset)
        assertEquals(1106, highlight.range!!.end.characterOffset)
        assertEquals("😀 tail", highlight.excerpt)
        val exact = highlight.copy(range = ServerReadingNoteRange(highlight.anchor, ServerReadingAnchor("p1", 1101)))
        assertEquals("😀", doc.noteExcerpt(exact))
        assertEquals(exact.range, exact.copy(title = "Edited").range)
        assertThrows(IllegalArgumentException::class.java) { doc.noteExcerpt(exact.copy(range = exact.range!!.copy(end = ServerReadingAnchor("p1", 1100)))) }
        val blocks = com.dongholab.pagetuner.ui.reader.noteTextBlocks("A".repeat(119) + "😀\n\n\n\nB")
        assertEquals("A".repeat(119) + "😀\n\n\n\nB", blocks.joinToString(""))
        assertTrue(blocks.none { it.lastOrNull()?.isHighSurrogate() == true })
    }
    @Test fun journalRoundTripKeepsIdsRangesMutationAndRejectsAnotherAccount() {
        val doc = document(); val item = item(); val mutation = ServerReadingNoteMutation(1, otherId, false, content("Local"))
        val journal = DeviceReadingNotes(1, mapOf(noteId to item), mapOf(noteId to mutation),
            mapOf(noteId to ServerReadingNoteDesired(true, null)), mapOf(noteId to item(2, 2)), 60_000)
        val encoded = encodeReadingNotesJournal(doc.target(), journal)
        assertEquals(journal, decodeReadingNotesJournal(JSONObject(encoded.toString()), doc.target()))
        assertThrows(IllegalArgumentException::class.java) { decodeReadingNotesJournal(encoded, document("other").target()) }
        assertThrows(IllegalArgumentException::class.java) { ServerReadingNotesJson.validate(content().copy(title = "\uD83D")) }
    }
}
