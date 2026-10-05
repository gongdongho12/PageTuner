package com.dongholab.pagetuner.document

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class EpubDocumentReaderTest {
    @Test fun fileExportKeepsCompleteSpineTextBeforePaginationWithoutDecodingImages() {
        val long = "a".repeat(399) + "🌏" + "z".repeat(1800)
        val value = buildEpub(mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"package\"/></rootfiles></container>",
            "package" to "<package><manifest><item id=\"a\" href=\"first.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"b\" href=\"last\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"b\"/><itemref idref=\"a\"/></spine></package>",
            "first.xhtml" to "<html><body><p>$long</p><p>After all pages.</p><img src=\"missing.png\" alt=\"Illustration\"/></body></html>",
            "last" to "<html><body><p>Spine first.</p></body></html>"
        ))
        assertEquals(listOf("Spine first.", "$long\n\nAfter all pages.\n\n[Image: Illustration]"), EpubDocumentReader.extractTextForExport(value))
    }

    @Test fun fileExportRejectsMissingSpineContentAndExpandedOrTextLimitsWithoutTruncation() {
        val value = minimalEpub("<p>" + "x".repeat(2000) + "</p>")
        assertThrows(EpubReadLimitException::class.java) { EpubDocumentReader.extractTextForExport(value, maxTextCharacters = 1000) }
        assertThrows(EpubReadLimitException::class.java) { EpubDocumentReader.extractTextForExport(value, maxExpandedBytes = 100) }
        assertThrows(EpubReadLimitException::class.java) { EpubDocumentReader.extractTextForExport(value, maxEntries = 1) }
        val missing = buildEpub(mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>",
            "book.opf" to "<package><manifest><item id=\"a\" href=\"absent.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/></spine></package>"
        ))
        assertThrows(IllegalArgumentException::class.java) { EpubDocumentReader.extractTextForExport(missing) }
    }

    @Test
    fun sharingBudgetRejectsRepeatedImageAllocationsBeforeRetainingWholeDocument() {
        val epub = minimalEpub("<p>Hello</p><img src=\"image.png\"/><img src=\"image.png\"/>", mapOf("image.png" to onePixelPng))
        try {
            EpubDocumentReader.parse("book", epub, "Untitled", EpubReadLimits(10_000, 10, onePixelPng.size.toLong()))
            fail("Repeated image bytes must respect the total budget")
        } catch (_: EpubReadLimitException) { }
        try {
            EpubDocumentReader.parse("book", epub, "Untitled", EpubReadLimits(10_000, 1, 10_000))
            fail("Repeated image references must respect the count budget")
        } catch (_: EpubReadLimitException) { }
    }

    @Test
    fun xhtmlCannotReadAnExternalFileEntity() {
        val secret = java.io.File.createTempFile("epub-private", ".txt").apply { writeText("PRIVATE_CONTENT_MUST_NOT_ESCAPE") }
        try {
            val xhtml = "<!DOCTYPE html [<!ENTITY secret SYSTEM '${secret.toURI()}'>]><html><body><p>Before &secret; After</p></body></html>"
            val epub = minimalEpub(xhtml, alreadyWrapped = true)
            val document = EpubDocumentReader.parse("book", epub, "Untitled")
            assertFalse(document.pages.joinToString { it.plainText }.contains("PRIVATE_CONTENT_MUST_NOT_ESCAPE"))
        } finally { secret.delete() }
    }

    private fun minimalEpub(body: String, binary: Map<String, ByteArray> = emptyMap(), alreadyWrapped: Boolean = false): ByteArray = buildEpub(mapOf(
        "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>",
        "content.opf" to "<package><manifest><item id=\"c\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"i\" href=\"image.png\" media-type=\"image/png\"/></manifest><spine><itemref idref=\"c\"/></spine></package>",
        "chapter.xhtml" to if (alreadyWrapped) body else "<html><body>$body</body></html>"
    ), binary)

    @Test
    fun parsesSpineDocumentsIntoReaderPages() {
        val epub = buildEpub(
            mapOf(
                "META-INF/container.xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent(),
                "OEBPS/content.opf" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <manifest>
                        <item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="map" href="images/map.png" media-type="image/png"/>
                      </manifest>
                      <spine>
                        <itemref idref="chapter1"/>
                        <itemref idref="chapter2"/>
                      </spine>
                    </package>
                """.trimIndent(),
                "OEBPS/chapter1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <h1>First</h1>
                        <p>Hello page.</p>
                        <ul><li>Point one</li></ul>
                        <img alt="Map" src="images/map.png"/>
                      </body>
                    </html>
                """.trimIndent(),
                "OEBPS/text/chapter2.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body><h2>Second</h2><p>Second chapter.</p></body>
                    </html>
                """.trimIndent(),
            ),
            mapOf("OEBPS/images/map.png" to onePixelPng),
        )

        val document = EpubDocumentReader.parse(
            title = "sample.epub",
            bytes = epub,
            fallbackTitle = "Untitled",
        )

        assertEquals(DocumentFormat.EPUB, document.format)
        assertTrue(document.pages.isNotEmpty())
        assertEquals(listOf("First", "Second"), document.tableOfContents.map { it.title })
        assertEquals("First", document.pages.first().chapterTitle)

        val body = document.pages.joinToString("\n") { it.plainText }
        assertTrue(body.contains("Hello page."))
        assertTrue(body.contains("- Point one"))
        assertTrue(body.contains("[Image: Map]"))
        assertTrue(body.contains("Second chapter."))

        val image = document.pages.first().images.single()
        assertEquals("Map", image.altText)
        assertEquals("image/png", image.mimeType)
        assertArrayEquals(onePixelPng, image.bytes)
    }

    private fun buildEpub(
        textEntries: Map<String, String>,
        binaryEntries: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            textEntries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            binaryEntries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private val onePixelPng: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lY4kWQAAAABJRU5ErkJggg==",
    )
}
