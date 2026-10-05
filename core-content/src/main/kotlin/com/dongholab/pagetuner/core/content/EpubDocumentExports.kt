package com.dongholab.pagetuner.core.content

/** An ordered EPUB resource, to be encoded as UTF-8 without a BOM by the platform ZIP writer. */
data class EpubDocumentEntry(val path: String, val text: String)

/** A new readable publication, with no source identity, notes, or original EPUB resources. */
data class EpubDocumentFile(val filename: String, val mimeType: String, val entries: List<EpubDocumentEntry>)

/** Deterministic EPUB 3 resources. The platform must store the first mimetype entry uncompressed. */
object EpubDocumentExports {
    const val MIME_TYPE = "application/epub+zip"
    const val MAX_OUTPUT_CODE_UNITS = 32_000_000
    const val MAX_ENTRY_UTF8_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_UTF8_BYTES = 32 * 1024 * 1024
    private const val MODIFIED = "2000-01-01T00:00:00Z"
    private val languagePattern = Regex(
        "[A-Za-z]{2,8}(?:-[A-Za-z]{4})?(?:-(?:[A-Za-z]{2}|[0-9]{3}))?" +
            "(?:-(?:[A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*",
    )

    fun create(
        bookTitle: String,
        chapterTitle: String,
        language: String?,
        paragraphs: List<String>,
    ): EpubDocumentFile {
        val rawLanguage = language.orEmpty()
        DocumentFileExports.validateInput(bookTitle, chapterTitle, paragraphs, rawLanguage)
        validateXmlText(bookTitle)
        validateXmlText(chapterTitle)
        validateXmlText(rawLanguage)
        paragraphs.forEach(::validateXmlText)
        val titles = DocumentFileExports.normalizeTitles(bookTitle, chapterTitle)
        val displayTitle = titles.book + (titles.chapter?.let { " - $it" } ?: "")
        val normalizedLanguage = rawLanguage.trim(DocumentFileExports::isTitleEdgeWhitespace)
            .takeUnless { it.equals("auto", ignoreCase = true) || it.equals("auto-detect", ignoreCase = true) }
            ?.takeIf { it.length <= 128 && languagePattern.matches(it) }
            ?.map { if (it in 'A'..'Z') it.lowercaseChar() else it }?.joinToString("") ?: "und"
        val budget = PackageBudget()
        val mimetype = budget.entry("mimetype") { raw(MIME_TYPE) }
        val container = budget.entry("META-INF/container.xml") {
            raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            raw("<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n")
            raw("  <rootfiles>\n")
            raw("    <rootfile full-path=\"EPUB/package.opf\" media-type=\"application/oebps-package+xml\" />\n")
            raw("  </rootfiles>\n</container>\n")
        }
        val content = budget.entry("EPUB/content.xhtml") {
            raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            raw("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"$normalizedLanguage\" lang=\"$normalizedLanguage\">\n")
            raw("<head>\n  <title>"); xml(displayTitle); raw("</title>\n")
            raw("  <link rel=\"stylesheet\" type=\"text/css\" href=\"styles.css\" />\n</head>\n<body>\n")
            raw("  <section id=\"document\">\n    <h1>"); xml(titles.book); raw("</h1>\n")
            titles.chapter?.let { raw("    <h2>"); xml(it); raw("</h2>\n") }
            paragraphs.forEach { raw("    <p>"); xml(it); raw("</p>\n") }
            raw("  </section>\n</body>\n</html>\n")
        }
        val identifier = "urn:sha256:${StableContentHash.sha256(content.text)}"
        val opf = budget.entry("EPUB/package.opf") {
            raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            raw("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"publication-id\">\n")
            raw("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
            raw("    <dc:identifier id=\"publication-id\">$identifier</dc:identifier>\n")
            raw("    <dc:title>"); xml(displayTitle); raw("</dc:title>\n")
            raw("    <dc:language>$normalizedLanguage</dc:language>\n")
            raw("    <meta property=\"dcterms:modified\">$MODIFIED</meta>\n  </metadata>\n")
            raw("  <manifest>\n")
            raw("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\" />\n")
            raw("    <item id=\"content\" href=\"content.xhtml\" media-type=\"application/xhtml+xml\" />\n")
            raw("    <item id=\"styles\" href=\"styles.css\" media-type=\"text/css\" />\n  </manifest>\n")
            raw("  <spine>\n    <itemref idref=\"content\" />\n  </spine>\n</package>\n")
        }
        val navigation = budget.entry("EPUB/nav.xhtml") {
            raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            raw("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"$normalizedLanguage\" lang=\"$normalizedLanguage\">\n")
            raw("<head>\n  <title>"); xml(displayTitle); raw("</title>\n</head>\n<body>\n")
            raw("  <nav epub:type=\"toc\" id=\"toc\">\n    <ol>\n      <li><a href=\"content.xhtml#document\">")
            xml(titles.chapter ?: titles.book)
            raw("</a></li>\n    </ol>\n  </nav>\n</body>\n</html>\n")
        }
        val styles = budget.entry("EPUB/styles.css") {
            raw("body { font-family: serif; line-height: 1.5; }\n")
            raw("h1, h2, p { white-space: pre-wrap; overflow-wrap: anywhere; }\n")
            raw("p { margin: 1em 0; min-height: 1em; }\n")
        }
        return EpubDocumentFile(
            filename = DocumentFileExports.safeFilename(bookTitle, chapterTitle, "epub"),
            mimeType = MIME_TYPE,
            entries = listOf(mimetype, container, opf, navigation, content, styles),
        )
    }

    private fun validateXmlText(value: String) {
        require(value.none { it == '\uFFFE' || it == '\uFFFF' }) {
            "Document text contains a character unsupported by EPUB XML."
        }
    }

    private fun escaped(char: Char): String? = when (char) {
        '&' -> "&amp;"
        '<' -> "&lt;"
        '>' -> "&gt;"
        '"' -> "&quot;"
        '\'' -> "&apos;"
        '\r' -> "&#xD;"
        else -> null
    }

    // Inputs have already passed surrogate validation; each half of a valid pair counts as two bytes.
    private fun utf8Length(char: Char): Int = when {
        char.code < 0x80 -> 1
        char.code < 0x800 || char.code in 0xD800..0xDFFF -> 2
        else -> 3
    }

    /** Checks the aggregate bound before appending each part, including expansion and repeated titles. */
    private class PackageBudget {
        private var used = 0L
        private var usedBytes = 0L

        fun reserve(length: Long, bytes: Long) {
            require(used + length <= MAX_OUTPUT_CODE_UNITS) { "EPUB content exceeds the export output limit." }
            require(usedBytes + bytes <= MAX_TOTAL_UTF8_BYTES) { "EPUB content exceeds the export UTF-8 byte limit." }
            used += length
            usedBytes += bytes
        }

        fun entry(path: String, build: XmlWriter.() -> Unit): EpubDocumentEntry =
            EpubDocumentEntry(path, XmlWriter(this).apply(build).toString())
    }

    private class XmlWriter(private val budget: PackageBudget) {
        private val builder = StringBuilder()
        private var usedBytes = 0L

        private fun reserve(length: Long, bytes: Long) {
            require(usedBytes + bytes <= MAX_ENTRY_UTF8_BYTES) { "EPUB resource exceeds the export UTF-8 byte limit." }
            budget.reserve(length, bytes)
            usedBytes += bytes
        }

        fun raw(value: String) {
            reserve(value.length.toLong(), value.sumOf { utf8Length(it).toLong() })
            builder.append(value)
        }

        fun xml(value: String) {
            var length = 0L
            var bytes = 0L
            value.forEach { char ->
                val escapedLength = escaped(char)?.length
                length += escapedLength ?: 1
                bytes += escapedLength ?: utf8Length(char)
            }
            reserve(length, bytes)
            value.forEach { builder.append(escaped(it) ?: it) }
        }

        override fun toString(): String = builder.toString()
    }
}
