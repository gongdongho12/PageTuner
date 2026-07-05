package com.dongholab.pagetuner.document

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

object EpubDocumentReader {
    private data class EpubChapter(
        val title: String?,
        val text: String,
        val imageCount: Int,
    )

    fun parse(
        title: String,
        bytes: ByteArray,
        fallbackTitle: String,
    ): ReaderDocument {
        val containerXml = readZipEntry(bytes, "META-INF/container.xml").decodeToString()
        val opfPath = parseContainerRootfile(containerXml)
        val opfXml = readZipEntry(bytes, opfPath).decodeToString()
        val chapters = parseSpineItems(opfXml, opfPath)
            .mapNotNull { path ->
                readZipEntryOrNull(bytes, path)?.decodeToString()?.let { xhtml ->
                    EpubChapter(
                        title = extractChapterTitle(xhtml) ?: path.substringAfterLast('/'),
                        text = extractXhtmlText(xhtml),
                        imageCount = countImages(xhtml),
                    )
                }
            }
            .filter { chapter -> chapter.text.isNotBlank() || chapter.imageCount > 0 }

        return PlainTextDocumentParser.parseChapters(
            title = title,
            chapters = chapters.map { chapter ->
                PlainTextDocumentParser.TextChapter(
                    title = chapter.title,
                    rawText = chapter.text,
                    imageCount = chapter.imageCount,
                )
            },
            format = DocumentFormat.EPUB,
            fallbackTitle = fallbackTitle,
        )
    }

    private fun parseContainerRootfile(containerXml: String): String {
        val document = parseXml(containerXml.byteInputStream())
        val rootfiles = document.getElementsByTagNameNS("*", "rootfile")
        if (rootfiles.length == 0) error("EPUB container does not contain a rootfile.")

        val path = rootfiles.item(0).attributes.getNamedItem("full-path")?.nodeValue
        return requireNotNull(path) { "EPUB rootfile path is missing." }
    }

    private fun parseSpineItems(
        opfXml: String,
        opfPath: String,
    ): List<String> {
        val document = parseXml(opfXml.byteInputStream())
        val manifest = mutableMapOf<String, String>()
        val items = document.getElementsByTagNameNS("*", "item")

        for (index in 0 until items.length) {
            val attributes = items.item(index).attributes
            val id = attributes.getNamedItem("id")?.nodeValue ?: continue
            val href = attributes.getNamedItem("href")?.nodeValue ?: continue
            val mediaType = attributes.getNamedItem("media-type")?.nodeValue.orEmpty()
            if (mediaType.contains("html") || href.endsWith(".xhtml") || href.endsWith(".html")) {
                manifest[id] = resolveRelativePath(opfPath.substringBeforeLast('/', ""), href)
            }
        }

        val spinePaths = mutableListOf<String>()
        val itemRefs = document.getElementsByTagNameNS("*", "itemref")
        for (index in 0 until itemRefs.length) {
            val idRef = itemRefs.item(index).attributes.getNamedItem("idref")?.nodeValue ?: continue
            manifest[idRef]?.let { spinePaths += it }
        }

        return spinePaths
    }

    private fun extractXhtmlText(xhtml: String): String {
        return runCatching {
            val builder = StringBuilder()
            val ignored = mutableListOf<String>()
            val handler = object : DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: org.xml.sax.Attributes?,
                ) {
                    val name = (localName?.takeIf { it.isNotBlank() } ?: qName).orEmpty().lowercase()
                    if (name in setOf("script", "style", "svg", "nav")) ignored += name
                    if (ignored.isNotEmpty()) return

                    when {
                        name in headingElements -> {
                            val level = name.removePrefix("h").toIntOrNull()?.coerceIn(1, 6) ?: 1
                            builder.append('\n').append("#".repeat(level)).append(' ')
                        }
                        name == "li" -> builder.append("\n- ")
                        name == "img" -> builder.append('\n')
                            .append(imagePlaceholder(attributes?.getValue("alt")))
                            .append('\n')
                        name == "br" -> builder.append('\n')
                        name in blockElements -> builder.append('\n')
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val name = (localName?.takeIf { it.isNotBlank() } ?: qName).orEmpty().lowercase()
                    if (ignored.lastOrNull() == name) ignored.removeAt(ignored.lastIndex)
                    if (ignored.isEmpty() && name in blockElements) builder.append('\n')
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (ignored.isEmpty()) builder.append(ch, start, length)
                }
            }

            SAXParserFactory.newInstance().newSAXParser().parse(
                InputSource(StringReader(xhtml)),
                handler,
            )
            normalizeWhitespace(builder.toString())
        }.getOrElse {
            normalizeWhitespace(
                xhtml
                    .replace(Regex("(?is)<(script|style|svg|nav).*?</\\1>"), " ")
                    .replace(Regex("(?is)<img\\b[^>]*alt=[\"']([^\"']*)[\"'][^>]*>"), "\n[Image: $1]\n")
                    .replace(Regex("(?is)<img\\b[^>]*>"), "\n[Image]\n")
                    .replace(Regex("(?is)<li\\b[^>]*>"), "\n- ")
                    .replace(Regex("(?is)<br\\s*/?>"), "\n")
                    .replace(Regex("(?is)</(p|div|h[1-6]|li|section|article)>"), "\n")
                    .replace(Regex("(?is)<[^>]+>"), " "),
            )
        }
    }

    private fun normalizeWhitespace(text: String): String {
        return text
            .lineSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .trim()
    }

    private fun extractChapterTitle(xhtml: String): String? {
        val titlePattern = Regex("(?is)<h[1-6][^>]*>(.*?)</h[1-6]>|<title[^>]*>(.*?)</title>")
        val match = titlePattern.find(xhtml) ?: return null
        return normalizeWhitespace(match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty())
            .takeIf { it.isNotBlank() }
    }

    private fun countImages(xhtml: String): Int {
        return Regex("(?is)<img\\b").findAll(xhtml).count()
    }

    private fun imagePlaceholder(alt: String?): String {
        val label = alt?.trim()?.takeIf { it.isNotBlank() }
        return if (label == null) "[Image]" else "[Image: $label]"
    }

    private fun parseXml(inputStream: InputStream): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().parse(inputStream)
    }

    private fun readZipEntry(bytes: ByteArray, path: String): ByteArray {
        return readZipEntryOrNull(bytes, path) ?: error("EPUB entry not found: $path")
    }

    private fun readZipEntryOrNull(bytes: ByteArray, path: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (!entry.isDirectory && entry.name == path) {
                    return zip.readBytes()
                }
            }
        }
    }

    private fun resolveRelativePath(basePath: String, href: String): String {
        val combined = if (basePath.isBlank()) href else "$basePath/$href"
        val stack = ArrayDeque<String>()
        combined.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private val blockElements = setOf(
        "p",
        "div",
        "br",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "li",
        "section",
        "article",
    )

    private val headingElements = setOf("h1", "h2", "h3", "h4", "h5", "h6")
}
