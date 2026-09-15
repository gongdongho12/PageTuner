package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.document.*
import com.dongholab.pagetuner.library.*
import com.dongholab.pagetuner.reader.*
import com.dongholab.pagetuner.translation.glossary.BookGlossary
import com.dongholab.pagetuner.translation.sync.ServerLibraryDocument
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

internal fun portableTimestamp(millis: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

internal fun portableMillis(value: String): Long = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)?.time ?: 0L
}.getOrDefault(0L)

data class PortablePageAnchor(val paragraphId: String, val startOffset: Int)
data class PortableReaderMapping(val document: ReaderDocument, val anchors: List<PortablePageAnchor?>) {
    fun pageFor(anchor: ExchangeAnchor): Int = anchors.indices.lastOrNull { index ->
        anchors[index]?.let { it.paragraphId == anchor.paragraphId && it.startOffset <= anchor.characterOffset } == true
    } ?: 0
    fun anchorFor(pageIndex: Int): ExchangeAnchor? = anchors.getOrNull(pageIndex)?.let { ExchangeAnchor(it.paragraphId, it.startOffset) }
}

/** Explicit book content projection: settings, credentials, server account IDs and API keys never enter a package. */
object PortableDocumentMapper {
    fun native(book: LocalBook, document: ReaderDocument, originalPdf: ByteArray? = null, glossary: BookGlossary? = null): LibraryExchangePackage {
        val paragraphs = document.pages.flatMap { it.segments }.filter { it.text.isNotEmpty() }
            .map { ExchangeParagraph(it.id, it.text) }
        require(paragraphs.map { it.paragraphId }.distinct().size == paragraphs.size) { "Duplicate native paragraph identities." }
        val pageAnchors = document.pages.map { page -> page.segments.firstOrNull { it.text.isNotEmpty() }?.let { ExchangeAnchor(it.id, 0) } }
        val assets = mutableListOf<ExchangeAsset>()
        val references = mutableListOf<ExchangeAssetReference>()
        if (document.format == DocumentFormat.PDF) {
            val asset = ExchangeAsset(requireNotNull(originalPdf) { "Original PDF bytes are unavailable." }, "application/pdf")
            assets += asset
            references += ExchangeAssetReference(asset.path, "pdf")
        }
        document.pages.forEach { page ->
            require(page.imageCount <= page.images.size || document.format == DocumentFormat.PDF) { "An EPUB image is unavailable; export stopped." }
            page.images.forEach { image ->
                require(image.bytes.isNotEmpty()) { "An EPUB image is unavailable; export stopped." }
                val asset = ExchangeAsset(image.bytes, image.mimeType)
                assets += asset
                references += ExchangeAssetReference(asset.path, "image", pageAnchors[page.index]?.paragraphId, image.altText)
            }
        }
        val nativeMetadata = JSONObject().put("format", document.format.name).put("documentId", document.id)
            .put("pageIndex", book.safeCurrentPageIndex)
            .put("pageParagraphIds", JSONArray(document.pages.map { page -> JSONArray(page.segments.map { it.id }) }))
            .put("pageBookmarks", JSONArray(book.bookmarks.filter { pageAnchors.getOrNull(it.pageIndex) == null }.map {
                JSONObject().put("id", it.id).put("pageIndex", it.pageIndex).put("label", it.label).put("createdAtMillis", it.createdAtMillis)
            }))
            .put("pageAnnotations", JSONArray(book.annotations.filter { pageAnchors.getOrNull(it.pageIndex) == null }.map {
                JSONObject().put("id", it.id).put("pageIndex", it.pageIndex).put("text", it.text).put("kind", it.type.name).put("createdAtMillis", it.createdAtMillis)
            }))
        val notes = book.bookmarks.mapNotNull { bookmark ->
            pageAnchors.getOrNull(bookmark.pageIndex)?.let { anchor ->
                ExchangeNote(bookmark.id, "bookmark", bookmark.label.orEmpty(), "", "", anchor, createdAt = portableTimestamp(bookmark.createdAtMillis))
            }
        } + book.annotations.mapNotNull { note ->
            pageAnchors.getOrNull(note.pageIndex)?.let { anchor ->
                ExchangeNote(note.id, if (note.type == LocalBookAnnotationType.Highlight) "highlight" else "note", "", note.text,
                    if (note.type == LocalBookAnnotationType.Highlight) note.text else "", anchor, createdAt = portableTimestamp(note.createdAtMillis))
            }
        }
        val exported = ExchangeDocument("android:${book.id}", book.title, book.currentChapterTitle ?: book.title,
            book.contentLanguage?.takeIf { it.isNotBlank() } ?: "und", if (book.contentIsTranslated) "translation" else "local", paragraphs,
            outline = document.tableOfContents.mapNotNull { item -> pageAnchors.getOrNull(item.pageIndex)?.let { ExchangeOutlineEntry(item.title, it.paragraphId) } },
            position = pageAnchors.getOrNull(book.safeCurrentPageIndex), notes = notes,
            organization = ExchangeOrganization(book.folder, book.tags),
            glossary = glossary?.entries.orEmpty().map { ExchangeGlossaryEntry(it.sourceTerm, it.translatedTerm, it.kind.name.lowercase(), it.displayTerm, it.caseSensitive, it.enabled) },
            assets = references, extensionsJson = JSONObject().put("android", nativeMetadata).toString())
        return LibraryExchangePackage(portableTimestamp(), listOf(exported), assets.distinctBy { it.path })
    }

    fun server(value: ServerLibraryDocument): LibraryExchangePackage {
        val paragraphs = value.storedTranslation?.artifact?.paragraphs?.map { ExchangeParagraph(it.paragraphId, it.text) }
            ?: value.sourceContent?.paragraphs?.map { ExchangeParagraph(it.paragraphId, it.text) }
            ?: error("Server paragraph identities are unavailable.")
        val metadata = JSONObject().put("recordId", value.entry.recordId).put("sourceRevision", value.entry.sourceRevision)
        value.storedTranslation?.artifact?.let { artifact ->
            metadata.put("providerId", artifact.providerId).put("sourceLanguage", artifact.sourceLanguage)
                .put("targetLanguage", artifact.targetLanguage).put("modelId", artifact.modelId)
                .put("promptRevision", artifact.promptRevision).put("glossaryRevision", artifact.glossaryRevision)
        }
        return LibraryExchangePackage(portableTimestamp(), listOf(ExchangeDocument("server:${value.entry.recordId}", value.entry.title,
            value.sourceContent?.title ?: value.entry.title, value.entry.language,
            if (value.storedTranslation != null) "translation" else "original", paragraphs,
            extensionsJson = JSONObject().put("server", metadata).toString())))
    }

    /** A page may split a paragraph for E-Ink fitting; archive identities and UTF-16 offsets stay unchanged. */
    fun reader(value: ExchangeDocument, assets: List<ExchangeAsset>, readerId: String): PortableReaderMapping {
        val pages = mutableListOf<ReaderPage>()
        val anchors = mutableListOf<PortablePageAnchor?>()
        val byPath = assets.associateBy { it.path }
        value.paragraphs.forEach { paragraph ->
            var offset = 0
            do {
                var end = (offset + 1_100).coerceAtMost(paragraph.text.length)
                if (end < paragraph.text.length && end > offset && paragraph.text[end - 1].isHighSurrogate() && paragraph.text[end].isLowSurrogate()) end--
                val index = pages.size
                val pageImages = if (offset == 0) value.assets.filter { it.role == "image" && it.paragraphId == paragraph.paragraphId }.map { ref ->
                    val asset = requireNotNull(byPath[ref.path]); ReaderPageImage(ref.path, ref.alt, asset.mimeType, asset.bytes)
                } else emptyList()
                pages += ReaderPage(index, listOf(TextSegment("${paragraph.paragraphId}:$offset", index, 0, paragraph.text.substring(offset, end))),
                    value.chapterTitle, pageImages.size, pageImages)
                anchors += PortablePageAnchor(paragraph.paragraphId, offset)
                offset = end
            } while (offset < paragraph.text.length)
        }
        value.assets.filter { it.role == "image" && it.paragraphId == null }.forEach { ref ->
            val asset = requireNotNull(byPath[ref.path])
            pages += ReaderPage(pages.size, emptyList(), value.chapterTitle, 1, listOf(ReaderPageImage(ref.path, ref.alt, asset.mimeType, asset.bytes)))
            anchors += null
        }
        if (pages.isEmpty()) { pages += ReaderPage(0, emptyList()); anchors += null }
        val outline = value.outline.map { item -> DocumentOutlineItem(item.title, anchors.indexOfFirst { it?.paragraphId == item.paragraphId }.coerceAtLeast(0)) }
        return PortableReaderMapping(ReaderDocument(readerId, value.bookTitle, if (value.assets.any { it.role == "image" }) DocumentFormat.EPUB else DocumentFormat.TEXT, pages, outline), anchors)
    }

    fun bookmarks(value: ExchangeDocument, mapping: PortableReaderMapping) = value.notes.filter { it.kind == "bookmark" }.map {
        ReaderBookmark(it.id, mapping.pageFor(it.anchor), it.title.ifBlank { null }, portableMillis(it.createdAt))
    }
    fun annotations(value: ExchangeDocument, mapping: PortableReaderMapping) = value.notes.filter { it.kind != "bookmark" }.mapNotNull {
        val text = it.text.ifBlank { it.excerpt }.ifBlank { it.title }
        if (text.isBlank()) null else ReaderAnnotation(it.id, if (it.kind == "highlight") ReaderAnnotationType.Highlight else ReaderAnnotationType.Note,
            mapping.pageFor(it.anchor), text, portableMillis(it.createdAt))
    }

    /** Existing imported ranges, timestamps and hidden metadata survive an unchanged Android reader projection. */
    fun mergeReader(value: ExchangeDocument, mapping: PortableReaderMapping, pageIndex: Int,
        bookmarks: List<ReaderBookmark>, annotations: List<ReaderAnnotation>): ExchangeDocument {
        val initialBookmarks = PortableDocumentMapper.bookmarks(value, mapping).associateBy { it.id }
        val initialAnnotations = PortableDocumentMapper.annotations(value, mapping).associateBy { it.id }
        val originals = value.notes.associateBy { it.id }
        val notes = bookmarks.mapNotNull { bookmark ->
            if (initialBookmarks[bookmark.id] == bookmark) originals[bookmark.id]
            else mapping.anchorFor(bookmark.pageIndex)?.let { ExchangeNote(bookmark.id, "bookmark", bookmark.label.orEmpty(), "", "", it, createdAt = portableTimestamp(bookmark.createdAtMillis)) }
        } + annotations.mapNotNull { note ->
            if (initialAnnotations[note.id] == note) originals[note.id]
            else mapping.anchorFor(note.pageIndex)?.let { ExchangeNote(note.id, if (note.type == ReaderAnnotationType.Highlight) "highlight" else "note", "", note.text,
                if (note.type == ReaderAnnotationType.Highlight) note.text else "", it, createdAt = portableTimestamp(note.createdAtMillis)) }
        } + value.notes.filter { it.kind != "bookmark" && it.id !in initialAnnotations }
        val position = value.position?.takeIf { mapping.pageFor(it) == pageIndex } ?: mapping.anchorFor(pageIndex)
        val byId = notes.associateBy { it.id }
        val orderedNotes = value.notes.mapNotNull { byId[it.id] } + notes.filter { it.id !in originals }
        return value.copy(position = position, notes = orderedNotes)
    }
}
