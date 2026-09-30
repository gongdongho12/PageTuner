package com.dongholab.pagetuner.translation.sync

import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

data class DeviceReaderPreferences(val enabled: Boolean = false, val remote: ServerReaderPreferences? = null,
    val pending: ReaderPreferencesMutation? = null, val queued: SharedReaderPreferences? = null,
    val conflict: ServerReaderPreferences? = null, val retryAfterUntil: Long = 0) {
    val local get() = queued ?: pending?.preferences ?: remote?.preferences
}

interface ServerReaderPreferencesStore {
    fun read(accountKey: String): DeviceReaderPreferences
    fun write(accountKey: String, value: DeviceReaderPreferences)
}

/** Account-scoped durable journal. No endpoint, username, password or token is written. */
class FileServerReaderPreferencesStore(private val directory: File) : ServerReaderPreferencesStore {
    private fun file(accountKey: String): AtomicFile {
        require(accountKey.matches(Regex("[a-f0-9]{64}")))
        return AtomicFile(File(directory, "$accountKey.json"))
    }
    override fun read(accountKey: String): DeviceReaderPreferences {
        val file = file(accountKey)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return DeviceReaderPreferences()
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 65_536)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decodeDeviceReaderPreferences(JSONObject(bytes.toString(Charsets.UTF_8)), accountKey)
    }
    override fun write(accountKey: String, value: DeviceReaderPreferences) {
        val data = encodeDeviceReaderPreferences(value, accountKey).toString().toByteArray(Charsets.UTF_8)
        require(data.size <= 65_536)
        check(directory.isDirectory || directory.mkdirs())
        val file = file(accountKey); val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

internal fun encodeDeviceReaderPreferences(value: DeviceReaderPreferences, accountKey: String): JSONObject = JSONObject()
    .put("format", 1).put("accountKey", accountKey).put("enabled", value.enabled)
    .put("remote", value.remote?.let(ServerReaderPreferencesJson::encode) ?: JSONObject.NULL)
    .put("pending", value.pending?.let(ServerReaderPreferencesJson::encode) ?: JSONObject.NULL)
    .put("queued", value.queued?.let(ServerReaderPreferencesJson::encode) ?: JSONObject.NULL)
    .put("conflict", value.conflict?.let(ServerReaderPreferencesJson::encode) ?: JSONObject.NULL)
    .put("retryAfterUntil", value.retryAfterUntil).also { decodeDeviceReaderPreferences(it, accountKey) }

internal fun decodeDeviceReaderPreferences(value: JSONObject, accountKey: String): DeviceReaderPreferences {
    require(accountKey.matches(Regex("[a-f0-9]{64}")) && value.get("accountKey") == accountKey && value.get("format") == 1)
    fun view(name: String) = if (value.get(name) == JSONObject.NULL) null else ServerReaderPreferencesJson.view(value.getJSONObject(name))
    val record = DeviceReaderPreferences(value.get("enabled") as Boolean, view("remote"),
        if (value.get("pending") == JSONObject.NULL) null else ServerReaderPreferencesJson.mutation(value.getJSONObject("pending")),
        if (value.get("queued") == JSONObject.NULL) null else ServerReaderPreferencesJson.preferences(value.getJSONObject("queued")),
        view("conflict"), ServerReaderPreferencesJson.number(value, "retryAfterUntil"))
    require(record.pending != null || record.queued == null && record.conflict == null)
    require(if (record.enabled) record.local != null else record.pending == null)
    return record
}
