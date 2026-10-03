package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.translation.glossary.BookGlossary
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import com.dongholab.pagetuner.translation.glossary.CharacterAliasSuggestion
import com.dongholab.pagetuner.translation.glossary.GlossaryTermKind
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ServerBookGlossaryTest {
    @Test fun originalAndSavedTranslationShareSourceScopeButTranslationKeepsItsActualLanguage() {
        val original = NotesFixture.document()
        val content = requireNotNull(original.source.sourceContent)
        val translated = com.dongholab.pagetuner.core.translation.TranslationArtifact(content.identity, content.sourceRevision, "en", "ko",
            "translator", "model", "prompt", "glossary", content.paragraphs.map {
                com.dongholab.pagetuner.core.translation.TranslatedParagraph(it.paragraphId, it.text)
            })
        val source = ServerLibraryDocument(original.source.entry.copy(kind = ServerLibraryKind.Translations, recordId = NotesFixture.otherId),
            original.source.paragraphs, com.dongholab.pagetuner.core.translation.StoredTranslation(NotesFixture.otherId, translated, NotesFixture.time))
        val translatedReading = original.copy(source = source)
        assertEquals(original.glossaryTarget("ko"), translatedReading.glossaryTarget("ja"))
        assertNotEquals(original.glossaryTarget("ja"), translatedReading.glossaryTarget("ja"))
        assertNull(original.glossaryTarget("auto"))
        assertEquals("source", translatedReading.glossaryTarget("ja")!!.identity.providerId)
        assertEquals("book", translatedReading.glossaryTarget("ja")!!.identity.bookId)
    }

    @Test fun preservesIdsOrderFlagsRawWhitespaceAndDisplayOnlyFingerprint() {
        val encoded = ServerBookGlossaryJson.encode(view(1))
        assertEquals(view(1), ServerBookGlossaryJson.view(JSONObject(encoded.toString()), identity))
        assertEquals(listOf("entry-Z", "entry-A"), ServerBookGlossaryJson.view(encoded, identity).value.entries!!.map { it.id })
        val glossary = BookGlossary("local-id", sample.entries!!)
        val displayOnly = glossary.copy(entries = glossary.entries.map { it.copy(displayTerm = " Different alias ", kind = GlossaryTermKind.Term) })
        assertEquals(glossary.translationFingerprint, displayOnly.translationFingerprint)
        assertEquals(" Alias ", glossary.entries.first().displayTerm)
        assertFalse(glossary.entries.last().enabled)
        assertEquals(sample, appendBookGlossaryAliases(sample, listOf(CharacterAliasSuggestion(" Source ", "Replacement"))))
        val appended = appendBookGlossaryAliases(sample, listOf(CharacterAliasSuggestion("New", "New alias")))
        assertEquals(sample.entries, appended.entries!!.take(2))
    }

    @Test fun initialQueryDoesNotSelectOrUploadAndExplicitSelectionSurvivesTombstone() = runTest(timeout = 10.seconds) {
        val store = Store(); var remote = view(1); val writes = mutableListOf<BookGlossaryMutation>()
        val connection = NotesFixture.connection { request ->
            if (request.method == "PUT") writes += mutation(request)
            response(remote)
        }
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(connection), connection)
        val loaded = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertTrue(loaded.loaded); assertFalse(loaded.selected); assertTrue(writes.isEmpty())
        sync.selectAccount(loaded.session, loaded.base)
        sync.state.first { it.selected }
        remote = view(2, BookGlossaryValue(null)); sync.retry()
        val deleted = sync.state.first { it.base.remote?.version == 2L }
        assertTrue(deleted.selected); assertNull(deleted.base.local!!.entries); assertTrue(writes.isEmpty())
        assertTrue(store.records.getValue(target(connection).key).selected)
    }

    @Test fun absentQueryLeavesDeviceModeAndExplicitEmptyDictionaryHasVersion() = runTest(timeout = 10.seconds) {
        val store = Store(); val writes = mutableListOf<BookGlossaryMutation>(); var remote = view()
        val account = NotesFixture.connection { request ->
            if (request.method == "PUT") { val mutation = mutation(request); writes += mutation; remote = view(mutation.expectedVersion + 1, mutation.value) }
            response(remote)
        }
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account); val first = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertFalse(first.selected); assertTrue(first.loaded); assertEquals(0L, first.base.remote!!.version)
        sync.edit(first.session, BookGlossaryValue(emptyList()), first.base)
        val done = sync.state.first { it.base.remote?.version == 1L && it.pendingDocuments == 0 }
        assertTrue(done.selected); assertEquals(emptyList<BookGlossaryEntry>(), done.base.local!!.entries)
        assertEquals(0L, writes.single().expectedVersion)
    }

    @Test fun adoptionOverExistingAccountRequiresChoiceAndStaleChoiceKeepsNewerEdit() = runTest(timeout = 10.seconds) {
        val store = Store(); var writes = 0
        val connection = NotesFixture.connection { request -> if (request.method == "PUT") { writes++; ack(request) } else response(view(3)) }
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(connection), connection); val initial = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        val device = BookGlossaryValue(sample.entries!!.reversed())
        sync.adopt(initial.session, device, initial.base)
        val conflict = sync.state.first { it.choice != null }
        assertEquals(0, writes); assertEquals(device, conflict.choice!!.local)
        sync.edit(conflict.session, BookGlossaryValue(null), conflict.base)
        val queued = sync.state.first { it.base.local?.entries == null && it.choice != null }
        sync.choose(conflict.session, false, conflict.choice)
        sync.state.first { it.staleActionRejected }
        assertNull(sync.state.value.choice!!.local.entries)
        sync.choose(queued.session, true, queued.choice!!)
        sync.state.first { it.pendingDocuments == 0 && it.base.remote?.version == 4L }
        assertEquals(1, writes)
    }

    @Test fun uncertainMutationAndQueuedDeletionPersistAndReplayAfterRestart() = runTest(timeout = 10.seconds) {
        val store = Store(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var failing = true
        val writes = mutableListOf<BookGlossaryMutation>(); var remote = view(1)
        val connection = NotesFixture.connection { request ->
            if (request.method == "PUT") {
                val mutation = mutation(request); writes += mutation
                if (failing) { started.complete(Unit); release.await(); throw SocketTimeoutException() }
                remote = view(mutation.expectedVersion + 1, mutation.value)
            }
            response(remote)
        }
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(connection), connection); val initial = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(initial.session, BookGlossaryValue(emptyList()), initial.base); started.await()
        val pending = sync.state.first { it.base.local?.entries?.isEmpty() == true }
        sync.edit(pending.session, BookGlossaryValue(null), pending.base)
        store.await(target(connection)) { it.queued == BookGlossaryValue(null) }
        release.complete(Unit); sync.state.first { it.phase == ReadingProgressPhase.Offline }
        sync.connect(null); sync.state.first { it.accountKey == null }; failing = false
        val restarted = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }
        restarted.connect(connection)
        store.await(target(connection)) { it.pending == null && it.remote?.version == 3L }
        assertEquals(3, writes.size); assertEquals(writes[0], writes[1]); assertEquals(BookGlossaryValue(null), writes.last().value)
        assertTrue(store.records.getValue(target(connection).key).selected)
    }

    @Test fun oversizedQueuedSnapshotIsRejectedAndSmallerQueuedEditStillSends() = runTest(timeout = 10.seconds) {
        val store = Store(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var remote = view(1)
        val writes = mutableListOf<BookGlossaryMutation>()
        val connection = NotesFixture.connection { request ->
            if (request.method == "PUT") {
                val mutation = mutation(request); writes += mutation
                if (writes.size == 1) { started.complete(Unit); release.await() }
                remote = view(mutation.expectedVersion + 1, mutation.value)
            }
            response(remote)
        }
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(connection), connection); val initial = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(initial.session, BookGlossaryValue(emptyList()), initial.base); started.await()
        val pending = sync.state.first { it.base.local?.entries?.isEmpty() == true }
        val large = BookGlossaryValue(List(500) { index -> BookGlossaryEntry("$index" + "字".repeat(195), "字".repeat(200), "字".repeat(200), "字".repeat(200)) })
        ServerBookGlossaryJson.validate(large) // All field limits pass; encoded request does not.
        sync.edit(pending.session, large, pending.base)
        val rejected = sync.state.first { it.invalidInput }
        assertNotEquals(ReadingProgressPhase.DeviceError, rejected.phase); assertEquals(pending.session, rejected.session)
        assertNull(store.records.getValue(target(connection).key).queued)
        sync.edit(rejected.session, BookGlossaryValue(null), rejected.base)
        store.await(target(connection)) { it.queued == BookGlossaryValue(null) }; release.complete(Unit)
        sync.state.first { it.pendingDocuments == 0 && it.base.remote?.version == 3L }
        assertEquals(2, writes.size); assertNull(writes.last().value.entries)
    }

    @Test fun lateNetworkAndAliasCallbacksCannotCrossBookLanguageOrAccount() = runTest(timeout = 10.seconds) {
        val store = Store(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = NotesFixture.connection { withContext(NonCancellable) { started.complete(Unit); release.await() }; response(view(1)) }
        val otherIdentity = identity.copy(bookId = "Other", targetLanguage = "fr")
        var writes = 0
        val other = NotesFixture.connection("other") { if (it.method == "PUT") writes++; response(view(1).copy(identity = otherIdentity)) }
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(old), old); started.await(); val oldSession = sync.state.value.session
        sync.open(BookGlossaryTarget(other.accountKey, otherIdentity), other)
        val fresh = sync.state.first { it.target?.identity == otherIdentity && it.phase == ReadingProgressPhase.Synced }
        release.complete(Unit)
        sync.appendAliases(oldSession, BookGlossaryEditBase(sample, view(1)), listOf(CharacterAliasSuggestion("Late", "Alias")))
        runCurrent()
        assertEquals(fresh.base, sync.state.value.base); assertEquals(0, writes)
    }

    @Test fun storageFailureNeverUploadsAndRetryRecoversSameBook() = runTest(timeout = 10.seconds) {
        val store = Store(); var writes = 0
        val connection = NotesFixture.connection { if (it.method == "PUT") writes++; response(view(1)) }
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(connection), connection); val first = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        store.fail = true; sync.edit(first.session, BookGlossaryValue(null), first.base)
        val failed = sync.state.first { it.phase == ReadingProgressPhase.DeviceError }
        assertFalse(failed.loaded); assertEquals(target(connection), failed.target); assertEquals(0, writes)
        assertNull(store.records.getValue(target(connection).key).pending)
        store.fail = false; sync.retry()
        val recovered = sync.state.first { it.loaded && it.phase == ReadingProgressPhase.Synced }
        assertEquals(target(connection), recovered.target); assertEquals(sample, recovered.base.local)
    }

    @Test fun journalPreservesQueuedTombstoneAndRejectsForeignAccount() {
        val target = BookGlossaryTarget("a".repeat(64), identity)
        val value = DeviceBookGlossary(view(1), BookGlossaryMutation(1, NotesFixture.noteId, sample), BookGlossaryValue(null), view(2), selected = true)
        val json = encodeBookGlossaryJournal(target, value)
        assertEquals(value, decodeBookGlossaryJournal(JSONObject(json.toString()), target))
        assertThrows(IllegalArgumentException::class.java) { decodeBookGlossaryJournal(json, target.copy(accountKey = "b".repeat(64))) }
        assertNotEquals(target.key, target.copy(identity = identity.copy(targetLanguage = "fr")).key)
    }

    @Test fun repeatedRejectedEditAndLaterValidEditPublishDistinctCompletionRevisions() = runTest(timeout = 10.seconds) {
        val store = Store(); var remote = view(1)
        val connection = NotesFixture.connection { request ->
            if (request.method == "PUT") { val mutation = mutation(request); remote = view(mutation.expectedVersion + 1, mutation.value) }
            response(remote)
        }
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(connection), connection); val initial = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        val invalid = BookGlossaryValue(listOf(sample.entries!!.first(), sample.entries.first()))
        sync.edit(initial.session, invalid, initial.base)
        val firstFailure = sync.state.first { it.invalidInput && it.editRevision > initial.editRevision }
        sync.edit(firstFailure.session, invalid, firstFailure.base)
        val secondFailure = sync.state.first { it.editRevision > firstFailure.editRevision }
        assertTrue(secondFailure.invalidInput)
        sync.edit(secondFailure.session, BookGlossaryValue(emptyList()), secondFailure.base)
        val accepted = sync.state.first { it.editRevision > secondFailure.editRevision && it.base.local?.entries?.isEmpty() == true }
        assertFalse(accepted.invalidInput)
        assertEquals(secondFailure.editRevision + 1, accepted.editRevision)
        assertEquals(initial.session, accepted.session)
    }

    private class Store : ServerBookGlossaryStore {
        val records = ConcurrentHashMap<String, DeviceBookGlossary>()
        private val knownTargets = ConcurrentHashMap<String, BookGlossaryTarget>()
        private val changes = Channel<Unit>(Channel.UNLIMITED)
        var fail = false
        override fun read(target: BookGlossaryTarget): DeviceBookGlossary { if (fail) error("Storage unavailable"); return records[target.key] ?: DeviceBookGlossary() }
        override fun targets(accountKey: String) = knownTargets.values.filter { it.accountKey == accountKey }
        override fun write(target: BookGlossaryTarget, value: DeviceBookGlossary) {
            if (fail) error("Storage unavailable")
            records[target.key] = decodeBookGlossaryJournal(JSONObject(encodeBookGlossaryJournal(target, value).toString()), target)
            knownTargets[target.key] = target; changes.trySend(Unit)
        }
        suspend fun await(target: BookGlossaryTarget, predicate: (DeviceBookGlossary) -> Boolean) {
            while (records[target.key]?.let(predicate) != true) changes.receive()
        }
    }
    private companion object {
        val identity = BookGlossarySyncIdentity("wtr-lab", "https://wtr-lab.com/novel/42/book", "ko")
        val sample = BookGlossaryValue(listOf(BookGlossaryEntry("entry-Z", " Source ", " Target ", " Alias ", GlossaryTermKind.Place, true),
            BookGlossaryEntry("entry-A", "Second", "Other", "Disabled", GlossaryTermKind.Character, enabled = false)))
        fun target(connection: ServerReadingConnection) = BookGlossaryTarget(connection.accountKey, identity)
        fun view(version: Long = 0, value: BookGlossaryValue = sample) = ServerBookGlossary(identity, version,
            if (version == 0L) BookGlossaryValue(null) else value, NotesFixture.time.takeIf { version > 0 })
        fun mutation(request: TranslationStoreHttpRequest) = ServerBookGlossaryJson.mutation(JSONObject(request.body!!), identity)
        fun response(value: ServerBookGlossary) = TranslationStoreHttpResponse(200, body = ServerBookGlossaryJson.encode(value).toString())
        fun ack(request: TranslationStoreHttpRequest) = mutation(request).let { response(view(it.expectedVersion + 1, it.value)) }
    }
}
