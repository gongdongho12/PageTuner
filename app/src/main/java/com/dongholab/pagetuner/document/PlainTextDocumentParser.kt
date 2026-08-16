package com.dongholab.pagetuner.document

object PlainTextDocumentParser {
    // The reader auto-fits within a bounded viewport; this target avoids large blank lower halves.
    private const val TargetPageChars = 1_100
    // Translation cache IDs keep the original 620-character pagination identity during reflow.
    private const val LegacyPageChars = 620
    private const val MaxSegmentChars = 400

    private data class SegmentDraft(
        val id: String,
        val text: String,
    )

    data class TextChapter(
        val title: String?,
        val rawText: String,
        val imageCount: Int = 0,
        val images: List<ReaderPageImage> = emptyList(),
    )

    fun parse(
        title: String,
        rawText: String,
        format: DocumentFormat = DocumentFormat.TEXT,
        fallbackTitle: String = "Untitled",
    ): ReaderDocument {
        return parseChapters(
            title = title,
            chapters = listOf(TextChapter(title = null, rawText = rawText)),
            format = format,
            fallbackTitle = fallbackTitle,
        )
    }

    fun parseChapters(
        title: String,
        chapters: List<TextChapter>,
        format: DocumentFormat = DocumentFormat.TEXT,
        fallbackTitle: String = "Untitled",
    ): ReaderDocument {
        val cleanedChapters = chapters.map { chapter ->
            chapter.copy(rawText = normalize(chapter.rawText, format))
        }
        val documentBody = cleanedChapters.joinToString(separator = "\n\n") { chapter ->
            listOfNotNull(chapter.title, chapter.rawText).joinToString("\n")
        }
        val documentId = DocumentIds.stableId(title, documentBody)
        val pages = paginateChapters(documentId, cleanedChapters)

        return ReaderDocument(
            id = documentId,
            title = title.ifBlank { fallbackTitle },
            format = format,
            pages = pages.ifEmpty {
                listOf(
                    ReaderPage(
                        index = 0,
                        segments = listOf(
                            TextSegment(
                                id = DocumentIds.segmentId(documentId, 0, 0, ""),
                                pageIndex = 0,
                                indexInPage = 0,
                                text = "",
                            ),
                        ),
                    ),
                )
            },
            tableOfContents = buildTableOfContents(pages),
        )
    }

    private fun normalize(rawText: String, format: DocumentFormat): String {
        val unixText = rawText.replace("\r\n", "\n").replace('\r', '\n')
        val stripped = if (format == DocumentFormat.MARKDOWN) {
            unixText
                .replace(Regex("(?m)^#{1,6}\\s+"), "")
                .replace(Regex("(?m)^>\\s?"), "")
                .replace(Regex("`{1,3}"), "")
                .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
        } else {
            unixText
        }

        return stripped
            .lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
            .trim()
    }

    private fun splitIntoSegments(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        return text
            .split(Regex("\\n\\s*\\n"))
            .flatMap { splitLongParagraph(it.trim()) }
            .filter { it.isNotBlank() }
    }

    private fun splitLongParagraph(paragraph: String): List<String> {
        if (paragraph.length <= MaxSegmentChars) return listOf(paragraph)

        val sentences = paragraph.split(Regex("(?<=[.!?。！？])\\s+"))
        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > MaxSegmentChars) {
                chunks += current.toString().trim()
                current = StringBuilder()
            }
            if (sentence.length > MaxSegmentChars) {
                sentence.chunked(MaxSegmentChars).forEach { chunks += it.trim() }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }

        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks
    }

    private fun paginateChapters(documentId: String, chapters: List<TextChapter>): List<ReaderPage> {
        val pages = mutableListOf<ReaderPage>()
        var current = mutableListOf<SegmentDraft>()
        var currentChars = 0
        var currentChapterTitle: String? = null
        var currentChapterImageCount = 0
        var currentChapterImages = emptyList<ReaderPageImage>()
        var isFirstPageInChapter = true
        var legacyPageIndex = 0
        var legacyIndexInPage = 0
        var legacyPageChars = 0
        var legacyHasContent = false

        fun flush() {
            if (current.isEmpty()) return
            val pageIndex = pages.size
            val pageSegments = current.mapIndexed { index, draft ->
                TextSegment(
                    id = draft.id,
                    pageIndex = pageIndex,
                    indexInPage = index,
                    text = draft.text,
                )
            }
            pages += ReaderPage(
                index = pageIndex,
                segments = pageSegments,
                chapterTitle = currentChapterTitle,
                imageCount = if (isFirstPageInChapter) currentChapterImageCount else 0,
                images = if (isFirstPageInChapter) currentChapterImages else emptyList(),
            )
            current = mutableListOf()
            currentChars = 0
            isFirstPageInChapter = false
        }

        chapters.forEach { chapter ->
            flush()
            if (legacyHasContent) {
                legacyPageIndex += 1
                legacyIndexInPage = 0
                legacyPageChars = 0
                legacyHasContent = false
            }
            currentChapterTitle = chapter.title?.takeIf { it.isNotBlank() }
            currentChapterImageCount = chapter.imageCount
            currentChapterImages = chapter.images
            isFirstPageInChapter = true

            val sourceSegments = splitIntoSegments(chapter.rawText)
            for (segment in sourceSegments) {
                val legacySeparatorChars = if (legacyHasContent) 2 else 0
                if (legacyHasContent && legacyPageChars + legacySeparatorChars + segment.length > LegacyPageChars) {
                    legacyPageIndex += 1
                    legacyIndexInPage = 0
                    legacyPageChars = 0
                    legacyHasContent = false
                }
                val stableCacheId = DocumentIds.segmentId(
                    documentId,
                    legacyPageIndex,
                    legacyIndexInPage,
                    segment,
                )
                legacyPageChars += (if (legacyHasContent) 2 else 0) + segment.length
                legacyIndexInPage += 1
                legacyHasContent = true

                val separatorChars = if (current.isEmpty()) 0 else 2
                val nextChars = currentChars + separatorChars + segment.length
                if (current.isNotEmpty() && nextChars > TargetPageChars) {
                    flush()
                }
                current += SegmentDraft(id = stableCacheId, text = segment)
                currentChars += (if (current.size == 1) 0 else 2) + segment.length
            }
        }
        flush()

        return pages
    }

    private fun buildTableOfContents(pages: List<ReaderPage>): List<DocumentOutlineItem> {
        val items = mutableListOf<DocumentOutlineItem>()
        var lastTitle: String? = null
        pages.forEach { page ->
            val title = page.chapterTitle?.takeIf { it.isNotBlank() }
            if (title != null && title != lastTitle) {
                items += DocumentOutlineItem(title = title, pageIndex = page.index)
                lastTitle = title
            }
        }
        return items
    }
}
