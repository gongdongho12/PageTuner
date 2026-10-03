package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.NotesFixture
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
        store.save(binding)
        val restarted = PortableServerBindingStore(directory)
        assertEquals(binding, restarted.read(binding.accountKey, entry))
        assertEquals(binding, restarted.read(binding.accountKey, entry.copy(document = entry.document.copy(bookTitle = "Renamed",
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
}
