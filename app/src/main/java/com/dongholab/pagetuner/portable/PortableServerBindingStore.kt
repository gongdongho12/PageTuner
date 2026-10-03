package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.storage.replaceFileAtomically
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

data class PortableServerBinding(val accountKey: String, val localKey: String, val recordId: String, val identity: DocumentIdentity)

/** Device-private associations. They never enter ZIP metadata or copy local records to the server. */
class PortableServerBindingStore(private val directory: File) {
    @Synchronized fun read(accountKey: String, entry: PortableLibraryEntry): PortableServerBinding? {
        val file = file(accountKey, entry.key)
        if (!file.exists()) return null
        return runCatching {
            require(file.length() <= 65_536)
            val json = JSONObject(file.readText())
            require(json.getInt("version") == 1)
            PortableServerBinding(json.getString("accountKey"), json.getString("localKey"), json.getString("recordId"),
                DocumentIdentityJson.decode(json.getJSONObject("identity"))).also {
                require(it.accountKey == accountKey && it.localKey == entry.key)
                require(PortableIdentityVerification.canonicalUuid(it.recordId))
                require(entry.document.assets.isEmpty() && DocumentIdentityJson.fromDocument(entry.document) == it.identity)
            }
        }.getOrNull()
    }

    @Synchronized fun save(value: PortableServerBinding) {
        require(PortableIdentityVerification.canonicalUuid(value.recordId))
        val bytes = JSONObject().put("version", 1).put("accountKey", value.accountKey).put("localKey", value.localKey)
            .put("recordId", value.recordId).put("identity", DocumentIdentityJson.encode(value.identity)).toString().toByteArray(Charsets.UTF_8)
        directory.mkdirs()
        val temporary = File(directory, "${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            replaceFileAtomically(temporary, file(value.accountKey, value.localKey))
        } finally { temporary.delete() }
    }

    @Synchronized fun remove(accountKey: String, entry: PortableLibraryEntry) { file(accountKey, entry.key).delete() }
    private fun file(accountKey: String, key: String) = File(directory, DocumentIds.sha256("$accountKey\n$key".toByteArray(Charsets.UTF_8)) + ".json")
}
