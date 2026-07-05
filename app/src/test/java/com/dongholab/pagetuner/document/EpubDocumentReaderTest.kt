package com.dongholab.pagetuner.document

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDocumentReaderTest {
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
    }

    private fun buildEpub(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
