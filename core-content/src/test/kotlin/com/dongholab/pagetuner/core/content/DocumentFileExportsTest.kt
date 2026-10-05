package com.dongholab.pagetuner.core.content

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentFileExportsTest {
    @Test
    fun sharedTextVectorsMatchExactly() {
        vectors("vectors.json").forEach { vector ->
            val expected = vector.getJSONObject("expected")
            val result = export(vector)
            val name = vector.getString("name")
            assertEquals(name, expected.getString("filename"), result.filename)
            assertEquals(name, expected.getString("mimeType"), result.mimeType)
            assertEquals(name, expected.getString("text"), result.text)
        }
    }

    @Test
    fun sharedFilenameVectorsMatchExactly() {
        vectors("filename-vectors.json").forEach { vector ->
            assertEquals(
                vector.getString("name"),
                vector.getString("expected"),
                DocumentFileExports.safeFilename(
                    vector.getString("bookTitle"), vector.getString("chapterTitle"), vector.getString("extension"),
                ),
            )
        }
    }

    @Test
    fun sharedInvalidTextVectorsAreRejected() {
        vectors("rejected-vectors.json").forEach { vector ->
            assertThrows(vector.getString("name"), IllegalArgumentException::class.java) { export(vector) }
        }
    }

    @Test
    fun inputLimitIsInclusiveAndIncludesRawTitles() {
        val allowed = "x".repeat(DocumentFileExports.MAX_INPUT_CODE_UNITS - 1)
        val result = DocumentFileExports.text("B", "", listOf(allowed), DocumentFileFormat.TXT)
        assertEquals("B\n\n$allowed\n", result.text)
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFileExports.text(" B", "", listOf(allowed), DocumentFileFormat.TXT)
        }
    }

    @Test
    fun paragraphLimitIsInclusiveWithoutDroppingBlankParagraphs() {
        val paragraphs = List(DocumentFileExports.MAX_PARAGRAPHS) { "" }
        val result = DocumentFileExports.text("B", "", paragraphs, DocumentFileFormat.TXT)
        assertEquals("B" + "\n".repeat(2 * paragraphs.size + 1), result.text)
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFileExports.text("B", "", paragraphs + "", DocumentFileFormat.TXT)
        }
    }

    @Test
    fun worstCaseMarkdownExpansionRemainsBoundedAndComplete() {
        val content = "*".repeat(DocumentFileExports.MAX_INPUT_CODE_UNITS - 1)
        val result = DocumentFileExports.text("B", "", listOf(content), DocumentFileFormat.MARKDOWN)
        assertEquals("# B\n\n" + "\\*".repeat(content.length) + "\n", result.text)
        assertFalse(result.text.length > DocumentFileExports.MAX_OUTPUT_CODE_UNITS)
    }

    @Test
    fun filenameExtensionCannotBecomeAPathAndTitlesAreValidated() {
        listOf("../pdf", ".txt", "TXT", "EPUB", "").forEach { extension ->
            assertThrows(IllegalArgumentException::class.java) {
                DocumentFileExports.safeFilename("Book", "", extension)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFileExports.safeFilename("Bad\u0000title", "", "pdf")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFileExports.safeFilename("x".repeat(DocumentFileExports.MAX_INPUT_CODE_UNITS), "x", "pdf")
        }
    }

    private fun export(vector: JSONObject): DocumentTextFile = DocumentFileExports.text(
        vector.getString("bookTitle"),
        vector.getString("chapterTitle"),
        vector.getJSONArray("paragraphs").let { values -> List(values.length()) { values.getString(it) } },
        DocumentFileFormat.valueOf(vector.getString("format")),
    )

    private fun vectors(name: String): List<JSONObject> {
        val directory = File("../contracts/fixtures/document-file-export-v1")
            .takeIf { it.isDirectory } ?: File("contracts/fixtures/document-file-export-v1")
        val array = JSONArray(File(directory, name).readText(Charsets.UTF_8))
        return List(array.length()) { array.getJSONObject(it) }
    }
}
