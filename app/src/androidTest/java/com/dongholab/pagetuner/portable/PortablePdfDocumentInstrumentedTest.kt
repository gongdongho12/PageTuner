package com.dongholab.pagetuner.portable

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.content.DocumentFileExports
import com.dongholab.pagetuner.core.content.DocumentFileFormat
import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android PDF/font tests. Compiling this class does not establish that a device ran it. */
@RunWith(AndroidJUnit4::class)
class PortablePdfDocumentInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualPdfDocumentHasMultipleA4PagesAndRendersKoreanWithMargins() {
        val paragraphs = (1..180).map { "문단 $it 한국어 본문과 English text, 中文 日本語 🌏.\n다음 줄 $it" }
        val file = PortablePdfDocument.create("한글 제목", "둘째 회차", paragraphs)
        assertEquals("application/pdf", file.mimeType)
        assertTrue(file.bytes.decodeToString(0, 5).startsWith("%PDF-"))
        assertTrue(file.filename.endsWith(".pdf"))
        withRenderer(file.bytes) { renderer ->
            assertTrue(renderer.pageCount > 2)
            for (index in 0 until renderer.pageCount) {
                renderer.openPage(index).use { page ->
                    assertEquals(595, page.width)
                    assertEquals(842, page.height)
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        assertTrue("Every exported page has visible content", pixels.count { Color.red(it) < 200 } > 30)
                        assertTrue("Outer A4 margin stays clear", pixels.indices.filter { offset ->
                            val x = offset % bitmap.width
                            val y = offset / bitmap.width
                            x < 20 || x >= bitmap.width - 20 || y < 20 || y >= bitmap.height - 20
                        }.all { Color.red(pixels[it]) >= 250 })
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun actualPdfExtractsCompleteKoreanTextAcrossAllPagesWithoutDuplicateOrMissingParagraphs() {
        val paragraphs = (1..180).map { "시작${it}번 문단 한국어 본문 中文 日本語 English.\n끝${it}번" }
        val source = ExchangeDocument("keep-original-id", "한글 제목", "제2회", "ko", "local",
            paragraphs.mapIndexed { index, text -> ExchangeParagraph("paragraph-$index", text) },
            extensionsJson = """{"passive":{"secret":"PRIVATE_METADATA"}}""")
        val archive = LibraryExchangePackage("2026-10-05T00:00:00Z", listOf(source))
        val file = PortableDocumentFiles.fromArchive(archive, 0, PortableDocumentFileFormat.PDF_DOCUMENT)
        val expected = DocumentFileExports.text(source.bookTitle, source.chapterTitle, paragraphs, DocumentFileFormat.TXT).text
        val actual = withRenderer(file.bytes) { renderer ->
            assertTrue(renderer.pageCount > 2)
            (0 until renderer.pageCount).joinToString("\n") { index ->
                renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }
            }
        }
        // PDF text extraction may reorder line whitespace; all actual prose/codepoints must survive.
        assertEquals(expected.filterNot(Char::isWhitespace), actual.filterNot(Char::isWhitespace))
        assertEquals(source, archive.documents.single())
        assertFalse(actual.contains("PRIVATE_METADATA"))
    }

    @Test fun pageByteInputAndCancellationFailuresNeverPublishAPartialPdf() {
        assertThrows(IllegalArgumentException::class.java) {
            PortablePdfDocument.create("Title", "Title", listOf("many lines\n".repeat(400)), maximumPages = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortablePdfDocument.create("Title", "Title", listOf("한글 본문"), maximumBytes = 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortablePdfDocument.create("Title", "Title", listOf("x".repeat(5_000_000)))
        }
        var activeChecks = 0
        assertThrows(CancellationException::class.java) {
            PortablePdfDocument.create("Title", "Title", listOf("한글 여러 줄\n".repeat(400)), checkActive = {
                if (++activeChecks >= 5) throw CancellationException("Cancelled during pages")
            })
        }
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) {
                PortablePdfDocument.create("Title", "Title", listOf("text"))
            }
        } finally { Thread.interrupted() }
    }

    private fun <T> withRenderer(bytes: ByteArray, action: (PdfRenderer) -> T): T {
        val file = File.createTempFile("generated-text-", ".pdf", context.cacheDir)
        return try {
            file.writeBytes(bytes)
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use(action)
        } finally { file.delete() }
    }
}
