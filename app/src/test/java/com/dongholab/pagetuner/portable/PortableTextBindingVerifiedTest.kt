package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.translation.sync.NotesFixture
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real JVM filesystem and monitor tests; no Android/SAF or account HTTP requests are performed. */
class PortableTextBindingVerifiedTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture {
        val libraryDirectory = temporary.newFolder()
        val bindingDirectory = temporary.newFolder()
        val library = PortableLibraryStore(libraryDirectory)
        val bindings = PortableServerBindingStore(bindingDirectory)
        val aliasLibrary = PortableLibraryStore(File(libraryDirectory, "."))
        val aliasBindings = PortableServerBindingStore(File(bindingDirectory, "."))
        val entry = library.importArchive(LibraryExchangeCodec.write(
            PortableDocumentMapper.server(NotesFixture.document().source))).entries.single()
        val value = PortableServerBinding("account", entry.key, NotesFixture.recordId,
            requireNotNull(DocumentIdentityJson.fromDocument(entry.document)))
        val saved = bindings.save(value)
        val snapshot = bindings.readSnapshot(value.accountKey, entry)
        val access = PortableTextBindingAccess(library, bindings)
        val bindingFile get() = File(bindingDirectory,
            DocumentIds.sha256("${value.accountKey}\n${value.localKey}".toByteArray(Charsets.UTF_8)) + ".json")
        val archive get() = File(libraryDirectory, "${entry.packageId}.zip")
        fun legacy() = JSONObject().put("version", 1).put("accountKey", value.accountKey)
            .put("localKey", value.localKey).put("recordId", value.recordId)
            .put("identity", DocumentIdentityJson.encode(value.identity)).toString().toByteArray()
    }

    @Test fun verifiedResultDoesNotRewritePrivateMetadataOrDocument() {
        val f = Fixture(); val binding = f.bindingFile.readBytes(); val archive = f.archive.readBytes()
        assertEquals("accepted", f.access.verified(f.value.accountKey, f.entry, f.snapshot, {}) { "accepted" })
        assertArrayEquals(binding, f.bindingFile.readBytes()); assertArrayEquals(archive, f.archive.readBytes())
    }

    @Test fun sameRecordUnlinkRelinkAndTombstoneNeverAuthorizeOldGeneration() {
        for (relink in listOf(false, true)) {
            val f = Fixture(); var invoked = false
            f.aliasBindings.remove(f.value.accountKey, f.entry)
            if (relink) f.aliasBindings.save(f.value)
            val bytes = f.bindingFile.readBytes()
            assertTrue(runCatching { f.access.verified(f.value.accountKey, f.entry, f.snapshot, {}) { invoked = true } }.isFailure)
            assertFalse(invoked); assertArrayEquals(bytes, f.bindingFile.readBytes())
        }
    }

    @Test fun absentNullMismatchedScopeAndNonceFailBeforeCallback() {
        val f = Fixture(); var invoked = false
        val invalid = listOf(PortableServerBindingSnapshot(null, null), PortableServerBindingSnapshot(f.saved.nonce, null),
            f.snapshot.copy(nonce = null), f.snapshot.copy(binding = f.saved.copy(nonce = NotesFixture.otherId)),
            f.snapshot.copy(binding = f.saved.copy(accountKey = "another-account")),
            f.snapshot.copy(binding = f.saved.copy(localKey = "another-copy")),
            f.snapshot.copy(binding = f.saved.copy(recordId = NotesFixture.otherId)))
        val before = f.bindingFile.readBytes()
        for (snapshot in invalid) {
            assertTrue(runCatching { f.access.verified(f.value.accountKey, f.entry, snapshot, {}) { invoked = true } }.isFailure)
            assertFalse(invoked); assertArrayEquals(before, f.bindingFile.readBytes())
        }
        assertTrue(runCatching { f.bindings.verified("wrong-account", f.entry, f.snapshot, {}) { invoked = true } }.isFailure)
        assertTrue(runCatching { f.access.verified("wrong-account", f.entry, f.snapshot, {}) { invoked = true } }.isFailure)
        assertFalse(invoked)
    }

    @Test fun legacyUnsupportedCorruptOversizedAndInvalidUtf8ArePreservedWithoutMigration() {
        val f = Fixture(); var invoked = false
        val cases = listOf(f.legacy(), "{}".toByteArray(), f.legacy().toString(Charsets.UTF_8).replace("\"version\":1", "\"version\":3").toByteArray(),
            ByteArray(65_537) { ' '.code.toByte() }, f.legacy() + byteArrayOf(0xc3.toByte(), 0x28))
        for (bytes in cases) {
            f.bindingFile.writeBytes(bytes)
            assertTrue(runCatching { f.access.verified(f.value.accountKey, f.entry, f.snapshot, {}) { invoked = true } }.isFailure)
            assertFalse(invoked); assertArrayEquals(bytes, f.bindingFile.readBytes())
        }
        f.bindingFile.writeBytes(f.legacy())
        val migrated = f.bindings.readSnapshot(f.value.accountKey, f.entry)
        assertNotNull(migrated.nonce); assertEquals(2, JSONObject(f.bindingFile.readText()).getInt("version"))
    }

    @Test fun fullCanonicalMetadataPositionNotesAndPassiveExtensionsMustStillMatch() {
        val changes: List<(ExchangeDocument) -> ExchangeDocument> = listOf(
            { it.copy(bookTitle = "Renamed") },
            { it.copy(position = ExchangeAnchor("p1", 1)) },
            { it.copy(notes = listOf(ExchangeNote(NotesFixture.noteId, "note", "Memo", "Memo", "First", ExchangeAnchor("p1", 0), NotesFixture.time))) },
            { it.copy(organization = ExchangeOrganization("Folder", listOf("é", "é"), true)) },
            { it.copy(glossary = listOf(ExchangeGlossaryEntry("Source", "Target", "term", "Shown", false, false))) },
            { it.copy(extensionsJson = JSONObject(requireNotNull(it.extensionsJson)).put("passiveSibling", JSONObject().put("value", 1)).toString()) },
        )
        for (change in changes) {
            val f = Fixture(); val binding = f.bindingFile.readBytes(); var invoked = false
            f.aliasLibrary.update(f.entry, change)
            assertTrue(runCatching { f.access.verified(f.value.accountKey, f.entry, f.snapshot, {}) { invoked = true } }.isFailure)
            assertFalse(invoked); assertArrayEquals(binding, f.bindingFile.readBytes())
        }
    }

    @Test fun changedFullProofOrDeletedArchiveCannotEnterCallback() {
        for (delete in listOf(false, true)) {
            val f = Fixture(); var invoked = false
            val before = f.bindingFile.readBytes()
            if (delete) assertTrue(f.archive.delete()) else f.library.update(f.entry) {
                it.copy(paragraphs = it.paragraphs.mapIndexed { index, p -> if (index == 0) p.copy(text = p.text + "!") else p })
            }
            assertTrue(runCatching { f.access.verified(f.value.accountKey, f.entry, f.snapshot, {}) { invoked = true } }.isFailure)
            assertFalse(invoked); assertArrayEquals(before, f.bindingFile.readBytes())
        }
    }

    @Test fun inputListsAreFrozenBeforeAnyExternalGuardAndUnknownSiblingIsPreserved() {
        val f = Fixture()
        f.library.update(f.entry) { it.copy(organization = ExchangeOrganization("Folder", listOf("é", "é"), true),
            extensionsJson = JSONObject(requireNotNull(it.extensionsJson)).put("unknownSibling", JSONObject().put("keep", "  中  文  ")).toString()) }
        val canonical = f.library.read(f.entry).documents.single()
        val paragraphs = canonical.paragraphs.toMutableList(); val tags = canonical.organization.tags.toMutableList()
        val selected = f.entry.copy(document = canonical.copy(paragraphs = paragraphs, organization = canonical.organization.copy(tags = tags)))
        val archive = f.archive.readBytes()
        assertTrue(f.access.verified(f.value.accountKey, selected, f.snapshot, { paragraphs.clear(); tags.clear() }) { true })
        assertTrue(paragraphs.isEmpty()); assertTrue(tags.isEmpty())
        assertEquals(canonical, f.library.read(f.entry).documents.single()); assertArrayEquals(archive, f.archive.readBytes())
    }

    @Test fun assetBackedAndOversizedExtensionInputsAreRejectedBeforeAnyGuard() {
        val f = Fixture(); var guard = 0; var invoked = false
        val invalid = listOf(f.entry.document.copy(assets = listOf(ExchangeAssetReference("assets/" + "a".repeat(64), "pdf"))),
            f.entry.document.copy(extensionsJson = JSONObject(requireNotNull(f.entry.document.extensionsJson))
                .put("tooLarge", "x".repeat(LibraryExchangeLimits.EXTENSIONS_BYTES)).toString()))
        for (document in invalid) {
            assertTrue(runCatching { f.access.verified(f.value.accountKey, f.entry.copy(document = document), f.snapshot, { guard++ }) { invoked = true } }.isFailure)
            assertEquals(0, guard); assertFalse(invoked)
        }
    }

    @Test fun sessionCancellationBeforeBlockDoesNotChangeEitherStore() {
        val f = Fixture(); val binding = f.bindingFile.readBytes(); val archive = f.archive.readBytes(); var invoked = false
        assertTrue(runCatching { f.access.verified(f.value.accountKey, f.entry, f.snapshot, { error("Session expired") }) { invoked = true } }.isFailure)
        assertFalse(invoked); assertArrayEquals(binding, f.bindingFile.readBytes()); assertArrayEquals(archive, f.archive.readBytes())
    }

    @Test fun reentrantAliasMutationAndMigrationAreRejectedInsideGuardAndCallback() {
        val f = Fixture(); val binding = f.bindingFile.readBytes(); val archive = f.archive.readBytes()
        var transformInvoked = false
        assertTrue(f.access.verified(f.value.accountKey, f.entry, f.snapshot, {
            assertTrue(runCatching { f.aliasLibrary.update(f.entry) { transformInvoked = true; it } }.isFailure)
            assertTrue(runCatching { f.aliasBindings.remove(f.value.accountKey, f.entry) }.isFailure)
        }) {
            assertTrue(runCatching { f.aliasBindings.save(f.value) }.isFailure)
            assertTrue(runCatching { f.aliasLibrary.importArchive(archive) }.isFailure)
            // Nested read-only access stays legal and sees the same generation.
            assertEquals(f.snapshot, f.aliasBindings.readSnapshot(f.value.accountKey, f.entry))
            f.access.verified(f.value.accountKey, f.entry, f.snapshot, {}) { true }
        })
        assertFalse(transformInvoked); assertArrayEquals(binding, f.bindingFile.readBytes()); assertArrayEquals(archive, f.archive.readBytes())
    }

    @Test fun legacyMigrationForAnotherAssociationCannotWriteInsideVerifiedDirectory() {
        val f = Fixture()
        val other = f.entry.copy(packageId = "b".repeat(64))
        val otherValue = f.value.copy(localKey = other.key)
        val otherFile = File(f.bindingDirectory, DocumentIds.sha256("${otherValue.accountKey}\n${otherValue.localKey}".toByteArray()) + ".json")
        val bytes = JSONObject().put("version", 1).put("accountKey", otherValue.accountKey).put("localKey", otherValue.localKey)
            .put("recordId", otherValue.recordId).put("identity", DocumentIdentityJson.encode(otherValue.identity)).toString().toByteArray()
        otherFile.writeBytes(bytes)
        f.access.verified(f.value.accountKey, f.entry, f.snapshot, {}) {
            assertTrue(runCatching { f.aliasBindings.readSnapshot(otherValue.accountKey, other) }.isFailure)
        }
        assertArrayEquals(bytes, otherFile.readBytes())
        assertNotNull(f.aliasBindings.readSnapshot(otherValue.accountKey, other).nonce)
    }

    @Test fun successfulCallbackRemainsSuccessfulWhenSessionExpiresAfterItsCommit() {
        val f = Fixture(); var current = true; var committed = false
        assertEquals(42, f.access.verified(f.value.accountKey, f.entry, f.snapshot, { check(current) }) { committed = true; current = false; 42 })
        assertTrue(committed); assertFalse(current)
        // Read-only markers are restored on both success and failure.
        f.aliasBindings.save(f.value); f.aliasLibrary.update(f.entry) { it.copy(bookTitle = "After commit") }
    }

    @Test fun callbackFailureReleasesBothReadOnlyScopesWithoutChangingFiles() {
        val f = Fixture(); val binding = f.bindingFile.readBytes(); val archive = f.archive.readBytes()
        assertTrue(runCatching { f.access.verified(f.value.accountKey, f.entry, f.snapshot, {}) { error("Journal rejected") } }.isFailure)
        assertArrayEquals(binding, f.bindingFile.readBytes()); assertArrayEquals(archive, f.archive.readBytes())
        assertNotEquals(f.saved.nonce, f.aliasBindings.save(f.value).nonce)
        f.aliasLibrary.update(f.entry) { it.copy(bookTitle = "After rejection") }
    }

    @Test fun verifiedCallbackBlocksConcurrentAliasSaveAndRemoveUntilItReturns() {
        for (remove in listOf(false, true)) {
            val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val worker = AtomicReference<Thread>(); val started = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
            try {
                val verifying = executor.submit<Int> { f.access.verified(f.value.accountKey, f.entry, f.snapshot, {}) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); 7
                } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val write = executor.submit<Unit> {
                    worker.set(Thread.currentThread()); started.countDown()
                    if (remove) f.aliasBindings.remove(f.value.accountKey, f.entry) else f.aliasBindings.save(f.value)
                }
                assertTrue(started.await(5, TimeUnit.SECONDS)); awaitBlocked(worker.get()); assertFalse(write.isDone)
                release.countDown(); assertEquals(7, verifying.get(5, TimeUnit.SECONDS)); write.get(5, TimeUnit.SECONDS)
                if (remove) assertNull(f.bindings.read(f.value.accountKey, f.entry)) else assertNotEquals(f.saved.nonce, f.bindings.read(f.value.accountKey, f.entry)!!.nonce)
            } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }

    @Test fun verifiedCallbackBlocksConcurrentReaderUpdateAcrossCanonicalDirectoryAliases() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>(); val started = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try {
            val verifying = executor.submit<Boolean> { f.access.verified(f.value.accountKey, f.entry, f.snapshot, {}) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val update = executor.submit<Unit> {
                worker.set(Thread.currentThread()); started.countDown(); f.aliasLibrary.update(f.entry) { it.copy(bookTitle = "Latest reader") }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS)); awaitBlocked(worker.get()); assertFalse(update.isDone)
            release.countDown(); assertTrue(verifying.get(5, TimeUnit.SECONDS)); update.get(5, TimeUnit.SECONDS)
            assertEquals("Latest reader", f.library.read(f.entry).documents.single().bookTitle)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals("The alias operation did not wait on the verified monitor", Thread.State.BLOCKED, thread.state)
    }
}

