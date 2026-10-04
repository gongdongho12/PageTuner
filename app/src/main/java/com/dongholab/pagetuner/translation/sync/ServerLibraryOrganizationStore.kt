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
}
/** Account/origin and exact server identity are hashed; credentials and local library entries stay separate. */
class FileServerLibraryOrganizationStore(private val directory: File) : ServerLibraryOrganizationStore {
    override fun read(target: ServerReadingTarget): DeviceLibraryOrganization {
        val file = file(target)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return DeviceLibraryOrganization()
        return decodeLibraryOrganizationJournal(readJson(file), target)
    }
    override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization) {
        val bytes = encodeLibraryOrganizationJournal(target, value).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65_536); check(directory.isDirectory || directory.mkdirs())
        val file = file(target); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    override fun targets(accountKey: String): List<ServerReadingTarget> = directory.listFiles().orEmpty()
        .map { it.name.removeSuffix(".bak") }.filter { it.matches(Regex("[a-f0-9]{64}\\.json")) }.distinct().mapNotNull { name ->
            val value = readJson(AtomicFile(File(directory, name)))
            val parts = (value.get("key") as String).split(':')
            require(parts.size == 3)
            if (parts[0] != accountKey) null else {
                val target = ServerReadingTarget(parts[0], parts[1], parts[2])
                require(name == StableContentHash.sha256(target.key) + ".json")
                decodeLibraryOrganizationJournal(value, target); target
            }
        }
    private fun file(target: ServerReadingTarget) = AtomicFile(File(directory, StableContentHash.sha256(target.key) + ".json"))
    private fun readJson(file: AtomicFile): JSONObject = file.openRead().use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= 65_536); output.write(buffer, 0, count)
        }
        JSONObject(output.toByteArray().toString(Charsets.UTF_8))
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
