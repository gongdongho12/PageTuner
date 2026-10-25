package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Production adapter, real JVM ZIP/binding files, injected journal/account transport. */
@OptIn(ExperimentalCoroutinesApi::class)
class PortableOrganizationAdoptionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sample = LibraryOrganizationSnapshotValue("읽을  책 é", listOf("é", "é", "Tag", "tag", "中  文"), true)

    private class Journal : ServerLibraryOrganizationStore {
        var record: DeviceLibraryOrganization? = null
        var commits = 0
        var beforeCommit: () -> Unit = {}
        var afterCommit: () -> Unit = {}
        override fun read(target: ServerReadingTarget) = record ?: DeviceLibraryOrganization()
        override fun readNullable(target: ServerReadingTarget) = record
        override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization) = error("Unconditional write forbidden")
        override fun targets(accountKey: String) = emptyList<ServerReadingTarget>()
        @Synchronized override fun compareAndWrite(target: ServerReadingTarget, expected: DeviceLibraryOrganization?,
            value: DeviceLibraryOrganization, guard: () -> Unit): Boolean {
            if (expected != record) return false
            beforeCommit(); guard()
            record = decodeLibraryOrganizationJournal(encodeLibraryOrganizationJournal(target, value), target)
            commits++; afterCommit(); return true
        }
    }

    private inner class Fixture(presence: LibraryOrganizationSnapshotPresence = LibraryOrganizationSnapshotPresence.PRESENT,
        value: LibraryOrganizationSnapshotValue = sample, withSnapshot: Boolean = true) {
        val account = NotesFixture.connection { error("Only injected verify and GET are allowed") }
        val directory = temporary.newFolder()
        val library = PortableLibraryStore(directory)
        val bindingDirectory = temporary.newFolder()
        val bindings = PortableServerBindingStore(bindingDirectory)
        val access = PortableTextBindingAccess(library, bindings)
        val base: ExchangeDocument = PortableDocumentMapper.server(NotesFixture.document().source).documents.single().let { source ->
            source.copy(extensionsJson = org.json.JSONObject(requireNotNull(source.extensionsJson))
                .put("unknown", org.json.JSONObject("""{"unicode":"é  文","keep":[1,2]}""")).toString())
        }
        val identity: DocumentIdentity = requireNotNull(DocumentIdentityJson.fromDocument(base))
        val snapshot: LibraryOrganizationSnapshot = LibraryOrganizationSnapshot(identity = identity, presence = presence,
            organization = value.takeIf { presence == LibraryOrganizationSnapshotPresence.PRESENT })
        val document: ExchangeDocument = if (withSnapshot) LibraryOrganizationSnapshotJson.withSnapshot(base, snapshot) else base
        val entry = library.importArchive(LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(document)))).entries.single()
        val saved = bindings.save(PortableServerBinding(account.accountKey, entry.key, NotesFixture.recordId, identity))
        val authority = PortableTextAccountAuthority(account)
        val lease = authority.operation(entry.key).apply { activate() }
        val ticket = lease.issue(account, entry.key)
        val target = ServerReadingTarget(account.accountKey, "ORIGINAL", NotesFixture.recordId)
        val storage = Journal()
        var gets = 0
        var verifies = 0
        var reads = 0
        var remote = remote(0)
        var beforeRead: suspend () -> Unit = {}
        var beforeQuery: suspend () -> Unit = {}
        var beforeVerify: suspend () -> Unit = {}
        fun remote(version: Long, value: LibraryOrganization = LibraryOrganization()) = ServerLibraryOrganization(
            target.kind, target.recordId, version, value.takeIf { version > 0 }, NotesFixture.time.takeIf { version > 0 })
        fun adapter(dispatcher: CoroutineDispatcher = Dispatchers.IO, scope: PortableTextAccountAuthority = authority,
            capturedTicket: PortableTextOperationTicket = ticket) = PortableOrganizationAdoption(entry, scope, capturedTicket,
            currentDocument = { selected -> reads++; beforeRead(); library.read(selected).documents[selected.documentIndex] },
            bindings = access, captureBinding = access::capture, storage = storage,
            verify = { connection, record, proof ->
                verifies++; beforeVerify(); assertSame(account.client, connection.client)
                assertEquals(target.recordId, record); assertEquals(identity, proof)
            },
            query = { connection, kind, record ->
                gets++; beforeQuery(); assertSame(account.client, connection.client)
                assertEquals(target.kind, kind); assertEquals(target.recordId, record); remote
            }, io = dispatcher)
    }

    @Test fun compareIsReadonlyAndSeparateConfirmationCreatesExactIntentWithoutEditingZip() = runTest {
        val f = Fixture(); val archive = File(f.directory, "${f.entry.packageId}.zip")
        val original = archive.readBytes()
        val adapter = f.adapter(StandardTestDispatcher(testScheduler))
        val shown = adapter.compare()
        assertEquals(f.snapshot, shown.snapshot); assertNull(shown.device); assertEquals(0L, shown.remote.version)
        assertNull(f.storage.record); assertEquals(0, f.storage.commits); assertEquals(1, f.gets)
        val committed = adapter.confirm(shown.id)
        assertEquals(f.target, committed.target); assertSame(f.account, committed.connection)
        val pending = f.storage.record!!.pending!!
        assertEquals(0L, pending.expectedVersion)
        assertEquals(LibraryOrganization(sample.folder, sample.tags, sample.favorite), pending.organization)
        assertEquals(pending.mutationId, java.util.UUID.fromString(pending.mutationId).toString())
        assertEquals(1, f.storage.commits); assertEquals(2, f.gets)
        assertArrayEquals(original, archive.readBytes())
        assertEquals(f.document, f.library.read(f.entry).documents.single())
        assertTrue(runCatching { adapter.confirm(shown.id) }.isFailure)
        assertTrue(runCatching { adapter.compare() }.isFailure)
    }

    @Test fun absentIsInformationOnlyWhilePresentEmptyAtZeroStillCreatesNewIntent() = runTest {
        val absent = Fixture(LibraryOrganizationSnapshotPresence.ABSENT)
        val actor = absent.adapter(StandardTestDispatcher(testScheduler)); val shown = actor.compare()
        assertEquals(LibraryOrganizationSnapshotPresence.ABSENT, shown.snapshot.presence)
        assertTrue(runCatching { actor.confirm(shown.id) }.isFailure)
        assertEquals(1, absent.gets); assertNull(absent.storage.record)
        val empty = Fixture(value = LibraryOrganizationSnapshotValue("", emptyList(), false))
        val emptyActor = empty.adapter(StandardTestDispatcher(testScheduler))
        emptyActor.confirm(emptyActor.compare().id)
        assertEquals(LibraryOrganization(), empty.storage.record!!.pending!!.organization)
        assertEquals(0L, empty.storage.record!!.pending!!.expectedVersion)
    }

    @Test fun sameValueUsesNewMutationAndKeepsNullableJournalAndRetryDeadline() = runTest {
        val f = Fixture(); f.remote = f.remote(3, LibraryOrganization(sample.folder, sample.tags, sample.favorite))
        f.storage.record = DeviceLibraryOrganization(f.remote, retryAfterUntil = 999999)
        val adapter = f.adapter(StandardTestDispatcher(testScheduler)); val shown = adapter.compare()
        adapter.confirm(shown.id)
        assertEquals(999999L, f.storage.record!!.retryAfterUntil)
        assertEquals(3L, f.storage.record!!.pending!!.expectedVersion)
        assertEquals(f.remote.organization, f.storage.record!!.pending!!.organization)
    }

    @Test fun editedDisplayCannotAlterPrivateSnapshotRemoteOrDeviceBase() = runTest {
        val f = Fixture(); f.remote = f.remote(1, LibraryOrganization("Remote", listOf("Remote tag"), false))
        f.storage.record = DeviceLibraryOrganization(f.remote)
        val adapter = f.adapter(StandardTestDispatcher(testScheduler)); val shown = adapter.compare()
        (shown.snapshot.organization!!.tags as MutableList).clear()
        (shown.remote.organization!!.tags as MutableList).clear()
        (shown.device!!.remote!!.organization!!.tags as MutableList).clear()
        adapter.confirm(shown.id)
        assertEquals(sample.tags, f.storage.record!!.pending!!.organization.tags)
        assertEquals(listOf("Remote tag"), f.storage.record!!.remote!!.organization!!.tags)
    }

    @Test fun readerSaveIsAwaitedAndCachedEntryDocumentNeverBecomesCanonicalAuthority() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var first = true
        f.beforeRead = { if (first) { first = false; entered.complete(Unit); release.await() } }
        val adapter = f.adapter(StandardTestDispatcher(testScheduler))
        val comparing = async { adapter.compare() }
        entered.await(); assertEquals(0, f.gets)
        f.library.update(f.entry) { it.copy(bookTitle = "Reader saved new title", position = ExchangeAnchor("p1", 1)) }
        release.complete(Unit)
        val shown = comparing.await(); adapter.confirm(shown.id)
        assertEquals(1, f.storage.commits)
        assertEquals("Reader saved new title", f.library.read(f.entry).documents.single().bookTitle)
    }

    @Test fun fullCanonicalMetadataAndDeletionAreRecheckedBeforeConfirmWithoutChangingBinding() = runTest {
        for (deleted in listOf(false, true)) {
            val f = Fixture(); val adapter = f.adapter(StandardTestDispatcher(testScheduler)); val shown = adapter.compare()
            val bindingBytes = f.bindingDirectory.listFiles()!!.single().readBytes()
            if (deleted) File(f.directory, "${f.entry.packageId}.zip").delete()
            else f.library.update(f.entry) { it.copy(bookTitle = "Metadata differs", position = ExchangeAnchor("p1", 1)) }
            assertTrue(runCatching { adapter.confirm(shown.id) }.isFailure)
            assertEquals(1, f.gets); assertEquals(0, f.storage.commits)
            assertArrayEquals(bindingBytes, f.bindingDirectory.listFiles()!!.single().readBytes())
        }
    }

    @Test fun sameUuidUnlinkRelinkRawNonceCannotBorrowDisplayedComparison() = runTest {
        val f = Fixture(); val adapter = f.adapter(StandardTestDispatcher(testScheduler)); val shown = adapter.compare()
        f.bindings.remove(f.account.accountKey, f.entry); val fresh = f.bindings.save(f.saved)
        assertNotEquals(f.saved.nonce, fresh.nonce)
        assertTrue(runCatching { adapter.confirm(shown.id) }.isFailure)
        assertEquals(0, f.storage.commits); assertEquals(fresh, f.bindings.read(f.account.accountKey, f.entry))
    }

    @Test fun accountAndSelectionRetirementDiscardHeldGetAndRejectRetainedAdapter() = runTest {
        for (account in listOf(false, true)) {
            val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.beforeQuery = { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            val adapter = f.adapter(StandardTestDispatcher(testScheduler)); val comparing = async { runCatching { adapter.compare() } }
            entered.await(); if (account) f.authority.close() else f.lease.close()
            release.complete(Unit)
            assertTrue(comparing.await().isFailure); assertNull(f.storage.record)
            assertTrue(runCatching { adapter.compare() }.isFailure)
            assertTrue(runCatching { adapter.confirm("old") }.isFailure)
        }
    }

    @Test fun dirtyJournalAndNullableBaseReplacementPreserveExistingIntentWithoutPut() = runTest {
        for (dirty in listOf("pending", "queued", "conflict", "nullable")) {
            val f = Fixture(); val adapter = f.adapter(StandardTestDispatcher(testScheduler))
            val shown = adapter.compare()
            val mutation = LibraryOrganizationMutation(0, NotesFixture.noteId, LibraryOrganization("Existing"))
            val row = when (dirty) {
                "pending" -> DeviceLibraryOrganization(pending = mutation)
                "queued" -> DeviceLibraryOrganization(pending = mutation, queued = LibraryOrganization("Queued"))
                "conflict" -> DeviceLibraryOrganization(pending = mutation, conflict = f.remote(1))
                else -> DeviceLibraryOrganization()
            }
            f.storage.record = row
            assertTrue(runCatching { adapter.confirm(shown.id) }.isFailure)
            assertEquals(row, f.storage.record); assertEquals(0, f.storage.commits)
        }
    }

    @Test fun sameScopedCommandKeepsHighwaterAcrossRecompareAndPermanentlyRejectsRegression() = runTest {
        for (sameVersionChange in listOf(false, true)) {
            val f = Fixture(); f.remote = f.remote(3)
            val adapter = f.adapter(StandardTestDispatcher(testScheduler)); adapter.compare()
            f.remote = if (sameVersionChange) f.remote(3, LibraryOrganization("Changed")) else f.remote(2)
            assertTrue(runCatching { adapter.compare() }.isFailure)
            f.remote = f.remote(4)
            assertTrue(runCatching { adapter.compare() }.isFailure)
            assertNull(f.storage.record)
        }
    }

    @Test fun lastJournalGuardRejectsRetirementAndPostcommitCloseCannotRollbackSuccess() = runTest {
        val before = Fixture(); val first = before.adapter(StandardTestDispatcher(testScheduler)); val shown = first.compare()
        before.storage.beforeCommit = { before.lease.close() }
        assertTrue(runCatching { first.confirm(shown.id) }.isFailure); assertNull(before.storage.record)
        val after = Fixture(); val second = after.adapter(StandardTestDispatcher(testScheduler)); val ready = second.compare()
        after.storage.afterCommit = { second.close(); after.lease.close(); after.authority.close() }
        assertEquals(after.target, second.confirm(ready.id).target)
        assertEquals(1, after.storage.commits); assertNotNull(after.storage.record!!.pending)
    }

    @Test fun missingSnapshotWrongAccountAndUnsupportedReadonlyBindingAreRejectedBeforeGet() = runTest {
        val noSnapshot = Fixture(withSnapshot = false)
        assertTrue(runCatching { noSnapshot.adapter(StandardTestDispatcher(testScheduler)).compare() }.isFailure)
        assertEquals(0, noSnapshot.gets)
        val wrong = Fixture()
        val other = PortableTextAccountAuthority(wrong.account.copy(accountKey = "b".repeat(64)))
        val lease = other.operation(wrong.entry.key).apply { activate() }
        val ticket = lease.issue(other.connection, wrong.entry.key)
        assertTrue(runCatching { wrong.adapter(StandardTestDispatcher(testScheduler), other, ticket).compare() }.isFailure)
        assertEquals(0, wrong.gets); assertNull(wrong.storage.record)
        val broken = Fixture()
        val file = broken.bindingDirectory.listFiles()!!.single(); val original = file.readBytes()
        file.writeText("{broken")
        assertTrue(runCatching { broken.adapter(StandardTestDispatcher(testScheduler)).compare() }.isFailure)
        assertEquals("{broken", file.readText()); assertEquals(0, broken.gets)
        file.writeBytes(original)
        val versionOne = org.json.JSONObject().put("version", 1).put("accountKey", broken.account.accountKey)
            .put("localKey", broken.entry.key).put("recordId", broken.saved.recordId)
            .put("identity", DocumentIdentityJson.encode(broken.identity)).toString()
        file.writeText(versionOne)
        assertTrue(runCatching { broken.adapter(StandardTestDispatcher(testScheduler)).compare() }.isFailure)
        assertEquals(versionOne, file.readText()); assertEquals(0, broken.gets)
    }

    @Test fun productionHttpAdapterOnlyVerifiesAndGetsWhileConfirmationOnlyCommitsJournal() = runTest {
        val f = Fixture(); val requests = mutableListOf<TranslationStoreHttpRequest>()
        val connection = NotesFixture.connection { request ->
            requests += request
            val response = when {
                request.url.endsWith("/library-identity/verify") && request.method == "POST" ->
                    org.json.JSONObject(request.body!!).put("verified", true)
                request.url.endsWith("/library-organization/ORIGINAL/${NotesFixture.recordId}") && request.method == "GET" ->
                    ServerLibraryOrganizationJson.encode(f.remote)
                else -> error("Unexpected HTTP write: ${request.method}")
            }
            TranslationStoreHttpResponse(200, body = response.toString())
        }
        val authority = PortableTextAccountAuthority(connection)
        val lease = authority.operation(f.entry.key).apply { activate() }
        val ticket = lease.issue(connection, f.entry.key)
        val adapter = PortableOrganizationAdoption(f.entry, authority, ticket,
            currentDocument = { f.library.read(it).documents[it.documentIndex] }, bindings = f.access,
            captureBinding = f.access::capture, storage = f.storage, io = StandardTestDispatcher(testScheduler))
        val shown = adapter.compare()
        assertNull(f.storage.record); assertEquals(1, requests.count { it.method == "GET" })
        adapter.confirm(shown.id)
        assertEquals(1, f.storage.commits); assertEquals(2, requests.count { it.method == "GET" })
        assertTrue(requests.any { it.method == "POST" })
        assertTrue(requests.all { it.method in setOf("GET", "POST") })
    }
}
