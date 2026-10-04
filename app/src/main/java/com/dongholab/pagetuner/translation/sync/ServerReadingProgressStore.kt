package com.dongholab.pagetuner.translation.sync

import android.util.AtomicFile
import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import java.io.IOException
import org.json.JSONObject

/** An uncertain write keeps its original mutation ID until acknowledged or explicitly resolved. */
data class DeviceReadingProgress(
    val remote: ServerReadingProgress? = null,
    val pending: ServerReadingMutation? = null,
    val queuedAnchor: ServerReadingAnchor? = null,
    val conflict: ServerReadingProgress? = null,
    val retryAfterUntil: Long? = null,
) {
    val localAnchor get() = queuedAnchor ?: pending?.anchor ?: remote?.anchor
}

data class ServerReadingTarget(val accountKey: String, val kind: String, val recordId: String) {
    val key get() = "$accountKey:$kind:$recordId"
}
internal fun ServerReadingDocument.target() = ServerReadingTarget(accountKey, source.entry.kind.progressKind(), source.entry.recordId)

interface ServerReadingProgressStore {
    fun read(document: ServerReadingDocument): DeviceReadingProgress
    fun write(document: ServerReadingDocument, value: DeviceReadingProgress)
    fun pending(accountKey: String): List<ServerReadingTarget>
    fun read(target: ServerReadingTarget): DeviceReadingProgress
    fun write(target: ServerReadingTarget, value: DeviceReadingProgress)
}

/** Small app-private records, atomically committed before an HTTP mutation is sent. */
class FileServerReadingProgressStore(private val directory: File) : ServerReadingProgressStore {
    override fun read(document: ServerReadingDocument): DeviceReadingProgress {
        return read(document.target()).also { value ->
            listOfNotNull(value.remote?.anchor, value.pending?.anchor, value.queuedAnchor, value.conflict?.anchor).forEach(document::validate)
        }
    }
    override fun read(target: ServerReadingTarget): DeviceReadingProgress {
        val file = file(target)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return DeviceReadingProgress()
        return decodeDeviceReadingProgress(readJson(file), target)
    }
    private fun readJson(file: AtomicFile): JSONObject {
        val bytes = file.openRead().use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (result.size() + count > 65_536) throw IOException("Reading progress record is too large")
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    override fun write(document: ServerReadingDocument, value: DeviceReadingProgress) = write(document.target(), value)
    override fun write(target: ServerReadingTarget, value: DeviceReadingProgress) {
        check(directory.isDirectory || directory.mkdirs()) { "Reading progress directory is unavailable" }
        val file = file(target)
        val stream = file.startWrite()
        try {
            stream.write(encodeDeviceReadingProgress(target, value).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    override fun pending(accountKey: String): List<ServerReadingTarget> = directory.listFiles().orEmpty()
        .map { it.name.removeSuffix(".bak") }.filter { it.matches(Regex("[a-f0-9]{64}\\.json")) }.distinct().mapNotNull { name ->
            // A corrupt record is retained for explicit recovery; it must never be overwritten by a scan.
            runCatching {
                val json = readJson(AtomicFile(File(directory, name)))
                val pieces = (json.get("key") as String).split(':')
                require(pieces.size == 3 && pieces[0] == accountKey)
                val target = ServerReadingTarget(pieces[0], pieces[1], pieces[2])
                require(name == StableContentHash.sha256(target.key) + ".json")
                val value = decodeDeviceReadingProgress(json, target)
                target.takeIf { value.pending != null }
            }.getOrNull()
        }
    private fun file(target: ServerReadingTarget) = AtomicFile(File(directory, StableContentHash.sha256(target.key) + ".json"))
}

internal fun encodeDeviceReadingProgress(document: ServerReadingDocument, value: DeviceReadingProgress) = encodeDeviceReadingProgress(document.target(), value)
internal fun encodeDeviceReadingProgress(target: ServerReadingTarget, value: DeviceReadingProgress): JSONObject = JSONObject()
    .put("format", 1).put("key", target.key)
    .put("remote", value.remote?.let(ServerReadingProgressJson::encode) ?: JSONObject.NULL)
    .put("pending", value.pending?.let(ServerReadingProgressJson::encode) ?: JSONObject.NULL)
    .put("queuedAnchor", value.queuedAnchor?.let(ServerReadingProgressJson::encode) ?: JSONObject.NULL)
    .put("conflict", value.conflict?.let(ServerReadingProgressJson::encode) ?: JSONObject.NULL)
    .put("retryAfterUntil", value.retryAfterUntil ?: JSONObject.NULL)

internal fun decodeDeviceReadingProgress(value: JSONObject, document: ServerReadingDocument): DeviceReadingProgress {
    return decodeDeviceReadingProgress(value, document.target()).also { result ->
        listOfNotNull(result.remote?.anchor, result.pending?.anchor, result.queuedAnchor, result.conflict?.anchor).forEach(document::validate)
    }
}
internal fun decodeDeviceReadingProgress(value: JSONObject, target: ServerReadingTarget): DeviceReadingProgress {
    require(target.accountKey.matches(Regex("[a-f0-9]{64}")))
    ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
    require(value.get("format") == 1 && value.get("key") == target.key)
    fun progress(key: String) = if (value.get(key) == JSONObject.NULL) null else ServerReadingProgressJson.decode(value.getJSONObject(key),
        target.kind, target.recordId)
    val result = DeviceReadingProgress(progress("remote"),
        if (value.get("pending") == JSONObject.NULL) null else ServerReadingProgressJson.mutation(value.getJSONObject("pending")),
        if (value.get("queuedAnchor") == JSONObject.NULL) null else ServerReadingProgressJson.anchor(value.getJSONObject("queuedAnchor")), progress("conflict"),
        if (!value.has("retryAfterUntil") || value.isNull("retryAfterUntil")) null else value.get("retryAfterUntil").let {
            require(it is Int || it is Long)
            (it as Number).toLong().also { deadline -> require(deadline >= 0) }
        })
    require(result.queuedAnchor == null || result.pending != null)
    require(result.conflict == null || result.pending != null)
    return result
}
