package com.dongholab.pagetuner.translation.sync

import android.util.AtomicFile
import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import org.json.JSONObject

data class DeviceBookGlossary(val remote: ServerBookGlossary? = null, val pending: BookGlossaryMutation? = null,
    val queued: BookGlossaryValue? = null, val conflict: ServerBookGlossary? = null, val retryAfterUntil: Long = 0,
    val selected: Boolean = false) {
    val local get() = queued ?: pending?.value ?: remote?.value
}
interface ServerBookGlossaryStore {
    fun read(target: BookGlossaryTarget): DeviceBookGlossary
    fun write(target: BookGlossaryTarget, value: DeviceBookGlossary)
    fun targets(accountKey: String): List<BookGlossaryTarget>
}

/** Atomic ordered snapshots and exact uncertain requests; no credentials or device dictionary migration. */
class FileServerBookGlossaryStore(private val directory: File) : ServerBookGlossaryStore {
    private fun accountDirectory(accountKey: String): File {
        require(accountKey.matches(Regex("[a-f0-9]{64}")))
        return File(directory, accountKey)
    }
    private fun file(target: BookGlossaryTarget) = AtomicFile(File(accountDirectory(target.accountKey), StableContentHash.sha256(target.key) + ".json"))
    override fun read(target: BookGlossaryTarget): DeviceBookGlossary {
        val file = file(target)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return DeviceBookGlossary()
        return decodeBookGlossaryJournal(readJson(file), target)
    }
    override fun write(target: BookGlossaryTarget, value: DeviceBookGlossary) {
        val bytes = encodeBookGlossaryJournal(target, value).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8_388_608)
        val folder = accountDirectory(target.accountKey); check(folder.isDirectory || folder.mkdirs())
        val file = file(target); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    override fun targets(accountKey: String): List<BookGlossaryTarget> = accountDirectory(accountKey).listFiles().orEmpty()
        .map { it.name.removeSuffix(".bak") }.filter { it.matches(Regex("[a-f0-9]{64}\\.json")) }.distinct().map { name ->
            val json = readJson(AtomicFile(File(accountDirectory(accountKey), name)))
            val target = BookGlossaryTarget(accountKey, ServerBookGlossaryJson.identity(json.getJSONObject("identity")))
            require(name == StableContentHash.sha256(target.key) + ".json")
            decodeBookGlossaryJournal(json, target); target
        }
    private fun readJson(file: AtomicFile): JSONObject = file.openRead().use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val size = input.read(buffer); if (size < 0) break; require(out.size() + size <= 8_388_608); out.write(buffer, 0, size) }
        JSONObject(out.toByteArray().toString(Charsets.UTF_8))
    }
}

internal fun encodeBookGlossaryJournal(target: BookGlossaryTarget, value: DeviceBookGlossary): JSONObject = JSONObject()
    .put("format", 1).put("accountKey", target.accountKey).put("identity", ServerBookGlossaryJson.encode(target.identity))
    .put("remote", value.remote?.let { ServerBookGlossaryJson.encode(it) } ?: JSONObject.NULL)
    .put("pending", value.pending?.let { ServerBookGlossaryJson.encode(target.identity, it) } ?: JSONObject.NULL)
    .put("queued", value.queued?.let { ServerBookGlossaryJson.encode(it) } ?: JSONObject.NULL)
    .put("conflict", value.conflict?.let { ServerBookGlossaryJson.encode(it) } ?: JSONObject.NULL)
    .put("retryAfterUntil", value.retryAfterUntil).put("selected", value.selected).also { require(decodeBookGlossaryJournal(it, target) == value) }

internal fun decodeBookGlossaryJournal(json: JSONObject, target: BookGlossaryTarget): DeviceBookGlossary {
    require(target.accountKey.matches(Regex("[a-f0-9]{64}")) && json.get("format") == 1 && json.get("accountKey") == target.accountKey)
    require(ServerBookGlossaryJson.identity(json.getJSONObject("identity")) == target.identity)
    fun view(name: String) = if (json.get(name) == JSONObject.NULL) null else ServerBookGlossaryJson.view(json.getJSONObject(name), target.identity)
    val value = DeviceBookGlossary(view("remote"),
        if (json.get("pending") == JSONObject.NULL) null else ServerBookGlossaryJson.mutation(json.getJSONObject("pending"), target.identity),
        if (json.get("queued") == JSONObject.NULL) null else ServerBookGlossaryJson.value(json.getJSONObject("queued")),
        view("conflict"), ServerReadingNotesJson.number(json, "retryAfterUntil"), json.get("selected") as Boolean)
    require(value.pending != null || value.queued == null && value.conflict == null)
    value.queued?.let { ServerBookGlossaryJson.validateRequest(target.identity, it) }
    return value
}
