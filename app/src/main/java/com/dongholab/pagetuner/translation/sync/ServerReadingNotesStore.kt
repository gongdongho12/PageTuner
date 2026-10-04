package com.dongholab.pagetuner.translation.sync

import android.util.AtomicFile
import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import org.json.JSONObject

data class ServerReadingNoteDesired(val deleted: Boolean, val note: ServerReadingNoteContent?)
data class DeviceReadingNotes(
    val afterRevision: Long = 0,
    val items: Map<String, ServerReadingNote> = emptyMap(),
    val pending: Map<String, ServerReadingNoteMutation> = emptyMap(),
    val queued: Map<String, ServerReadingNoteDesired> = emptyMap(),
    val conflicts: Map<String, ServerReadingNote> = emptyMap(),
    val retryAfterUntil: Long = 0,
) {
    fun desired(id: String): ServerReadingNoteDesired? = queued[id] ?: pending[id]?.let { ServerReadingNoteDesired(it.deleted, it.note) }
        ?: items[id]?.let { ServerReadingNoteDesired(it.deleted, it.note) }
    fun visible(): List<ServerReadingNote> = (items.keys + pending.keys).mapNotNull { id ->
        val value = desired(id) ?: return@mapNotNull null
        if (value.deleted) null else ServerReadingNote(id, items[id]?.version ?: 0, items[id]?.changeRevision ?: 0,
            false, value.note, items[id]?.updatedAt ?: value.note?.createdAt)
    }.sortedWith(compareBy<ServerReadingNote> { it.note!!.createdAt }.thenBy { it.noteId })
}

interface ServerReadingNotesStore {
    fun read(target: ServerReadingTarget): DeviceReadingNotes
    fun write(target: ServerReadingTarget, value: DeviceReadingNotes)
    fun targets(accountKey: String): List<ServerReadingTarget>
}

class FileServerReadingNotesStore(private val directory: File) : ServerReadingNotesStore {
    override fun read(target: ServerReadingTarget): DeviceReadingNotes {
        val file = file(target)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return DeviceReadingNotes()
        return decodeReadingNotesJournal(readJson(file), target)
    }
    override fun write(target: ServerReadingTarget, value: DeviceReadingNotes) {
        check(directory.isDirectory || directory.mkdirs())
        val bytes = encodeReadingNotesJournal(target, value).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MaxJournalBytes) { "Reading notes exceed device storage limit" }
        val file = file(target)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    override fun targets(accountKey: String): List<ServerReadingTarget> = directory.listFiles().orEmpty()
        .map { it.name.removeSuffix(".bak") }.filter { it.matches(Regex("[a-f0-9]{64}\\.json")) }.distinct().mapNotNull { name ->
            runCatching {
                val value = readJson(AtomicFile(File(directory, name)))
                val parts = (value.get("key") as String).split(':')
                require(parts.size == 3 && parts[0] == accountKey)
                val target = ServerReadingTarget(parts[0], parts[1], parts[2])
                require(name == StableContentHash.sha256(target.key) + ".json")
                decodeReadingNotesJournal(value, target)
                target
            }.getOrNull()
        }
    private fun file(target: ServerReadingTarget) = AtomicFile(File(directory, StableContentHash.sha256(target.key) + ".json"))
    private fun readJson(file: AtomicFile): JSONObject = file.openRead().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MaxJournalBytes)
            output.write(buffer, 0, count)
        }
        JSONObject(output.toByteArray().toString(Charsets.UTF_8))
    }
    private companion object { const val MaxJournalBytes = 64 * 1024 * 1024 }
}

internal fun encodeReadingNotesJournal(target: ServerReadingTarget, value: DeviceReadingNotes): JSONObject {
    fun <T> entries(items: Map<String, T>, encode: (T) -> JSONObject) = JSONObject().apply { items.forEach { (id, item) -> put(id, encode(item)) } }
    return JSONObject().put("format", 1).put("key", target.key).put("afterRevision", value.afterRevision).put("retryAfterUntil", value.retryAfterUntil)
        .put("items", entries(value.items, ServerReadingNotesJson::encode))
        .put("pending", entries(value.pending) { ServerReadingNotesJson.encode(it, true) })
        .put("queued", entries(value.queued) { JSONObject().put("deleted", it.deleted).put("note", it.note?.let { note -> ServerReadingNotesJson.encode(note, true) } ?: JSONObject.NULL) })
        .put("conflicts", entries(value.conflicts, ServerReadingNotesJson::encode))
}

internal fun decodeReadingNotesJournal(value: JSONObject, target: ServerReadingTarget): DeviceReadingNotes {
    require(value.get("format") == 1 && value.get("key") == target.key && target.accountKey.matches(Regex("[a-f0-9]{64}")))
    ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
    fun <T> entries(name: String, decode: (JSONObject, String) -> T): Map<String, T> {
        val values = value.getJSONObject(name)
        return values.keys().asSequence().associateWith { id -> ServerReadingNotesJson.uuid(id); decode(values.getJSONObject(id), id) }
    }
    val result = DeviceReadingNotes(ServerReadingNotesJson.number(value, "afterRevision"),
        entries("items") { item, id -> ServerReadingNotesJson.item(item, id).also { require(it.version > 0) } },
        entries("pending") { item, _ -> ServerReadingNotesJson.mutation(item) },
        entries("queued") { item, _ -> ServerReadingNoteDesired(item.get("deleted") as Boolean,
            if (item.get("note") == JSONObject.NULL) null else ServerReadingNotesJson.content(item.getJSONObject("note"), true))
            .also { require(it.deleted == (it.note == null)) } },
        entries("conflicts") { item, id -> ServerReadingNotesJson.item(item, id) }, ServerReadingNotesJson.number(value, "retryAfterUntil"))
    require(result.queued.keys.all { it in result.pending } && result.conflicts.keys.all { it in result.pending })
    return result
}
