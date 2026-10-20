package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.NotesFixture
import com.dongholab.pagetuner.document.DocumentIds
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableServerBindingStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun entry() = PortableLibraryEntry("a".repeat(64), 0,
        PortableDocumentMapper.server(NotesFixture.document().source).documents.single())

    @Test fun associationSurvivesRestartAndMetadataChangesButNeverCrossesAccountOrLocalCopy() {
        val directory = temporary.newFolder()
        val entry = entry()
        val identity = requireNotNull(DocumentIdentityJson.fromDocument(entry.document))
        val binding = PortableServerBinding("account-origin-a", entry.key, NotesFixture.recordId, identity)
        val store = PortableServerBindingStore(directory)
        assertNull(store.read(binding.accountKey, entry))
        val saved = store.save(binding)
        val restarted = PortableServerBindingStore(directory)
        assertEquals(saved, restarted.read(binding.accountKey, entry))
        assertEquals(saved, restarted.read(binding.accountKey, entry.copy(document = entry.document.copy(bookTitle = "Renamed",
            position = ExchangeAnchor("p1", 6), organization = ExchangeOrganization("Folder", listOf("tag"), true)))))
        assertNull(restarted.read("another-account-or-origin", entry))
        assertNull(restarted.read(binding.accountKey, entry.copy(documentIndex = 1)))
        assertNull(restarted.read(binding.accountKey, entry.copy(packageId = "b".repeat(64))))
        assertEquals(entry.document, entry().document)
    }

    @Test fun tamperedBodyAndCorruptSavedProofCannotActivateBinding() {
        val directory = temporary.newFolder()
        val entry = entry()
        val binding = PortableServerBinding("a", entry.key, NotesFixture.recordId, requireNotNull(DocumentIdentityJson.fromDocument(entry.document)))
        val store = PortableServerBindingStore(directory)
        store.save(binding)
        val changed = entry.copy(document = entry.document.copy(paragraphs = entry.document.paragraphs.mapIndexed { i, p -> if (i == 0) p.copy(text = p.text + "!") else p }))
        assertNull(store.read("a", changed))
        directory.listFiles()!!.single().writeText("{}")
        assertNull(store.read("a", entry))
    }

    @Test fun removalLeavesImportedDocumentIntact() {
        val directory = temporary.newFolder()
        val entry = entry()
        val binding = PortableServerBinding("a", entry.key, NotesFixture.recordId, requireNotNull(DocumentIdentityJson.fromDocument(entry.document)))
        val store = PortableServerBindingStore(directory)
        store.save(binding)
        store.remove("a", entry)
        assertNull(store.read("a", entry))
        assertEquals(entry.document, entry().document)
    }

    private fun binding(entry: PortableLibraryEntry = entry()) = PortableServerBinding("account-origin-a", entry.key,
        NotesFixture.recordId, requireNotNull(DocumentIdentityJson.fromDocument(entry.document)))

    private fun file(directory: File, binding: PortableServerBinding) = File(directory,
        DocumentIds.sha256("${binding.accountKey}\n${binding.localKey}".toByteArray(Charsets.UTF_8)) + ".json")

    private fun legacy(binding: PortableServerBinding) = JSONObject().put("version", 1)
        .put("accountKey", binding.accountKey).put("localKey", binding.localKey).put("recordId", binding.recordId)
        .put("identity", DocumentIdentityJson.encode(binding.identity)).toString()

    @Test fun legacyMigrationPreservesAssociationAndIsStableAcrossConcurrentInstancesAndRestart() {
        val directory = temporary.newFolder()
        val entry = entry(); val original = binding(entry)
        val stored = file(directory, original)
        stored.writeText(legacy(original))
        val executor = Executors.newFixedThreadPool(4)
        val snapshots = try {
            executor.invokeAll(List(12) { Callable {
                PortableServerBindingStore(File(directory, ".")).readSnapshot(original.accountKey, entry)
            } }).map { it.get() }
        } finally { executor.shutdownNow() }
        assertEquals(1, snapshots.toSet().size)
        val migrated = requireNotNull(snapshots.first().binding)
        assertEquals(original.copy(nonce = migrated.nonce), migrated)
        assertEquals(migrated.nonce, snapshots.first().nonce)
        assertEquals(2, JSONObject(stored.readText()).getInt("version"))
        assertEquals(snapshots.first(), PortableServerBindingStore(directory).readSnapshot(original.accountKey, entry))
        assertEquals(listOf(stored.name), directory.listFiles()!!.map { it.name })
    }

    @Test fun invalidLegacyProofDoesNotMigrateOrReplaceTheOriginalBytes() {
        val directory = temporary.newFolder(); val entry = entry(); val original = binding(entry)
        val stored = file(directory, original)
        val bytes = legacy(original).toByteArray()
        stored.writeBytes(bytes)
        val changed = entry.copy(document = entry.document.copy(paragraphs = entry.document.paragraphs.mapIndexed { i, p ->
            if (i == 0) p.copy(text = p.text + " changed") else p
        }))
        val store = PortableServerBindingStore(directory)
        assertTrue(runCatching { store.readSnapshot(original.accountKey, changed) }.isFailure)
        assertNull(store.read(original.accountKey, changed))
        assertArrayEquals(bytes, stored.readBytes())
        assertNotNull(store.read(original.accountKey, entry))
    }

    @Test fun repeatedSavesAndUnlinksRetainDistinctDurableGenerations() {
        val directory = temporary.newFolder(); val entry = entry(); val original = binding(entry)
        val store = PortableServerBindingStore(directory)
        assertEquals(PortableServerBindingSnapshot(null, null), store.readSnapshot(original.accountKey, entry))
        store.remove(original.accountKey, entry)
        val absent = store.readSnapshot(original.accountKey, entry)
        assertNotNull(absent.nonce); assertNull(absent.binding)
        store.remove(original.accountKey, entry)
        val unlinked = store.readSnapshot(original.accountKey, entry)
        assertNotEquals(absent.nonce, unlinked.nonce)
        val first = store.save(original)
        val second = store.save(original)
        assertNotEquals(first.nonce, second.nonce)
        store.remove(original.accountKey, entry)
        val third = store.save(original)
        assertNotEquals(second.nonce, third.nonce)
        assertEquals(first.copy(nonce = third.nonce), third)
        assertEquals(third, PortableServerBindingStore(directory).read(original.accountKey, entry))
    }

    @Test fun staleFailureCannotUnlinkSameRecordReconnectedByAnotherInstance() {
        val directory = temporary.newFolder(); val entry = entry(); val original = binding(entry)
        val first = PortableServerBindingStore(directory); val second = PortableServerBindingStore(directory)
        val before = first.save(original)
        second.remove(original.accountKey, entry)
        val after = second.save(original)
        assertFalse(first.removeIfCurrent(original.accountKey, entry, before))
        assertEquals(after, first.read(original.accountKey, entry))
        assertTrue(first.removeIfCurrent(original.accountKey, entry, after))
        assertNull(second.read(original.accountKey, entry))
        assertNotEquals(after.nonce, second.readSnapshot(original.accountKey, entry).nonce)
    }

    @Test fun conditionalSaveRejectsAbsentAbaWrongScopeAndCancellationBeforePublication() {
        val directory = temporary.newFolder(); val entry = entry(); val original = binding(entry)
        val store = PortableServerBindingStore(directory)
        val absent = store.readSnapshot(original.accountKey, entry)
        store.remove(original.accountKey, entry)
        assertTrue(runCatching { store.saveIfCurrent(original.accountKey, entry, absent, original) }.isFailure)
        val expected = store.readSnapshot(original.accountKey, entry)
        assertTrue(runCatching { store.saveIfCurrent(original.accountKey, entry, expected, original.copy(accountKey = "other")) }.isFailure)
        var checks = 0
        assertTrue(runCatching { store.saveIfCurrent(original.accountKey, entry, expected, original) {
            checks++
            check(checks < 2) { "Account changed while writing." }
        } }.isFailure)
        assertEquals(2, checks)
        assertEquals(expected, store.readSnapshot(original.accountKey, entry))
        assertEquals(1, directory.listFiles()!!.size)
        val saved = store.saveIfCurrent(original.accountKey, entry, expected, original)
        assertEquals(saved, store.read(original.accountKey, entry))
    }

    @Test fun corruptUnsupportedCoercedAndOversizedFilesRemainUnmodified() {
        val entry = entry(); val original = binding(entry)
        val malformed = listOf("{}", legacy(original).replace("\"version\":1", "\"version\":3"),
            legacy(original).replace("\"version\":1", "\"version\":1,\"version\":2"),
            legacy(original) + " true",
            legacy(original).replace("\"version\":1", "\"version\":\"1\""),
            legacy(original).replace("\"version\":1", "\"version\":1.5"),
            legacy(original).replace("\"accountKey\":\"account-origin-a\"", "\"accountKey\":123"),
            " ".repeat(65_537))
        malformed.forEach { value ->
            val directory = temporary.newFolder(); val stored = file(directory, original)
            val bytes = value.toByteArray(); stored.writeBytes(bytes)
            val store = PortableServerBindingStore(directory)
            assertNull(store.read(original.accountKey, entry))
            assertTrue(runCatching { store.readSnapshot(original.accountKey, entry) }.isFailure)
            assertTrue(runCatching { store.save(original) }.isFailure)
            assertTrue(runCatching { store.remove(original.accountKey, entry) }.isFailure)
            assertArrayEquals(bytes, stored.readBytes())
        }
    }

    @Test fun invalidUtf8IsRejectedWithoutReplacingPrivateMetadata() {
        val directory = temporary.newFolder(); val entry = entry(); val original = binding(entry)
        val stored = file(directory, original)
        val bytes = legacy(original).toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
        stored.writeBytes(bytes)
        val store = PortableServerBindingStore(directory)
        assertTrue(runCatching { store.readSnapshot(original.accountKey, entry) }.isFailure)
        assertTrue(runCatching { store.save(original) }.isFailure)
        assertArrayEquals(bytes, stored.readBytes())
    }

    @Test fun oversizedWriteLeavesPriorGenerationUntouched() {
        val directory = temporary.newFolder(); val entry = entry(); val original = binding(entry)
        val store = PortableServerBindingStore(directory)
        val saved = store.save(original)
        assertTrue(runCatching { store.save(original.copy(accountKey = "a".repeat(65_536))) }.isFailure)
        assertEquals(saved, store.read(original.accountKey, entry))
        assertEquals(1, directory.listFiles()!!.size)
    }
}
