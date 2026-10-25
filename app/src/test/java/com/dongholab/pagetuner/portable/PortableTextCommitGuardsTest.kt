package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real ZIP/binding files and nested authority locks; journal and account query are injected. */
class PortableTextCommitGuardsTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Journal : ServerLibraryOrganizationStore {
        var record: DeviceLibraryOrganization? = null
        var attempts = 0
        var staged: () -> Unit = {}
        override fun read(target: ServerReadingTarget) = record ?: DeviceLibraryOrganization()
        override fun readNullable(target: ServerReadingTarget) = record
        override fun targets(accountKey: String) = emptyList<ServerReadingTarget>()
        override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization) = error("Use CAS")
        override fun compareAndWrite(target: ServerReadingTarget, expected: DeviceLibraryOrganization?,
            value: DeviceLibraryOrganization, guard: () -> Unit): Boolean {
            attempts++
            if (record != expected) return false
            staged(); guard(); record = value; return true
        }
    }
    private inner class Fixture {
        val account = NotesFixture.connection { error("Injected account observation only") }
        val libraryDirectory = temporary.newFolder()
        val bindingDirectory = temporary.newFolder()
        val library = PortableLibraryStore(libraryDirectory)
        val entry = library.importArchive(LibraryExchangeCodec.write(PortableDocumentMapper.server(NotesFixture.document().source))).entries.single()
        val bindings = PortableServerBindingStore(bindingDirectory)
        val saved = bindings.save(PortableServerBinding(account.accountKey, entry.key, NotesFixture.recordId,
            requireNotNull(DocumentIdentityJson.fromDocument(entry.document))))
        val snapshot = bindings.readSnapshot(account.accountKey, entry)
        val access = PortableTextBindingAccess(library, bindings)
        val accountLife = PortableTextAccountLifetime(account).apply { activate() }
        val operation = PortableTextOperationLease(accountLife, account, entry.key).apply { activate() }
        val ticket = operation.issue(account, entry.key)
        val journal = Journal()
        val target = ServerReadingTarget(account.accountKey, "ORIGINAL", saved.recordId)
        val facade = object : ServerLibraryOrganizationStore {
            override fun read(target: ServerReadingTarget) = journal.read(target)
            override fun readNullable(target: ServerReadingTarget) = journal.readNullable(target)
            override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization) = error("Use guarded CAS")
            override fun targets(accountKey: String) = journal.targets(accountKey)
            override fun compareAndWrite(target: ServerReadingTarget, expected: DeviceLibraryOrganization?,
                value: DeviceLibraryOrganization, guard: () -> Unit): Boolean = ticket.withCurrent { current ->
                access.verified(current.accountKey, entry, snapshot, ticket::check) {
                    journal.compareAndWrite(target, expected, value, guard)
                }
            }
        }
        var gets = 0
        val command = ServerLibraryOrganizationAdoption(facade, target, account, ticket::check,
            checkCanonical = { ticket.withCurrent { access.verified(it.accountKey, entry, snapshot, ticket::check) {} } },
            query = { gets++; ServerLibraryOrganization(target.kind, target.recordId, 0, null, null) })
    }

    @Test fun stagedCommitOwnsGateAccountLibraryAndBindingUntilDurableReturn() = runTest {
        val f = Fixture(); val shown = f.command.compare()
        val staged = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        f.journal.staged = { staged.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val saving = async { f.command.adopt(shown, LibraryOrganization("읽을  책", listOf("é", "é"), true)) }
            staged.await()
            val started = CountDownLatch(4)
            val competing = listOf<() -> Unit>(
                { f.operation.close() },
                { f.accountLife.close() },
                { PortableLibraryStore(File(f.libraryDirectory, ".")).update(f.entry) { it.copy(bookTitle = "After commit") } },
                { PortableServerBindingStore(File(f.bindingDirectory, ".")).remove(f.account.accountKey, f.entry) },
            ).map { action -> executor.submit { started.countDown(); action() } }
            assertTrue(started.await(5, TimeUnit.SECONDS)); assertTrue(competing.none { it.isDone })
            competing.forEach { future -> assertThrows(TimeoutException::class.java) { future.get(100, TimeUnit.MILLISECONDS) } }
            release.countDown()
            assertTrue(saving.await())
            competing.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(0L, f.journal.record!!.pending!!.expectedVersion)
            assertEquals(listOf("é", "é"), f.journal.record!!.pending!!.organization.tags)
            assertEquals(1, f.journal.attempts)
            assertTrue(runCatching { f.ticket.check() }.isFailure)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun reentrantRetirementBeforeFinalJournalGuardRejectsWithoutACommit() = runTest {
        val f = Fixture(); val shown = f.command.compare()
        f.journal.staged = { f.operation.close() }
        assertTrue(runCatching { f.command.adopt(shown, LibraryOrganization()) }.isFailure)
        assertNull(f.journal.record)
        // The failed callback releases both read-only scopes; ordinary future operations work.
        f.library.update(f.entry) { it.copy(bookTitle = "Still available") }
        f.bindings.remove(f.account.accountKey, f.entry)
        assertEquals("Still available", f.library.read(f.entry).documents.single().bookTitle)
    }

    @Test fun metadataChangeBeforeConfirmationCannotBorrowUnchangedTextIdentity() = runTest {
        val f = Fixture(); val shown = f.command.compare()
        f.library.update(f.entry) { it.copy(position = ExchangeAnchor("p1", 1), bookTitle = "Changed title") }
        val bytes = f.bindingDirectory.listFiles()!!.single().readBytes()
        assertTrue(runCatching { f.command.adopt(shown, LibraryOrganization()) }.isFailure)
        assertEquals(0, f.journal.attempts); assertNull(f.journal.record)
        assertArrayEquals(bytes, f.bindingDirectory.listFiles()!!.single().readBytes())
        assertEquals(1, f.gets)
    }

    @Test fun sameRecordReconnectCannotBorrowOldRawNonceBeforeJournalWrite() = runTest {
        val f = Fixture(); val shown = f.command.compare()
        f.bindings.remove(f.account.accountKey, f.entry)
        val next = f.bindings.save(f.saved)
        assertEquals(f.saved.recordId, next.recordId); assertNotEquals(f.saved.nonce, next.nonce)
        assertTrue(runCatching { f.command.adopt(shown, LibraryOrganization()) }.isFailure)
        assertEquals(0, f.journal.attempts); assertNull(f.journal.record)
        assertEquals(next, f.bindings.read(f.account.accountKey, f.entry))
    }
}
