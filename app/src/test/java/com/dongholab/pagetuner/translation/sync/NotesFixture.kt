package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

internal object NotesFixture {
    const val recordId = "11111111-1111-4111-8111-111111111111"
    const val noteId = "22222222-2222-4222-8222-222222222222"
    const val otherId = "33333333-3333-4333-8333-333333333333"
    const val time = "2026-09-16T00:00:00Z"
    fun document(username: String = "reader", texts: List<String> = listOf("First 😀 paragraph", "Second paragraph", "Third paragraph")): ServerReadingDocument {
        val content = ChapterContent(ChapterIdentity(BookIdentity("source", "book"), "chapter"), "Chapter", "en",
            texts.mapIndexed { index, text -> ContentParagraph("p${index + 1}", index, text) })
        val entry = ServerLibraryEntry(recordId, "Book", "en", texts.size, content.sourceRevision, ServerLibraryKind.Originals)
        return ServerReadingDocument.create(serverReadingAccountKey("https://reader.example", username), ServerLibraryDocument(entry, texts, sourceContent = content))
    }
    fun content(title: String = "Saved", kind: ServerReadingNoteKind = ServerReadingNoteKind.NOTE) =
        ServerReadingNoteContent(kind, title, if (kind == ServerReadingNoteKind.NOTE) "Memo" else "", ServerReadingAnchor("p1", 0), null, time, "First 😀 paragraph")
    fun item(version: Long = 1, revision: Long = version, id: String = noteId, content: ServerReadingNoteContent? = content()) =
        ServerReadingNote(id, version, revision, content == null, content, if (version == 0L) null else time)
    fun page(items: List<ServerReadingNote> = emptyList(), after: Long = items.lastOrNull()?.changeRevision ?: 0,
        watermark: Long = after, more: Boolean = false, kind: String = "ORIGINAL") = JSONObject().put("kind", kind).put("recordId", recordId)
        .put("items", JSONArray(items.map(ServerReadingNotesJson::encode))).put("nextAfterRevision", after).put("watermark", watermark).put("hasMore", more)
    fun response(json: JSONObject) = TranslationStoreHttpResponse(200, body = json.toString())
    fun feed(request: TranslationStoreHttpRequest, items: List<ServerReadingNote> = emptyList()): TranslationStoreHttpResponse {
        val query = java.net.URI(request.url).query.split('&').associate { val pair = it.split('='); pair[0] to pair[1].toLong() }
        val after = query.getValue("afterRevision")
        val watermark = query["untilRevision"] ?: maxOf(after, items.maxOfOrNull { it.changeRevision } ?: 0)
        return response(page(items.filter { it.changeRevision in (after + 1)..watermark }, watermark, watermark))
    }
    fun conflict(item: ServerReadingNote) = TranslationStoreHttpResponse(409, body = JSONObject().put("code", "READING_NOTE_CONFLICT").put("current", ServerReadingNotesJson.encode(item)).toString())
    fun requestMutation(request: TranslationStoreHttpRequest): ServerReadingNoteMutation {
        val value = JSONObject(request.body!!)
        return ServerReadingNoteMutation(value.getLong("expectedVersion"), value.getString("mutationId"), value.getBoolean("deleted"),
            if (value.isNull("note")) null else ServerReadingNotesJson.content(value.getJSONObject("note"), false))
    }
    fun ack(request: TranslationStoreHttpRequest, revision: Long = 1): TranslationStoreHttpResponse {
        val mutation = requestMutation(request)
        val note = mutation.note?.let { it.copy(excerpt = document().noteExcerpt(it)) }
        return response(ServerReadingNotesJson.encode(item(mutation.expectedVersion + 1, revision, request.url.substringAfterLast('/'), note)))
    }
    fun connection(username: String = "reader", handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) =
        ServerReadingConnection(serverReadingAccountKey("https://reader.example", username),
            HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth(username, "password"), TranslationStoreHttpTransport { request ->
                if (request.url.endsWith("/csrf")) TranslationStoreHttpResponse(200, mapOf("Set-Cookie" to listOf("JSESSIONID=session; Path=/; HttpOnly")),
                    """{"headerName":"X-CSRF-TOKEN","token":"csrf"}""") else handler(request)
            }))
    class Store : ServerReadingNotesStore {
        val records = java.util.concurrent.ConcurrentHashMap<String, DeviceReadingNotes>()
        val changes = Channel<DeviceReadingNotes>(Channel.UNLIMITED)
        var fail = false
        override fun read(target: ServerReadingTarget) = records[target.key] ?: DeviceReadingNotes()
        override fun write(target: ServerReadingTarget, value: DeviceReadingNotes) {
            if (fail) error("Disk full")
            // Round trip actual persistent codec on every write, including pending request identity.
            records[target.key] = decodeReadingNotesJournal(JSONObject(encodeReadingNotesJournal(target, value).toString()), target)
            changes.trySend(records.getValue(target.key))
        }
        override fun targets(accountKey: String) = records.keys.filter { it.startsWith("$accountKey:") }.map {
            val parts = it.split(':'); ServerReadingTarget(parts[0], parts[1], parts[2])
        }
        suspend fun await(predicate: (DeviceReadingNotes) -> Boolean) {
            while (!records.values.any(predicate)) changes.receive()
        }
    }
}
