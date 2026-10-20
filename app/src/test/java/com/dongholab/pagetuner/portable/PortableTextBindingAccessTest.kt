package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.NotesFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableTextBindingAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture {
        val library = PortableLibraryStore(temporary.newFolder())
        val bindings = PortableServerBindingStore(temporary.newFolder())
        val entry = library.importArchive(LibraryExchangeCodec.write(
            PortableDocumentMapper.server(NotesFixture.document().source))).entries.single()
        val access = PortableTextBindingAccess(library, bindings)
        val value = PortableServerBinding("account", entry.key, NotesFixture.recordId,
            requireNotNull(DocumentIdentityJson.fromDocument(entry.document)))
        fun changedDocument() = library.update(entry) { it.copy(paragraphs = it.paragraphs.reversed()) }
    }

    @Test fun lateOpenAndFailureCannotConsumeOrDeleteReconnectedBinding() {
        val f = Fixture()
        f.bindings.save(f.value)
        val original = requireNotNull(f.bindings.read("account", f.entry))
        val guard = { f.access.validate(f.entry, original) {} }
        guard()
        f.bindings.remove("account", f.entry)
        f.bindings.save(f.value)
        val replacement = requireNotNull(f.bindings.read("account", f.entry))
        assertTrue(runCatching(guard).isFailure)
        assertFalse(f.access.removeIfCurrent(f.entry, original))
        assertEquals(replacement, f.bindings.read("account", f.entry))
        assertTrue(f.access.removeIfCurrent(f.entry, replacement))
        assertNull(f.bindings.read("account", f.entry))
    }

    @Test fun canonicalDocumentChangeAndSelectionCancellationRejectOpen() {
        val f = Fixture()
        f.bindings.save(f.value)
        val observed = requireNotNull(f.bindings.read("account", f.entry))
        assertTrue(runCatching { f.access.validate(f.entry, observed) { error("Selection changed") } }.isFailure)
        f.changedDocument()
        assertTrue(runCatching { f.access.validate(f.entry, observed) {} }.isFailure)
        assertTrue(runCatching { f.access.removeIfCurrent(f.entry, observed) }.isFailure)
        // The failed request did not remove the old generation; it remains tied to its original proof.
        assertEquals(observed, f.bindings.read("account", f.entry))
    }

    @Test fun absentToTombstoneAndSameValueReconnectionBothRejectStaleSave() {
        for (initiallyBound in listOf(false, true)) {
            val f = Fixture()
            if (initiallyBound) f.bindings.save(f.value)
            val expected = f.access.snapshot("account", f.entry)
            f.bindings.remove("account", f.entry)
            if (initiallyBound) f.bindings.save(f.value)
            val current = f.bindings.readSnapshot("account", f.entry)
            assertTrue(runCatching { f.access.save(f.entry, f.value, expected) {} }.isFailure)
            assertEquals(current, f.bindings.readSnapshot("account", f.entry))
        }
    }

    @Test fun finalDocumentAndSessionChecksPrecedeBindingWrite() {
        val f = Fixture()
        val expected = f.access.snapshot("account", f.entry)
        assertTrue(runCatching { f.access.save(f.entry, f.value, expected) { error("Account changed") } }.isFailure)
        assertEquals(expected, f.bindings.readSnapshot("account", f.entry))
        f.changedDocument()
        assertTrue(runCatching { f.access.save(f.entry, f.value, expected) {} }.isFailure)
        assertEquals(expected, f.bindings.readSnapshot("account", f.entry))
    }

    @Test fun cancellationAtStoreCommitGuardLeavesPriorGenerationUnchanged() {
        val f = Fixture()
        val expected = f.access.snapshot("account", f.entry)
        var checks = 0
        assertTrue(runCatching {
            f.access.save(f.entry, f.value, expected) {
                if (++checks == 3) error("Account changed immediately before replacement")
            }
        }.isFailure)
        assertEquals(3, checks)
        assertEquals(expected, f.bindings.readSnapshot("account", f.entry))
    }
}
