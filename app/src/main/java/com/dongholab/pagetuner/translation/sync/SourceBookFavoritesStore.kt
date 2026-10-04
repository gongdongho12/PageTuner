package com.dongholab.pagetuner.translation.sync

import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class DeviceSourceFavorites(val items: Map<String, SourceBookFavorite> = emptyMap(),
    val pending: Map<String, SourceFavoriteMutation> = emptyMap(), val queued: Map<String, SourceFavoriteDesired> = emptyMap(),
    val conflicts: Map<String, SourceBookFavorite> = emptyMap(), val afterRevision: Long = 0, val retryAfterUntil: Long = 0,
    val initialized: Boolean = false) {
    fun desired(key: String) = queued[key] ?: pending[key]?.desired ?: items[key]?.let { SourceFavoriteDesired(it.identity, it.deleted, it.book) }
}
interface SourceBookFavoritesStore {
    fun read(accountKey: String): DeviceSourceFavorites
    fun write(accountKey: String, value: DeviceSourceFavorites)
}

/** One atomic account journal commits feed cursor and items together. It never contains login credentials. */
class FileSourceBookFavoritesStore(private val directory: File) : SourceBookFavoritesStore {
    private fun file(accountKey: String): AtomicFile {
        require(accountKey.matches(Regex("[a-f0-9]{64}")))
        return AtomicFile(File(directory, "$accountKey.json"))
    }
    override fun read(accountKey: String): DeviceSourceFavorites {
        val file = file(accountKey)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return DeviceSourceFavorites()
        val bytes = file.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val size = input.read(buffer); if (size < 0) break; require(out.size() + size <= 16_777_216); out.write(buffer, 0, size) }
            out.toByteArray()
        }
        return decodeSourceFavoritesJournal(accountKey, JSONObject(bytes.toString(Charsets.UTF_8)))
    }
    override fun write(accountKey: String, value: DeviceSourceFavorites) {
        val bytes = encodeSourceFavoritesJournal(accountKey, value).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16_777_216); check(directory.isDirectory || directory.mkdirs())
        val file = file(accountKey); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

internal fun encodeSourceFavoritesJournal(accountKey: String, value: DeviceSourceFavorites) = JSONObject()
    .put("format", 1).put("initialized", value.initialized).put("accountKey", accountKey).put("afterRevision", value.afterRevision).put("retryAfterUntil", value.retryAfterUntil)
    .put("items", JSONArray(value.items.values.map { SourceFavoritesJson.encode(it) }))
    .put("pending", JSONArray(value.pending.values.map { SourceFavoritesJson.encode(it) }))
    .put("queued", JSONArray(value.queued.values.map { SourceFavoritesJson.encode(it) }))
    .put("conflicts", JSONArray(value.conflicts.values.map { SourceFavoritesJson.encode(it) }))
    .also { require(decodeSourceFavoritesJournal(accountKey, it) == value) }

internal fun decodeSourceFavoritesJournal(accountKey: String, json: JSONObject): DeviceSourceFavorites {
    require(accountKey.matches(Regex("[a-f0-9]{64}")) && json.get("accountKey") == accountKey && json.get("format") == 1)
    fun <T> entries(name: String, decode: (JSONObject) -> T, key: (T) -> String): Map<String, T> {
        val raw = json.getJSONArray(name); val items = List(raw.length()) { decode(raw.getJSONObject(it)) }
        return items.associateBy(key).also { require(it.size == items.size) }
    }
    val result = DeviceSourceFavorites(entries("items", SourceFavoritesJson::item) { it.identity.key },
        entries("pending", SourceFavoritesJson::mutation) { it.desired.identity.key },
        entries("queued", SourceFavoritesJson::desired) { it.identity.key }, entries("conflicts", SourceFavoritesJson::item) { it.identity.key },
        ServerReadingNotesJson.number(json, "afterRevision"), ServerReadingNotesJson.number(json, "retryAfterUntil"), json.get("initialized") as Boolean)
    require(result.queued.keys.all { it in result.pending } && result.conflicts.keys.all { it in result.pending })
    require(result.items.values.all { it.version > 0 })
    return result
}
