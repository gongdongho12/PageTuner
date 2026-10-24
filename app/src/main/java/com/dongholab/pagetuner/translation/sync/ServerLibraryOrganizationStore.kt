package com.dongholab.pagetuner.translation.sync

import android.util.AtomicFile
import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import org.json.JSONObject

data class DeviceLibraryOrganization(val remote: ServerLibraryOrganization? = null,
    val pending: LibraryOrganizationMutation? = null, val queued: LibraryOrganization? = null,
    val conflict: ServerLibraryOrganization? = null, val retryAfterUntil: Long = 0) {
    val local get() = queued ?: pending?.organization ?: remote?.organization
}
interface ServerLibraryOrganizationStore {
    fun read(target: ServerReadingTarget): DeviceLibraryOrganization
    fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization)
    fun targets(accountKey: String): List<ServerReadingTarget>
    /** Missing is distinct from an existing empty journal. Unsupported stores must fail closed. */
    fun readNullable(target: ServerReadingTarget): DeviceLibraryOrganization? =
        throw UnsupportedOperationException("Nullable organization journals are unavailable")
    /** The guard is synchronous and must not mutate this journal. True means local commit, not server ACK. */
    fun compareAndWrite(target: ServerReadingTarget, expected: DeviceLibraryOrganization?,
        value: DeviceLibraryOrganization, guard: () -> Unit): Boolean =
        throw UnsupportedOperationException("Atomic organization adoption is unavailable")
}
/** Account/origin and exact server identity are hashed; credentials and local library entries stay separate. */
class FileServerLibraryOrganizationStore internal constructor(private val directory: File,
    private val io: LibraryOrganizationJournalIo) : ServerLibraryOrganizationStore {
    constructor(directory: File) : this(directory, AtomicLibraryOrganizationJournalIo)

    override fun read(target: ServerReadingTarget): DeviceLibraryOrganization = readNullable(target) ?: DeviceLibraryOrganization()

    override fun readNullable(target: ServerReadingTarget): DeviceLibraryOrganization? = ServerJournalFileLocks.withDirectory(directory) {
        readLocked(target)
    }

    override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization) = ServerJournalFileLocks.withDirectory(directory) {
        writeLocked(target, encoded(target, value), {})
    }

    override fun compareAndWrite(target: ServerReadingTarget, expected: DeviceLibraryOrganization?,
        value: DeviceLibraryOrganization, guard: () -> Unit): Boolean {
        // Capture caller-owned lists before waiting for a directory lock or invoking external guards.
        val capturedExpected = expected?.let { decodeLibraryOrganizationJournal(JSONObject(encoded(target, it).toString(Charsets.UTF_8)), target) }
        val bytes = encoded(target, value)
        return ServerJournalFileLocks.withDirectory(directory) {
            guard()
            if (readLocked(target) != capturedExpected) return@withDirectory false
            guard()
            writeLocked(target, bytes, guard)
            // No post-commit guard: logout/cancel after this point cannot roll back a durable intent.
            true
        }
    }

    override fun targets(accountKey: String): List<ServerReadingTarget> = ServerJournalFileLocks.withDirectory(directory) { directory.listFiles().orEmpty()
        .map { it.name.removeSuffix(".bak") }.filter { it.matches(Regex("[a-f0-9]{64}\\.json")) }.distinct().mapNotNull { name ->
            val value = readJson(File(directory, name))
            val parts = (value.get("key") as String).split(':')
            require(parts.size == 3)
            if (parts[0] != accountKey) null else {
                val target = ServerReadingTarget(parts[0], parts[1], parts[2])
                require(name == StableContentHash.sha256(target.key) + ".json")
                decodeLibraryOrganizationJournal(value, target); target
            }
        }
    }

    private fun readLocked(target: ServerReadingTarget): DeviceLibraryOrganization? {
        // Validate even when the corresponding file does not exist.
        require(target.accountKey.matches(Regex("[a-f0-9]{64}")))
        ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
        val file = file(target)
        return if (io.exists(file)) decodeLibraryOrganizationJournal(readJson(file), target) else null
    }
    private fun encoded(target: ServerReadingTarget, value: DeviceLibraryOrganization): ByteArray =
        encodeLibraryOrganizationJournal(target, value).toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 65_536) }
    private fun writeLocked(target: ServerReadingTarget, bytes: ByteArray, guard: () -> Unit) {
        check(directory.isDirectory || directory.mkdirs())
        io.write(file(target), bytes, guard)
    }
    private fun file(target: ServerReadingTarget) = File(directory, StableContentHash.sha256(target.key) + ".json")
    private fun readJson(file: File): JSONObject = JSONObject(io.read(file).also { require(it.size <= 65_536) }.toString(Charsets.UTF_8))
}

/** File operations are injectable for JVM transaction tests; Android AtomicFile coverage remains instrumentation. */
internal interface LibraryOrganizationJournalIo {
    fun exists(file: File): Boolean
    fun read(file: File): ByteArray
    fun write(file: File, bytes: ByteArray, guard: () -> Unit)
}
private object AtomicLibraryOrganizationJournalIo : LibraryOrganizationJournalIo {
    override fun exists(file: File) = file.exists() || File(file.path + ".bak").exists()
    override fun read(file: File): ByteArray = AtomicFile(file).openRead().use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= 65_536); output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    override fun write(file: File, bytes: ByteArray, guard: () -> Unit) {
        val atomic = AtomicFile(file)
        guard()
        val stream = atomic.startWrite()
        try {
            stream.write(bytes); stream.flush(); stream.fd.sync()
            // Last caller-controlled abortable boundary, after staging/sync and before atomic commit.
            guard()
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
internal fun encodeLibraryOrganizationJournal(target: ServerReadingTarget, value: DeviceLibraryOrganization): JSONObject = JSONObject()
    .put("format", 1).put("key", target.key).put("remote", value.remote?.let(ServerLibraryOrganizationJson::encode) ?: JSONObject.NULL)
    .put("pending", value.pending?.let(ServerLibraryOrganizationJson::encode) ?: JSONObject.NULL)
    .put("queued", value.queued?.let(ServerLibraryOrganizationJson::encode) ?: JSONObject.NULL)
    .put("conflict", value.conflict?.let(ServerLibraryOrganizationJson::encode) ?: JSONObject.NULL)
    .put("retryAfterUntil", value.retryAfterUntil).also { decodeLibraryOrganizationJournal(it, target) }

internal fun decodeLibraryOrganizationJournal(value: JSONObject, target: ServerReadingTarget): DeviceLibraryOrganization {
    require(target.accountKey.matches(Regex("[a-f0-9]{64}")) && value.get("format") == 1 && value.get("key") == target.key)
    ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
    fun view(name: String) = if (value.get(name) == JSONObject.NULL) null else ServerLibraryOrganizationJson.view(value.getJSONObject(name), target.kind, target.recordId)
    val result = DeviceLibraryOrganization(view("remote"),
        if (value.get("pending") == JSONObject.NULL) null else ServerLibraryOrganizationJson.mutation(value.getJSONObject("pending")),
        if (value.get("queued") == JSONObject.NULL) null else ServerLibraryOrganizationJson.organization(value.getJSONObject("queued")),
        view("conflict"), ServerReaderPreferencesJson.number(value, "retryAfterUntil"))
    require(result.pending != null || result.queued == null && result.conflict == null)
    return result
}
