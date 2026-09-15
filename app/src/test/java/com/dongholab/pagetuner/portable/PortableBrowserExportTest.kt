package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.LibraryExchangeCodec
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in verification of the actual ZIP downloaded through the browser UI. */
class PortableBrowserExportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun browserDownloadImportsAndReexportsAllDocumentsWithNativeMetadata() {
        val path = System.getenv("PAGETUNER_BROWSER_EXCHANGE_FIXTURE")
        assumeTrue("Supply the browser UI export to run this interoperability check", !path.isNullOrBlank())
        val bytes = File(requireNotNull(path)).readBytes()
        val source = LibraryExchangeCodec.read(bytes)
        assertEquals(3, source.documents.size)
        assertTrue(source.documents.any { it.kind == "translation" && it.language == "ko" })
        assertTrue(source.documents.any { it.paragraphs.any { p -> p.text == "Native source 🌏" } })
        assertTrue(source.documents.any { it.notes.any { n -> n.title == "Read here" } })
        assertTrue(source.documents.any { it.notes.any { n -> n.title == "Tea page checkpoint" } })
        val store = PortableLibraryStore(temporary.newFolder())
        val imported = store.importArchive(bytes)
        assertEquals(3, imported.entries.size)
        for (entry in imported.entries) {
            val expected = source.documents.single { it.id == entry.document.id }
            val mapped = PortableDocumentMapper.reader(entry.document, source.assets, entry.readerId)
            assertEquals(expected.paragraphs.joinToString("") { it.text }, mapped.document.pages.joinToString("") { it.plainText })
            val restored = LibraryExchangeCodec.read(store.export(entry))
            assertEquals(expected, restored.documents.single())
            restored.assets.forEach { asset -> assertArrayEquals(source.assets.single { it.sha256 == asset.sha256 }.bytes, asset.bytes) }
        }
    }
}
