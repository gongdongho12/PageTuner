package com.dongholab.pagetuner.document

object PlainTextDocumentParser {
    private const val TargetPageChars = 1_050
    private const val MaxSegmentChars = 520

    fun parse(
        title: String,
        rawText: String,
        format: DocumentFormat = DocumentFormat.TEXT,
        fallbackTitle: String = "Untitled",
    ): ReaderDocument {
        val cleaned = normalize(rawText, format)
        val documentId = DocumentIds.stableId(title, cleaned)
        val segments = splitIntoSegments(cleaned)
        val pages = paginate(documentId, segments)

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

    private fun paginate(documentId: String, sourceSegments: List<String>): List<ReaderPage> {
        val pages = mutableListOf<ReaderPage>()
        var current = mutableListOf<String>()
        var currentChars = 0

        fun flush() {
            if (current.isEmpty()) return
            val pageIndex = pages.size
            val pageSegments = current.mapIndexed { index, text ->
                TextSegment(
                    id = DocumentIds.segmentId(documentId, pageIndex, index, text),
                    pageIndex = pageIndex,
                    indexInPage = index,
                    text = text,
                )
            }
            pages += ReaderPage(index = pageIndex, segments = pageSegments)
            current = mutableListOf()
            currentChars = 0
        }

        for (segment in sourceSegments) {
            val nextChars = currentChars + segment.length
            if (current.isNotEmpty() && nextChars > TargetPageChars) {
                flush()
            }
            current += segment
            currentChars += segment.length
        }
        flush()

        return pages
    }
}
