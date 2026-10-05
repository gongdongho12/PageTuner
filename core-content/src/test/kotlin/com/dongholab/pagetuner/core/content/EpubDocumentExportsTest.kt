package com.dongholab.pagetuner.core.content

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.xml.sax.InputSource

class EpubDocumentExportsTest {
    @Test
    fun sharedPublicationVectorsMatchEveryEntryExactly() {
        vectors("vectors.json").forEach { vector ->
            val result = export(vector)
            val expected = vector.getJSONObject("expected")
            val label = vector.getString("name")
            assertEquals(label, expected.getString("filename"), result.filename)
            assertEquals(label, expected.getString("mimeType"), result.mimeType)
            val entries = expected.getJSONArray("entries")
            assertEquals(label, entries.length(), result.entries.size)
            result.entries.forEachIndexed { index, entry ->
                assertEquals(label, entries.getJSONObject(index).getString("path"), entry.path)
                assertEquals(label, entries.getJSONObject(index).getString("text"), entry.text)
            }
        }
    }

    @Test
    fun sharedInvalidXmlAndTextVectorsAreRejected() {
        vectors("rejected-vectors.json").forEach { vector ->
            assertThrows(vector.getString("name"), IllegalArgumentException::class.java) { export(vector) }
        }
    }

    @Test
    fun packageHasRequiredResourcesAndFreshContentIdentifier() {
        val publication = EpubDocumentExports.create("Book", "Chapter", "ko-KR", listOf("Body"))
        assertEquals(EpubDocumentEntry("mimetype", "application/epub+zip"), publication.entries.first())
        assertEquals(
            listOf("mimetype", "META-INF/container.xml", "EPUB/package.opf", "EPUB/nav.xhtml", "EPUB/content.xhtml", "EPUB/styles.css"),
            publication.entries.map { it.path },
        )
        val container = xml(publication.entry("META-INF/container.xml"))
        assertEquals("EPUB/package.opf", container.getElementsByTagName("rootfile").item(0).attributes.getNamedItem("full-path").nodeValue)
        val opf = xml(publication.entry("EPUB/package.opf"))
        val content = publication.entry("EPUB/content.xhtml")
        assertEquals("3.0", opf.documentElement.getAttribute("version"))
        assertEquals("urn:sha256:${StableContentHash.sha256(content)}", opf.getElementsByTagName("dc:identifier").item(0).textContent)
        assertEquals("ko-kr", opf.getElementsByTagName("dc:language").item(0).textContent)
        assertEquals("2000-01-01T00:00:00Z", opf.getElementsByTagName("meta").item(0).textContent)
        assertEquals(publication, EpubDocumentExports.create("Book", "Chapter", "ko-KR", listOf("Body")))
        val changed = EpubDocumentExports.create("Book", "Chapter", "ko-KR", listOf("Changed"))
        assertNotEquals(publication.entry("EPUB/package.opf"), changed.entry("EPUB/package.opf"))
        xml(publication.entry("EPUB/nav.xhtml"))
        xml(content)
    }

    @Test
    fun xmlRoundTripPreservesParagraphsAndTreatsMarkupAsInertText() {
        val paragraphs = listOf("  한글\t雪 😀\r\nline\r  ", "", "<script>alert('x')</script><img src=\"https://example.invalid\">&copy;")
        val publication = EpubDocumentExports.create(" A & <Book> ", " Ch\rtitle ", "en", paragraphs)
        val content = xml(publication.entry("EPUB/content.xhtml"))
        val nodes = content.getElementsByTagName("p")
        assertEquals(paragraphs.size, nodes.length)
        paragraphs.forEachIndexed { index, paragraph -> assertEquals(paragraph, nodes.item(index).textContent) }
        assertEquals("A & <Book>", content.getElementsByTagName("h1").item(0).textContent)
        assertEquals("Ch\rtitle", content.getElementsByTagName("h2").item(0).textContent)
        assertEquals(0, content.getElementsByTagName("script").length)
        assertEquals(0, content.getElementsByTagName("img").length)
        assertTrue(publication.entry("EPUB/styles.css").contains("white-space: pre-wrap"))
        assertFalse(publication.entry("EPUB/content.xhtml").contains('\r'))
    }

    @Test
    fun inputLimitIncludesRawLanguageAndRawTitles() {
        val body = "x".repeat(DocumentFileExports.MAX_INPUT_CODE_UNITS - 3)
        assertTrue(EpubDocumentExports.create("B", "", "en", listOf(body)).entry("EPUB/content.xhtml").contains(body))
        assertThrows(IllegalArgumentException::class.java) {
            EpubDocumentExports.create(" B", "", "en", listOf(body))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpubDocumentExports.create("B", "", " en", listOf(body))
        }
    }

    @Test
    fun paragraphLimitIncludesBlankParagraphsAndDoesNotTruncate() {
        val paragraphs = List(DocumentFileExports.MAX_PARAGRAPHS) { "" }
        val publication = EpubDocumentExports.create("B", "", null, paragraphs)
        assertEquals(paragraphs.size, Regex("<p></p>").findAll(publication.entry("EPUB/content.xhtml")).count())
        assertThrows(IllegalArgumentException::class.java) {
            EpubDocumentExports.create("B", "", null, paragraphs + "")
        }
    }

    @Test
    fun outputBudgetIncludesEscapingAndEveryRepeatedTitle() {
        assertThrows(IllegalArgumentException::class.java) {
            EpubDocumentExports.create("'".repeat(1_100_000), "", null, emptyList())
        }
    }

    @Test
    fun utf8ResourceBudgetRejectsOversizedCjkAndEscapedText() {
        listOf("雪".repeat(2_800_000), "'".repeat(1_400_000)).forEach { body ->
            assertThrows(IllegalArgumentException::class.java) {
                EpubDocumentExports.create("B", "", null, listOf(body))
            }
        }
    }

    @Test
    fun unsupportedLanguageTagsFallBackAndSafeFilenameSupportsEpub() {
        listOf(null, "", "en_US", "<script>", "x".repeat(129), "en-u-ca-gregory", " AUTO ", "Auto-Detect").forEach { language ->
            val opf = xml(EpubDocumentExports.create("CON", "", language, emptyList()).entry("EPUB/package.opf"))
            assertEquals("und", opf.getElementsByTagName("dc:language").item(0).textContent)
        }
        assertEquals("_CON.epub", DocumentFileExports.safeFilename("CON", "", "epub"))
    }

    private fun xml(value: String): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(InputSource(StringReader(value)))

    private fun EpubDocumentFile.entry(path: String): String = entries.single { it.path == path }.text

    private fun export(vector: JSONObject): EpubDocumentFile = EpubDocumentExports.create(
        vector.getString("bookTitle"),
        vector.getString("chapterTitle"),
        if (vector.isNull("language")) null else vector.getString("language"),
        vector.getJSONArray("paragraphs").let { values -> List(values.length()) { values.getString(it) } },
    )

    private fun vectors(name: String): List<JSONObject> {
        val directory = File("../contracts/fixtures/document-ebook-export-v1")
            .takeIf { it.isDirectory } ?: File("contracts/fixtures/document-ebook-export-v1")
        val array = JSONArray(File(directory, name).readText(Charsets.UTF_8))
        return List(array.length()) { array.getJSONObject(it) }
    }
}
