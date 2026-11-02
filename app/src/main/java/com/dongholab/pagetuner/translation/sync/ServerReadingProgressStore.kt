package com.dongholab.pagetuner.translation.sync

import android.util.AtomicFile
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.dongholab.pagetuner.core.backup.exchange.StrictExchangeJson
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
    /** Complete, strict alias observation is distinct from the legacy pending-only scan. */
    fun observeAliases(target: ServerReadingTarget): List<ReadingProgressJournalObservation> =
        throw UnsupportedOperationException("Strict reading progress observations are unavailable")
}

/** Small app-private records, atomically committed before an HTTP mutation is sent. */
class FileServerReadingProgressStore internal constructor(private val directory: File,
    private val io: ReadingProgressJournalIo) : ServerReadingProgressStore {
    constructor(directory: File) : this(directory, AtomicReadingProgressJournalIo)
    override fun read(document: ServerReadingDocument): DeviceReadingProgress {
        return read(document.target()).also { value ->
            listOfNotNull(value.remote?.anchor, value.pending?.anchor, value.queuedAnchor, value.conflict?.anchor).forEach(document::validate)
        }
    }
    override fun read(target: ServerReadingTarget): DeviceReadingProgress = ServerJournalFileLocks.withDirectory(directory) {
        val file = file(target)
        if (!io.exists(file)) DeviceReadingProgress() else decodeDeviceReadingProgress(readJson(file), target)
    }
    private fun readJson(file: File): JSONObject {
        val bytes = io.read(file)
        if (bytes.size > 65_536) throw IOException("Reading progress record is too large")
        // Replacement decoding could silently change the exact paragraph ID in a stored anchor.
        return JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
    }
    private fun readObservation(file: File): JSONObject {
        val bytes = io.readObservation(file)
        if (bytes.size > 65_536) throw IOException("Reading progress record is too large")
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        // Android org.json is lenient and can discard an earlier duplicated pending intent.
        // Validate raw tokens before creating any JSONObject, without normalizing numbers.
        StrictExchangeJson.validate(text, integerNumbersOnly = true, maxArrayEntries = 0, maxValues = 128)
        return JSONObject(text)
    }

    override fun write(document: ServerReadingDocument, value: DeviceReadingProgress) = write(document.target(), value)
    override fun write(target: ServerReadingTarget, value: DeviceReadingProgress) = ServerJournalFileLocks.withDirectory(directory) {
        check(directory.isDirectory || directory.mkdirs()) { "Reading progress directory is unavailable" }
        io.write(file(target), encodeDeviceReadingProgress(target, value).toString().toByteArray(Charsets.UTF_8))
    }

    override fun pending(accountKey: String): List<ServerReadingTarget> = ServerJournalFileLocks.withDirectory(directory) { directory.listFiles().orEmpty()
        .map { it.name.removeSuffix(".bak") }.filter { it.matches(Regex("[a-f0-9]{64}\\.json")) }.distinct().mapNotNull { name ->
            // A corrupt record is retained for explicit recovery; it must never be overwritten by a scan.
            runCatching {
                val json = readJson(File(directory, name))
                val pieces = (json.get("key") as String).split(':')
                require(pieces.size == 3 && pieces[0] == accountKey)
                val target = ServerReadingTarget(pieces[0], pieces[1], pieces[2])
                require(name == StableContentHash.sha256(target.key) + ".json")
                val value = decodeDeviceReadingProgress(json, target)
                target.takeIf { value.pending != null }
            }.getOrNull()
        }
    }

    override fun observeAliases(target: ServerReadingTarget): List<ReadingProgressJournalObservation> {
        // The query is canonical; historical spelling is retained only in observed results.
        require(target.accountKey.matches(Regex("[a-f0-9]{64}")))
        ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
        return ServerJournalFileLocks.withDirectory(directory) {
            val entries = io.inventory(directory)?.toList() ?: return@withDirectory emptyList()
            val parent = directory.canonicalFile
            val sources = linkedMapOf<String, File>()
            for (entry in entries) {
                val name = entry.name
                // Win32 lookups can see a noncanonical case/trailing-dot spelling of a journal.
                // Classify it conservatively, then reject rather than skipping or renaming it.
                val classified = name.trimEnd(' ', '.').lowercase(java.util.Locale.ROOT)
                if (!classified.endsWith(".json") && !classified.contains(".json.")) continue
                // An uncommitted staging file or unclassifiable journal cannot be silently skipped.
                require(!name.endsWith(".new") && name.matches(Regex("[a-f0-9]{64}\\.json(?:\\.bak)?"))) {
                    "Reading progress inventory contains an unsupported journal"
                }
                require(entry.isFile && entry.canonicalFile.parentFile == parent && entry.canonicalFile.name == name) {
                    "Reading progress inventory contains an invalid journal path"
                }
                val baseName = name.removeSuffix(".bak")
                // A listed backup is the authoritative committed source. Read it directly rather
                // than allowing AtomicFile recovery failure to fall through to a readable base.
                if (name.endsWith(".bak") || baseName !in sources) sources[baseName] = entry
            }
            val observations = mutableListOf<ReadingProgressJournalObservation>()
            // Validate the whole inventory before returning anything. Even an unrelated invalid row
            // makes this conservative observation unavailable; pending() retains its old behavior.
            for (name in sources.keys.sorted()) {
                val observation = decodeReadingProgressObservation(readObservation(sources.getValue(name)), name)
                if (observation.target.accountKey == target.accountKey && observation.target.kind == target.kind &&
                    observation.target.recordId.equals(target.recordId, ignoreCase = true)) observations += observation
            }
            observations.toList()
        }
    }
    private fun file(target: ServerReadingTarget) = File(directory, StableContentHash.sha256(target.key) + ".json")
}

/** Injected IO tests production exclusion on the JVM; Android AtomicFile itself needs instrumentation. */
internal interface ReadingProgressJournalIo {
    /** Null means verified absence, never an unreadable or indeterminate directory. */
    fun inventory(directory: File): List<File>? =
        throw UnsupportedOperationException("Strict reading progress inventory is unavailable")
    /** Read exactly the selected physical source, without recovery, rename or base/backup fallback. */
    fun readObservation(file: File): ByteArray =
        throw UnsupportedOperationException("Strict reading progress source reads are unavailable")
    fun exists(file: File): Boolean
    fun read(file: File): ByteArray
    fun write(file: File, bytes: ByteArray)
}
private object AtomicReadingProgressJournalIo : ReadingProgressJournalIo {
    override fun inventory(directory: File): List<File>? {
        val metadata = try {
            Os.stat(directory.path)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return null
            throw IOException("Reading progress inventory is unavailable", error)
        }
        check(OsConstants.S_ISDIR(metadata.st_mode)) { "Reading progress directory is unavailable" }
        return checkNotNull(directory.listFiles()) { "Reading progress inventory is unavailable" }.toList()
    }
    override fun exists(file: File) = file.exists() || File(file.path + ".bak").exists()
    override fun readObservation(file: File): ByteArray = file.inputStream().use { input ->
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
    override fun read(file: File): ByteArray = AtomicFile(file).openRead().use { input ->
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
    override fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
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
