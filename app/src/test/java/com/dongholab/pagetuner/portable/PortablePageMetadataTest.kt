package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.document.*
import com.dongholab.pagetuner.reader.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortablePageMetadataTest {
    @Test fun imageOnlyBookmarkAndNoteSurviveArchiveReexportAndReopen() {
        val image = ExchangeAsset(byteArrayOf(1, 2, 3), "image/png")
        val value = ExchangeDocument("images", "Illustrations", "Chapter", "und", "local", emptyList(),
            assets = listOf(ExchangeAssetReference(image.path, "image")))
        val mapping = PortableDocumentMapper.reader(value, listOf(image), "reader")
        val bookmark = ReaderBookmark("image-mark", 0, "Illustration", 1L)
        val note = ReaderAnnotation("image-note", ReaderAnnotationType.Note, 0, "Remember this drawing", 2L)
        val withContent = PortableDocumentMapper.mergeReader(value, mapping, 0, listOf(bookmark), listOf(note))
        val stored = PortablePageMetadata.merge(withContent, mapping, 0, listOf(bookmark), listOf(note), pdf = false)
        val bytes = LibraryExchangeCodec.write(LibraryExchangePackage("2026-09-15T00:00:00Z", listOf(stored), listOf(image)))
        val restored = LibraryExchangeCodec.read(bytes).documents.single()
        val state = PortablePageMetadata.read(restored, mapping, pdf = false)
        assertEquals(0, state.pageIndex)
        assertEquals(listOf(bookmark), state.bookmarks)
        assertEquals(listOf(note), state.annotations)
        assertTrue(restored.notes.isEmpty()) // No fabricated paragraph anchor for a binary-only page.
    }

    @Test fun movingOntoAnImageRetainsTextAnchorAndRestoresTheActualImagePage() {
        val image = ExchangeAsset(byteArrayOf(1), "image/png")
        val value = ExchangeDocument("mixed", "Mixed", "Chapter", "en", "local", listOf(ExchangeParagraph("p", "Original")),
            position = ExchangeAnchor("p", 3), assets = listOf(ExchangeAssetReference(image.path, "image")))
        val mapping = PortableDocumentMapper.reader(value, listOf(image), "reader")
        val moved = PortableDocumentMapper.mergeReader(value, mapping, 1, emptyList(), emptyList())
        assertEquals(value.position, moved.position)
        val stored = PortablePageMetadata.merge(moved, mapping, 1, emptyList(), emptyList(), pdf = false)
        assertEquals(1, PortablePageMetadata.read(stored, mapping, pdf = false).pageIndex)
        val back = PortablePageMetadata.merge(stored, mapping, 0, emptyList(), emptyList(), pdf = false)
        assertEquals(0, PortablePageMetadata.read(back, mapping, pdf = false).pageIndex)
    }

    @Test fun passivePdfExtensionsCannotBreakOpeningAndUnsupportedFieldsSurviveEditing() {
        val raw = """{"android":{"pageIndex":0,"futureSetting":{"mode":"next-version"},"pageBookmarks":[42,{"id":"future","pageIndex":999,"createdAtMillis":2},{"id":"known","pageIndex":0,"label":"Here","createdAtMillis":1,"futureColor":"black"}],"pageAnnotations":[{"id":"unknown-kind","pageIndex":0,"kind":"FutureNote","text":"Keep me","createdAtMillis":3}]}}"""
        val value = pdfDocument().copy(extensionsJson = raw)
        val mapping = pdfMapping()
        val state = PortablePageMetadata.read(value, mapping, pdf = true)
        assertEquals(listOf("known"), state.bookmarks.map { it.id })
        assertTrue(state.annotations.isEmpty())
        val moved = PortablePageMetadata.merge(value, mapping, 1, state.bookmarks, state.annotations, pdf = true)
        val json = JSONObject(requireNotNull(moved.extensionsJson))
        assertEquals(JSONObject(raw).getJSONObject("android").toString(), json.getJSONObject("android").toString())
        val owned = json.getJSONObject("pageturnerAndroidReader")
        assertEquals(3, owned.getJSONArray("pageBookmarks").length())
        assertEquals("black", owned.getJSONArray("pageBookmarks").getJSONObject(2).getString("futureColor"))
        assertEquals("FutureNote", owned.getJSONArray("pageAnnotations").getJSONObject(0).getString("kind"))
        val removed = PortablePageMetadata.merge(moved, mapping, 1, emptyList(), emptyList(), pdf = true)
        val after = JSONObject(requireNotNull(removed.extensionsJson)).getJSONObject("pageturnerAndroidReader")
        assertEquals(2, after.getJSONArray("pageBookmarks").length())
        assertEquals(42, after.getJSONArray("pageBookmarks").getInt(0))
        assertTrue(PortablePageMetadata.read(removed, mapping, pdf = true).bookmarks.isEmpty())
    }

    @Test fun conflictingExtensionNamesArePreservedRatherThanOverwritten() {
        val value = pdfDocument().copy(extensionsJson = """{"android":"opaque legacy metadata","pageturnerAndroidReader":{"format":"different-client","value":27}}""")
        val changed = PortablePageMetadata.merge(value, pdfMapping(), 1, emptyList(), emptyList(), pdf = true)
        val json = JSONObject(requireNotNull(changed.extensionsJson))
        assertEquals("opaque legacy metadata", json.getString("android"))
        assertEquals(27, json.getJSONObject("pageturnerAndroidReader").getInt("value"))
        assertEquals(1, PortablePageMetadata.read(changed, pdfMapping(), pdf = true).pageIndex)
    }

    @Test fun isoOffsetsFractionsAndSupplementaryFilenameLettersRemainValid() {
        val millis = portableMillis("2026-09-15T00:00:00Z")
        assertTrue(millis > 0)
        assertEquals(millis, portableMillis("2026-09-15T09:00:00+09:00"))
        assertEquals(millis + 123, portableMillis("2026-09-15T00:00:00.123456789Z"))
        assertEquals("2026-09-15T00:00:00.000Z", portableTimestamp(millis))
        val name = ("a".repeat(79) + "𝒜" + "tail").portableFilename()
        assertEquals(79, name.length)
        assertFalse(name.last().isHighSurrogate())
        assertEquals("a𝒜", "a𝒜".portableFilename())
    }

    private fun pdfDocument() = ExchangeDocument("pdf", "PDF", "PDF", "und", "local", emptyList())
    private fun pdfMapping() = PortableReaderMapping(ReaderDocument("pdf-reader", "PDF", DocumentFormat.PDF,
        listOf(ReaderPage(0, emptyList()), ReaderPage(1, emptyList()))), listOf(null, null))
}
