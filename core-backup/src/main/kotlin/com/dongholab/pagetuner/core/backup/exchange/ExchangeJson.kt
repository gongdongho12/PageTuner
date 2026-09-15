package com.dongholab.pagetuner.core.backup.exchange

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.GregorianCalendar
import java.util.Date
import java.util.TimeZone

internal object ExchangeJson {
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun parseObject(bytes: ByteArray): JSONObject {
        val text = utf8(bytes)
        return JSONObject(StrictExchangeJson.normalize(text))
    }

    fun encode(document: ExchangeDocument): ByteArray = JSONObject().apply {
        put("id", document.id); put("bookTitle", document.bookTitle); put("chapterTitle", document.chapterTitle)
        put("language", document.language); put("kind", document.kind)
        put("paragraphs", array(document.paragraphs) { JSONObject().put("paragraphId", it.paragraphId).put("text", it.text) })
        put("outline", array(document.outline) { JSONObject().put("title", it.title).put("paragraphId", it.paragraphId) })
        document.position?.let { put("position", anchor(it)) }
        put("notes", array(document.notes) { note -> JSONObject().apply {
            put("id", note.id); put("kind", note.kind); put("title", note.title); put("text", note.text)
            put("excerpt", note.excerpt); put("anchor", anchor(note.anchor)); put("createdAt", note.createdAt)
            note.range?.let { put("range", JSONObject().put("start", anchor(it.start)).put("end", anchor(it.end))) }
        } })
        put("organization", JSONObject().put("folder", document.organization.folder)
            .put("tags", JSONArray(document.organization.tags)).put("favorite", document.organization.favorite))
        put("glossary", array(document.glossary) { entry -> JSONObject().apply {
            put("source", entry.source); put("target", entry.target); put("caseSensitive", entry.caseSensitive)
            put("enabled", entry.enabled); entry.kind?.let { put("kind", it) }; entry.displayTerm?.let { put("displayTerm", it) }
        } })
        put("assets", array(document.assets) { ref -> JSONObject().apply {
            put("path", ref.path); put("role", ref.role); ref.paragraphId?.let { put("paragraphId", it) }
            ref.alt?.let { put("alt", it) }
        } })
        document.extensionsJson?.let { put("extensions", parseObject(it.toByteArray(Charsets.UTF_8))) }
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): ExchangeDocument {
        require(bytes.size <= LibraryExchangeLimits.DOCUMENT_BYTES) { "Document JSON exceeds the byte limit." }
        val json = parseObject(bytes)
        json.only("id", "bookTitle", "chapterTitle", "language", "kind", "paragraphs", "outline", "position", "notes", "organization", "glossary", "assets", "extensions")
        val document = ExchangeDocument(
            id = json.string("id"), bookTitle = json.string("bookTitle"), chapterTitle = json.string("chapterTitle"),
            language = json.string("language"), kind = json.string("kind"),
            paragraphs = json.list("paragraphs", required = true) { it.only("paragraphId", "text"); ExchangeParagraph(it.string("paragraphId"), it.string("text")) },
            outline = json.list("outline") { it.only("title", "paragraphId"); ExchangeOutlineEntry(it.string("title"), it.string("paragraphId")) },
            position = json.optionalObject("position")?.let(::readAnchor),
            notes = json.list("notes") { note ->
                note.only("id", "kind", "title", "text", "excerpt", "anchor", "range", "createdAt")
                ExchangeNote(note.string("id"), note.string("kind"), note.string("title"), note.string("text"),
                    note.string("excerpt"), readAnchor(note.getJSONObject("anchor")), note.string("createdAt"),
                    note.optionalObject("range")?.let { it.only("start", "end"); ExchangeRange(readAnchor(it.getJSONObject("start")), readAnchor(it.getJSONObject("end"))) })
            },
            organization = json.optionalObject("organization")?.let { organization ->
                organization.only("folder", "tags", "favorite")
                val tags = organization.getJSONArray("tags")
                ExchangeOrganization(organization.string("folder"), (0 until tags.length()).map { tags.get(it) as? String ?: error("Tag must be a string.") }, organization.boolean("favorite"))
            } ?: ExchangeOrganization(),
            glossary = json.list("glossary") { entry ->
                entry.only("source", "target", "kind", "displayTerm", "caseSensitive", "enabled")
                ExchangeGlossaryEntry(entry.string("source"), entry.string("target"), entry.optionalString("kind"),
                    entry.optionalString("displayTerm"), entry.boolean("caseSensitive", true), entry.boolean("enabled", true))
            },
            assets = json.list("assets") { ref ->
                ref.only("path", "role", "paragraphId", "alt")
                ExchangeAssetReference(ref.string("path"), ref.string("role"), ref.optionalString("paragraphId"), ref.optionalString("alt"))
            },
            extensionsJson = json.optionalObject("extensions")?.toString(),
        )
        validate(document)
        return document
    }

    fun validate(document: ExchangeDocument) {
        bounded(document.id, 500, true); bounded(document.bookTitle, 2000, true); bounded(document.chapterTitle, 2000, true)
        bounded(document.language, 35, true)
        require(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*").matches(document.language)) { "Invalid language tag." }
        require(document.kind in setOf("original", "translation", "local")) { "Unknown document kind." }
        require(document.paragraphs.size <= LibraryExchangeLimits.MAX_PARAGRAPHS) { "Too many paragraphs." }
        require(document.paragraphs.isNotEmpty() || document.assets.isNotEmpty()) { "Document has neither text nor assets." }
        var characters = 0L
        val paragraphMap = LinkedHashMap<String, ExchangeParagraph>()
        document.paragraphs.forEach {
            bounded(it.paragraphId, 500, true); unicode(it.text)
            characters += it.text.length
            require(paragraphMap.put(it.paragraphId, it) == null) { "Duplicate paragraph ID." }
        }
        require(characters <= LibraryExchangeLimits.MAX_CHARACTERS) { "Too much document text." }
        val paragraphOrder = paragraphMap.keys.withIndex().associate { it.value to it.index }
        fun checkAnchor(anchor: ExchangeAnchor) {
            val text = paragraphMap[anchor.paragraphId]?.text ?: error("Anchor references an unknown paragraph.")
            require(anchor.characterOffset in 0..text.length) { "Anchor offset is outside the paragraph." }
            require(anchor.characterOffset == 0 || anchor.characterOffset == text.length ||
                !(text[anchor.characterOffset - 1].isHighSurrogate() && text[anchor.characterOffset].isLowSurrogate())) { "Anchor splits a surrogate pair." }
        }
        document.position?.let(::checkAnchor)
        require(document.outline.size <= LibraryExchangeLimits.MAX_PARAGRAPHS) { "Too many outline entries." }
        document.outline.forEach { bounded(it.title, 2000, true); require(it.paragraphId in paragraphMap) { "Unknown outline paragraph." } }
        require(document.notes.size <= LibraryExchangeLimits.MAX_NOTES) { "Too many notes." }
        require(document.notes.map { it.id }.distinct().size == document.notes.size) { "Duplicate note ID." }
        document.notes.forEach { note ->
            bounded(note.id, 500, true); bounded(note.title, 2000); bounded(note.text, 10_000); bounded(note.excerpt, 10_000)
            require(note.kind in setOf("bookmark", "note", "highlight")) { "Unknown note kind." }
            timestamp(note.createdAt); checkAnchor(note.anchor)
            note.range?.let { range ->
                checkAnchor(range.start); checkAnchor(range.end)
                val start = paragraphOrder.getValue(range.start.paragraphId)
                val end = paragraphOrder.getValue(range.end.paragraphId)
                require(start < end || start == end && range.start.characterOffset <= range.end.characterOffset) { "Reversed highlight range." }
            }
        }
        bounded(document.organization.folder, 500)
        require(document.organization.tags.size <= 100 && document.organization.tags.distinct().size == document.organization.tags.size) { "Invalid tags." }
        document.organization.tags.forEach { bounded(it, 200, true) }
        require(document.glossary.size <= LibraryExchangeLimits.MAX_GLOSSARY) { "Too many glossary entries." }
        document.glossary.forEach {
            bounded(it.source, 2000, true); bounded(it.target, 2000, true)
            it.kind?.let { value -> bounded(value, 80, true) }; it.displayTerm?.let { value -> bounded(value, 2000) }
        }
        require(document.assets.size <= LibraryExchangeLimits.MAX_ENTRIES) { "Too many asset references." }
        document.assets.forEach {
            require(Regex("assets/[0-9a-f]{64}").matches(it.path)) { "Invalid asset path." }
            require(it.role in setOf("pdf", "image")) { "Unknown asset role." }
            it.paragraphId?.let { id -> require(id in paragraphMap) { "Unknown image paragraph." } }
            it.alt?.let { value -> bounded(value, 2000) }
        }
        document.extensionsJson?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= LibraryExchangeLimits.EXTENSIONS_BYTES) { "Extensions exceed the byte limit." }
            checkMetadata(parseObject(it.toByteArray(Charsets.UTF_8)), 0)
        }
    }

    private val forbiddenKeys = setOf("authorization", "password", "passwd", "token", "apikey", "accesstoken", "refreshtoken", "secret", "clientsecret", "credentials", "cookie", "setcookie", "basicauth")
    private fun checkMetadata(value: Any, depth: Int) {
        require(depth <= 16) { "Extensions are nested too deeply." }
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                unicode(key)
                val normalizedKey = StringBuilder()
                val lower = key.lowercase()
                var index = 0
                while (index < lower.length) {
                    val codePoint = Character.codePointAt(lower, index)
                    if (Character.isLetterOrDigit(codePoint)) normalizedKey.appendCodePoint(codePoint)
                    index += Character.charCount(codePoint)
                }
                require(normalizedKey.toString() !in forbiddenKeys) { "Credentials are not allowed in a library package." }
                checkMetadata(value.get(key), depth + 1)
            }
            is JSONArray -> (0 until value.length()).forEach { checkMetadata(value.get(it), depth + 1) }
            is String -> unicode(value)
            is Number -> {
                val number = value.toDouble()
                require(number.isFinite() && (number % 1.0 != 0.0 || kotlin.math.abs(number) <= 9_007_199_254_740_991.0)) { "Metadata number is outside the portable binary64 range." }
            }
        }
    }

    fun timestamp(value: String) {
        bounded(value, 64, true)
        val match = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2})").matchEntire(value)
            ?: error("Invalid ISO-8601 instant.")
        val parts = match.groupValues
        require(parts[1].toInt() in 1..9999) { "Invalid timestamp year." }
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            gregorianChange = Date(Long.MIN_VALUE)
            isLenient = false; clear()
            set(parts[1].toInt(), parts[2].toInt() - 1, parts[3].toInt(), parts[4].toInt(), parts[5].toInt(), parts[6].toInt())
            timeInMillis // Strictly validates dates and clock ranges, without requiring Android API 26.
        }
        if (parts[8] != "Z") {
            val hour = parts[8].substring(1, 3).toInt(); val minute = parts[8].substring(4, 6).toInt()
            require(hour in 0..18 && minute in 0..59 && (hour != 18 || minute == 0)) { "Invalid timestamp offset." }
        }
    }
    private fun bounded(value: String, limit: Int, required: Boolean = false) {
        require(value.length <= limit && (!required || value.isNotBlank())) { "Invalid string length." }; unicode(value)
    }
    private fun unicode(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            require(!char.isLowSurrogate()) { "Unpaired low surrogate." }
            if (char.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate()) { "Unpaired high surrogate." }
        }
    }
    private fun anchor(value: ExchangeAnchor) = JSONObject().put("paragraphId", value.paragraphId).put("characterOffset", value.characterOffset)
    private fun readAnchor(value: JSONObject): ExchangeAnchor { value.only("paragraphId", "characterOffset"); return ExchangeAnchor(value.string("paragraphId"), value.integer("characterOffset")) }
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject) = JSONArray().apply { values.forEach { put(encode(it)) } }
    private fun <T> JSONObject.list(key: String, required: Boolean = false, decode: (JSONObject) -> T): List<T> {
        if (!has(key) && !required) return emptyList()
        val array = getJSONArray(key)
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }
}

internal fun JSONObject.string(key: String): String = get(key) as? String ?: error("$key must be a string.")
internal fun JSONObject.integer(key: String): Int {
    val number = get(key) as? Number ?: error("$key must be an integer.")
    val value = number.toDouble()
    require(value.isFinite() && value >= 0 && value <= Int.MAX_VALUE && value == value.toInt().toDouble()) { "$key must be a non-negative integer." }
    return value.toInt()
}
internal fun JSONObject.boolean(key: String, default: Boolean? = null): Boolean =
    if (!has(key) && default != null) default else get(key) as? Boolean ?: error("$key must be a boolean.")
internal fun JSONObject.optionalString(key: String): String? = if (has(key)) string(key) else null
internal fun JSONObject.optionalObject(key: String): JSONObject? = if (has(key)) getJSONObject(key) else null
internal fun JSONObject.only(vararg allowed: String) { keys().forEach { require(it in allowed) { "Unknown field: $it." } } }
