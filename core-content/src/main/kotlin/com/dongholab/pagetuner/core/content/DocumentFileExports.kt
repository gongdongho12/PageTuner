package com.dongholab.pagetuner.core.content

enum class DocumentFileFormat { TXT, MARKDOWN }

/** UTF-8 text to write without a BOM. This readable copy is not a portable identity backup. */
data class DocumentTextFile(val filename: String, val mimeType: String, val text: String)

/** Deterministic, platform-independent readable document exports. See document-file-export-v1. */
object DocumentFileExports {
    const val MAX_INPUT_CODE_UNITS = 5_000_000
    const val MAX_OUTPUT_CODE_UNITS = 12_000_000
    const val MAX_PARAGRAPHS = 100_000
    const val MAX_FILENAME_STEM_CODE_UNITS = 120

    fun text(
        bookTitle: String,
        chapterTitle: String,
        paragraphs: List<String>,
        format: DocumentFileFormat,
    ): DocumentTextFile {
        require(paragraphs.size <= MAX_PARAGRAPHS) { "Too many paragraphs to export." }
        val inputLength = bookTitle.length.toLong() + chapterTitle.length +
            paragraphs.sumOf { it.length.toLong() }
        require(inputLength <= MAX_INPUT_CODE_UNITS) { "Document text exceeds the export input limit." }
        validateText(bookTitle)
        validateText(chapterTitle)
        paragraphs.forEach(::validateText)
        val titles = normalizeTitles(bookTitle, chapterTitle)
        val markdown = format == DocumentFileFormat.MARKDOWN
        val outputLength = renderedLength(titles.book, markdown) +
            (if (markdown) 2 else 0) +
            (titles.chapter?.let { renderedLength(it, markdown) + if (markdown) 5 else 1 } ?: 0L) +
            3 + paragraphs.sumOf { renderedLength(it, markdown) } +
            ((paragraphs.size - 1).coerceAtLeast(0) * 2L)
        require(outputLength <= MAX_OUTPUT_CODE_UNITS) { "Document text exceeds the export output limit." }
        val content = buildString(outputLength.toInt()) {
            if (markdown) append("# ")
            appendProse(titles.book, markdown)
            titles.chapter?.let { chapter ->
                append(if (markdown) "\n\n## " else "\n")
                appendProse(chapter, markdown)
            }
            append("\n\n")
            paragraphs.forEachIndexed { index, paragraph ->
                if (index > 0) append("\n\n")
                appendProse(paragraph, markdown)
            }
            append('\n')
        }
        return DocumentTextFile(
            filename = filename(titles, if (markdown) "md" else "txt"),
            mimeType = if (markdown) "text/markdown" else "text/plain",
            text = content,
        )
    }

    /** A safe suggestion for a readable text copy or verified original PDF; no path components. */
    fun safeFilename(bookTitle: String, chapterTitle: String, extension: String): String {
        require(extension in setOf("txt", "md", "pdf")) { "Unsupported document file extension." }
        require(bookTitle.length.toLong() + chapterTitle.length <= MAX_INPUT_CODE_UNITS) {
            "Document titles exceed the export input limit."
        }
        validateText(bookTitle)
        validateText(chapterTitle)
        return filename(normalizeTitles(bookTitle, chapterTitle), extension)
    }

    private data class Titles(val book: String, val chapter: String?)

    private fun normalizeTitles(bookTitle: String, chapterTitle: String): Titles {
        val book = bookTitle.trim(::isTitleEdgeWhitespace).ifEmpty { "Untitled" }
        val chapter = chapterTitle.trim(::isTitleEdgeWhitespace).takeIf { it.isNotEmpty() && it != book }
        return Titles(book, chapter)
    }

    private fun isTitleEdgeWhitespace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\r' || char == '\n'

    private fun validateText(value: String) {
        var index = 0
        while (index < value.length) {
            val code = value[index].code
            require(code !in 0..8 && code !in 11..12 && code !in 14..31 && code !in 127..159) {
                "Document text contains an unsupported control character."
            }
            when (code) {
                in 0xD800..0xDBFF -> {
                    require(index + 1 < value.length && value[index + 1].code in 0xDC00..0xDFFF) {
                        "Document text contains an unpaired surrogate."
                    }
                    index++
                }
                in 0xDC00..0xDFFF -> throw IllegalArgumentException("Document text contains an unpaired surrogate.")
            }
            index++
        }
    }

    private fun isAsciiPunctuation(char: Char): Boolean =
        char.code in 0x21..0x2F || char.code in 0x3A..0x40 ||
            char.code in 0x5B..0x60 || char.code in 0x7B..0x7E

    private fun renderedLength(value: String, markdown: Boolean): Long =
        value.length.toLong() + if (markdown) value.count(::isAsciiPunctuation) else 0

    private fun StringBuilder.appendProse(value: String, markdown: Boolean) {
        if (!markdown) {
            append(value)
        } else {
            value.forEach { char ->
                if (isAsciiPunctuation(char)) append('\\')
                append(char)
            }
        }
    }

    private fun filename(titles: Titles, extension: String): String {
        val raw = titles.book + (titles.chapter?.let { " - $it" } ?: "")
        var stem = boundedStem(buildString(raw.length) {
            raw.forEach { append(if (isForbiddenFilenameChar(it)) '_' else it) }
        }).ifEmpty { "document" }
        val deviceName = stem.substringBefore('.').trimEnd(' ')
            .map { if (it in 'a'..'z') it.uppercaseChar() else it }.joinToString("")
        if (deviceName in setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") ||
            Regex("(?:COM|LPT)[1-9¹²³]").matches(deviceName)
        ) {
            stem = boundedStem("_$stem")
        }
        return "$stem.$extension"
    }

    private fun boundedStem(value: String): String {
        val trimmed = value.trim('.', ' ')
        var end = minOf(trimmed.length, MAX_FILENAME_STEM_CODE_UNITS)
        if (end > 0 && trimmed[end - 1].code in 0xD800..0xDBFF) end--
        return trimmed.substring(0, end).trim('.', ' ')
    }

    private fun isForbiddenFilenameChar(char: Char): Boolean =
        char in "<>:\"/\\|?*" || char.code in 0..31 || char.code in 127..159 ||
            char == '\u061C' || char == '\u200E' || char == '\u200F' ||
            char.code in 0x202A..0x202E || char.code in 0x2066..0x2069
}
