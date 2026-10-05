package com.dongholab.pagetuner.document

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.display.DisplayMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a device/emulator: compilation alone is not evidence of actual PDF decoding. */
@RunWith(AndroidJUnit4::class)
class PdfDecoderContextInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pdf(pages: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                page.canvas.drawText("Page ${index + 1}", 24f, 50f, Paint().apply { textSize = 20f })
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally { document.close() }
        output.toByteArray()
    }
    private fun decoderFiles() = File(context.cacheDir, "pdf-decoder").listFiles().orEmpty().map { it.name }.toSet()

    @Test fun realDecoderKeepsOriginalBytesAndRawPhysicalCountAfterUriReplacement() {
        val before = decoderFiles(); val bytes = pdf(2)
        val source = File.createTempFile("decoder-qa-", ".pdf", context.cacheDir)
        try {
            source.writeBytes(bytes)
            val snapshot = PdfDocumentReader.read(context, Uri.fromFile(source), "PDF", "PDF")
            try {
                assertEquals(exchangeSha256(bytes), snapshot.context.originalFileSha256)
                assertEquals(2, snapshot.context.pageCount)
                if (Build.VERSION.SDK_INT < 35) assertEquals(PdfTextExtraction.Unavailable, snapshot.extraction)
                source.writeBytes(pdf(1))
                val rendered = PdfDocumentReader.renderPage(context, snapshot, 1, DisplayMode.Color)
                try { assertTrue(rendered.width in 1..1080); assertTrue(rendered.height in 1..1440) } finally { rendered.recycle() }
                assertThrows(IllegalArgumentException::class.java) { snapshot.exportOriginal(snapshot.document, source.readBytes()) }
                assertThrows(IllegalArgumentException::class.java) { PdfDocumentReader.renderPage(context, snapshot, 2, DisplayMode.Color) }
            } finally { snapshot.close() }
            assertThrows(CancellationException::class.java) { PdfDocumentReader.renderPage(context, snapshot, 0, DisplayMode.Color) }
            assertEquals(before, decoderFiles())
        } finally { source.delete() }
    }

    @Test fun canonicalZipParagraphsRemainIndependentAndBrokenDecoderInputsLeaveNoFiles() {
        val before = decoderFiles(); val bytes = pdf(2); val asset = ExchangeAsset(bytes, "application/pdf")
        val canonical = ExchangeDocument("stable-copy", "ZIP title", "Chapter", "EN", "local",
            listOf(ExchangeParagraph("canonical-id", "Canonical text stays exact.  ")),
            assets = listOf(ExchangeAssetReference(asset.path, "pdf")))
        val archive = LibraryExchangePackage("2026-10-05T00:00:00Z", listOf(canonical), listOf(asset))
        val snapshot = PdfDocumentReader.readInput(context, PdfDecoderInput.capture(bytes), "ZIP asset", "Decoder title", "PDF")
        try {
            val proof = PortableContentProofs.compute(PortableRepresentation.PDF, canonical, archive.assets, bytes)
            PortableContentProofs.validatePdfAnchor(proof, PortableProofAnchor.Pdf(asset.sha256, 1), snapshot.context)
            assertEquals(canonical, LibraryExchangeCodec.read(LibraryExchangeCodec.write(archive)).documents.single())
            assertNotEquals(canonical.paragraphs.first().paragraphId, snapshot.document.pages.first().segments.firstOrNull()?.id)
        } finally { snapshot.close() }
        assertThrows(Exception::class.java) {
            PdfDocumentReader.readInput(context, PdfDecoderInput.capture("%PDF-broken".toByteArray()), "broken", "Broken", "PDF")
        }
        assertEquals(before, decoderFiles())
    }
}
