package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortablePdfContentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/library-exchange-v1/portable-v1.zip")).use { it.readBytes() }
    @Test fun preparationRereadsCurrentSelectedDocumentAndOriginalBytesWithoutEditingArchive() {
        val directory = temporary.newFolder(); val store = PortableLibraryStore(directory)
        val entries = store.importArchive(fixture()).entries
        val selected = entries.single { it.document.assets.any { ref -> ref.role == "pdf" } }
        val stale = selected.copy(document = entries.first { it != selected }.document)
        store.update(selected) { it.copy(language = "ja") }
        val before = directory.listFiles()!!.single().readBytes()
        val prepared = store.preparePdfContent(stale)
        val actual = store.read(selected)
        assertEquals(PdfContentDocuments.fromPackage(actual, selected.documentIndex).content, prepared.content)
        assertEquals("ja", prepared.content.language)
        assertEquals(selected.document.paragraphs, prepared.content.paragraphs)
        val original = actual.assets.single { it.path == actual.documents[selected.documentIndex].assets.single { ref -> ref.role == "pdf" }.path }
        assertArrayEquals(original.bytes, prepared.assets.single { it.path == original.path }.bytes)
        assertArrayEquals(before, directory.listFiles()!!.single().readBytes())
        assertFalse(prepared.content.toString().contains("glossary"))
    }
    @Test fun wrongIndexTextOnlyAndMissingArchiveFailWithoutSourceMutation() {
        val directory = temporary.newFolder(); val store = PortableLibraryStore(directory); val entries = store.importArchive(fixture()).entries
        val original = directory.listFiles()!!.single().readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.preparePdfContent(entries.first().copy(documentIndex = 99)) }
        assertThrows(IllegalArgumentException::class.java) { store.preparePdfContent(entries.first { entry -> entry.document.assets.none { it.role == "pdf" } }) }
        assertThrows(Exception::class.java) { store.preparePdfContent(entries.first().copy(packageId = "0".repeat(64))) }
        assertArrayEquals(original, directory.listFiles()!!.single().readBytes())
    }
}
