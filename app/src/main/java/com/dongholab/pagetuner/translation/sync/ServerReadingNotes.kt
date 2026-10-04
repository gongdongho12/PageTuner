package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.portable.portableTimestamp
import org.json.JSONObject
import java.util.UUID

enum class ServerReadingNoteKind { BOOKMARK, NOTE, HIGHLIGHT }
data class ServerReadingNoteRange(val start: ServerReadingAnchor, val end: ServerReadingAnchor)
data class ServerReadingNoteContent(
    val kind: ServerReadingNoteKind, val title: String, val text: String,
    val anchor: ServerReadingAnchor, val range: ServerReadingNoteRange?, val createdAt: String,
    val excerpt: String = "",
)
data class ServerReadingNote(val noteId: String, val version: Long, val changeRevision: Long,
    val deleted: Boolean, val note: ServerReadingNoteContent?, val updatedAt: String?)
data class ServerReadingNoteMutation(val expectedVersion: Long, val mutationId: String, val deleted: Boolean, val note: ServerReadingNoteContent?)
data class ServerReadingNotesPage(val kind: String, val recordId: String, val items: List<ServerReadingNote>,
    val nextAfterRevision: Long, val watermark: Long, val hasMore: Boolean)
class ServerReadingNoteConflict(val current: ServerReadingNote) : Exception("Reading note conflict")
class ServerReadingNotesRateLimited(val retryAfterSeconds: Long) : Exception("Reading notes rate limited")

internal object ServerReadingNotesJson {
    fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
    fun number(value: JSONObject, key: String): Long {
        val number = value.get(key)
        require(number is Int || number is Long)
        return (number as Number).toLong().also { require(it in 0..MaxReadingVersion) }
    }
    fun timestamp(value: String): String {
        val pattern = Regex("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.\\d{1,9})?Z")
        val match = requireNotNull(pattern.matchEntire(value))
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false
        }
        requireNotNull(format.parse(match.groupValues[1] + "Z"))
        val fraction = value.substringAfter('.', "").removeSuffix("Z").trimEnd('0')
        return match.groupValues[1] + (if (fraction.isEmpty()) "" else ".$fraction") + "Z"
    }
    fun content(value: JSONObject, response: Boolean): ServerReadingNoteContent {
        val kind = ServerReadingNoteKind.valueOf(value.get("kind") as String)
        val range = if (value.get("range") == JSONObject.NULL) null else value.getJSONObject("range").let {
            ServerReadingNoteRange(ServerReadingProgressJson.anchor(it.getJSONObject("start")), ServerReadingProgressJson.anchor(it.getJSONObject("end")))
        }
        return ServerReadingNoteContent(kind, value.get("title") as String, value.get("text") as String,
            ServerReadingProgressJson.anchor(value.getJSONObject("anchor")), range, timestamp(value.get("createdAt") as String),
            if (response) value.get("excerpt") as String else "").also(::validate)
    }
    fun validate(value: ServerReadingNoteContent) {
        require(value.title.isNotBlank() && value.title.length <= 200 && value.text.length <= 4000)
        require(value.kind != ServerReadingNoteKind.NOTE || value.text.isNotBlank())
        require((value.kind == ServerReadingNoteKind.HIGHLIGHT) == (value.range != null))
        require(value.excerpt.length <= if (value.kind == ServerReadingNoteKind.HIGHLIGHT) 4000 else 1000)
        ServerReadingProgressJson.validateAnchor(value.anchor)
        value.range?.let {
            ServerReadingProgressJson.validateAnchor(it.start); ServerReadingProgressJson.validateAnchor(it.end)
            require(it.start == value.anchor && (it.start.paragraphId != it.end.paragraphId || it.start.characterOffset < it.end.characterOffset))
        }
        timestamp(value.createdAt)
        listOf(value.title, value.text, value.excerpt, value.anchor.paragraphId, value.range?.end?.paragraphId.orEmpty()).forEach(::validUnicode)
    }
    private fun validUnicode(text: String) {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            require(!char.isLowSurrogate())
            if (char.isHighSurrogate()) require(index < text.length && text[index++].isLowSurrogate())
        }
    }
    fun encode(value: ServerReadingNoteContent, response: Boolean = false): JSONObject {
        validate(value)
        return JSONObject().put("kind", value.kind.name).put("title", value.title).put("text", value.text)
            .put("anchor", ServerReadingProgressJson.encode(value.anchor))
            .put("range", value.range?.let { JSONObject().put("start", ServerReadingProgressJson.encode(it.start)).put("end", ServerReadingProgressJson.encode(it.end)) } ?: JSONObject.NULL)
            .put("createdAt", timestamp(value.createdAt)).also { if (response) it.put("excerpt", value.excerpt) }
    }
    fun item(value: JSONObject, expectedId: String? = null): ServerReadingNote {
        val id = (value.get("noteId") as String).also(::uuid)
        require(expectedId == null || id == expectedId)
        val version = number(value, "version")
        val revision = number(value, "changeRevision")
        val deleted = value.get("deleted") as Boolean
        val content = if (value.get("note") == JSONObject.NULL) null else content(value.getJSONObject("note"), true)
        val updated = if (value.get("updatedAt") == JSONObject.NULL) null else timestamp(value.get("updatedAt") as String)
        require(deleted == (content == null))
        require(if (version == 0L) revision == 0L && deleted && updated == null else revision > 0 && updated != null)
        return ServerReadingNote(id, version, revision, deleted, content, updated)
    }
    fun encode(value: ServerReadingNote) = JSONObject().put("noteId", value.noteId).put("version", value.version)
        .put("changeRevision", value.changeRevision).put("deleted", value.deleted)
        .put("note", value.note?.let { encode(it, true) } ?: JSONObject.NULL).put("updatedAt", value.updatedAt ?: JSONObject.NULL)
    fun encode(value: ServerReadingNoteMutation, response: Boolean = false): JSONObject {
        require(value.expectedVersion in 0 until MaxReadingVersion && value.deleted == (value.note == null))
        uuid(value.mutationId)
        return JSONObject().put("expectedVersion", value.expectedVersion).put("mutationId", value.mutationId)
            .put("deleted", value.deleted).put("note", value.note?.let { encode(it, response) } ?: JSONObject.NULL)
    }
    fun mutation(value: JSONObject) = ServerReadingNoteMutation(number(value, "expectedVersion"), value.get("mutationId") as String,
        value.get("deleted") as Boolean, if (value.get("note") == JSONObject.NULL) null else content(value.getJSONObject("note"), response = true))
        .also { encode(it) }
    fun page(value: JSONObject, kind: String, recordId: String, after: Long, limit: Int, until: Long?): ServerReadingNotesPage {
        require(value.get("kind") == kind && value.get("recordId") == recordId)
        val watermark = number(value, "watermark")
        val next = number(value, "nextAfterRevision")
        val more = value.get("hasMore") as Boolean
        require(watermark >= after && next in after..watermark && (until == null || until == watermark))
        val raw = value.getJSONArray("items")
        require(raw.length() <= limit)
        val items = List(raw.length()) { item(raw.getJSONObject(it)) }
        require(items.all { it.version > 0 && it.changeRevision in (after + 1)..watermark })
        require(items.zipWithNext().all { (a, b) -> a.changeRevision < b.changeRevision })
        require(if (more) items.isNotEmpty() && next == items.last().changeRevision && next < watermark else next == watermark)
        require(items.isEmpty() || next >= items.last().changeRevision)
        return ServerReadingNotesPage(kind, recordId, items, next, watermark, more)
    }
}

/** Point notes retain exact terminal and empty paragraph anchors; no next-paragraph remapping. */
internal fun ServerReadingDocument.notePosition(page: Int, characterOffset: Int): Pair<Int, Int>? =
    anchor(page, characterOffset)?.let { page to characterOffset }

internal fun ServerReadingDocument.noteContent(kind: ServerReadingNoteKind, page: Int, title: String, text: String, characterOffset: Int = 0): ServerReadingNoteContent {
    val (sourcePage, sourceOffset) = requireNotNull(notePosition(page, characterOffset)) { "No source text at this position." }
    val anchor = requireNotNull(anchor(sourcePage, sourceOffset))
    val length = mapping.document.pages[sourcePage].segments.single().text.length
    if (kind == ServerReadingNoteKind.HIGHLIGHT) require(sourceOffset < length) { "No source text at this highlight position." }
    val range = if (kind == ServerReadingNoteKind.HIGHLIGHT) ServerReadingNoteRange(anchor, anchor.copy(characterOffset = anchor.characterOffset + length - sourceOffset)) else null
    val note = ServerReadingNoteContent(kind, title.trim(), text.trim(), anchor, range, portableTimestamp())
    return note.copy(excerpt = noteExcerpt(note)).also(ServerReadingNotesJson::validate)
}

internal fun ServerReadingDocument.noteExcerpt(note: ServerReadingNoteContent): String {
    ServerReadingNotesJson.validate(note)
    validate(note.anchor)
    val paragraphs = source.storedTranslation?.artifact?.paragraphs?.map { it.paragraphId to it.text }
        ?: requireNotNull(source.sourceContent).paragraphs.map { it.paragraphId to it.text }
    val index = paragraphs.indexOfFirst { it.first == note.anchor.paragraphId }
    require(index >= 0 && note.anchor.characterOffset <= paragraphs[index].second.length)
    val range = note.range
    if (range == null) return paragraphs[index].second.substring(note.anchor.characterOffset).takeUtf16(1000)
    validate(range.end)
    val endIndex = paragraphs.indexOfFirst { it.first == range.end.paragraphId }
    require(endIndex >= index && (endIndex > index || range.end.characterOffset > range.start.characterOffset))
    val selected = (index..endIndex).joinToString("\n\n") { current ->
        val text = paragraphs[current].second
        text.substring(if (current == index) range.start.characterOffset else 0, if (current == endIndex) range.end.characterOffset else text.length)
    }
    require(selected.isNotBlank() && selected.length <= 4000)
    return selected.takeUtf16(if (note.kind == ServerReadingNoteKind.HIGHLIGHT) 4000 else 1000)
}
internal fun String.takeUtf16(limit: Int): String {
    var end = minOf(length, limit)
    if (end in 1 until length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
    return substring(0, end)
}
