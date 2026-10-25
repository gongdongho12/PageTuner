package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.storage.replaceFileAtomically
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

data class PortableServerBinding(
    val accountKey: String,
    val localKey: String,
    val recordId: String,
    val identity: DocumentIdentity,
    val nonce: String = UUID.randomUUID().toString(),
)

data class PortableServerBindingSnapshot(val nonce: String?, val binding: PortableServerBinding?)

/** Device-private associations. Nonces and unlink tombstones never enter ZIP metadata. */
class PortableServerBindingStore(private val directory: File) {
    private val lockState = synchronized(locks) { locks.getOrPut(directory.canonicalPath) { BindingLock() } }
    private val lock = lockState.monitor

    /**
     * Keep the exact v2 text association locked through a caller's synchronous journal commit.
     * Verification never migrates or rewrites metadata. Guards may check memory/session state;
     * they must not acquire earlier account/library locks. No guard runs after block returns.
     * This low-level operation does not read the canonical ZIP. Callers must hold its read-only
     * library scope, normally through PortableTextBindingAccess.verified.
     */
    internal fun <T> verified(accountKey: String, entry: PortableLibraryEntry,
        expected: PortableServerBindingSnapshot, current: () -> Unit, block: () -> T): T {
        val captured = expected.copy(binding = expected.binding?.let { it.copy(identity = it.identity.copy()) })
        val selected = detachedTextBindingEntry(entry)
        val linked = requireNotNull(captured.binding) { "Connect this document first." }
        require(captured.nonce == linked.nonce && PortableIdentityVerification.canonicalUuid(linked.nonce))
        require(linked.accountKey == accountKey && linked.localKey == selected.key)
        validate(linked)
        require(DocumentIdentityJson.fromDocument(selected.document) == linked.identity) { "Portable document changed." }
        return synchronized(lock) {
            lockState.verifiedDepth++
            try {
                current()
                val (version, stored) = readStored(accountKey, selected.key)
                require(version == 2 && stored == captured) { "Portable binding changed." }
                current()
                block()
            } finally { lockState.verifiedDepth-- }
        }
    }

    /** Compatibility lookup: malformed or mismatched associations cannot activate a reader. */
    fun read(accountKey: String, entry: PortableLibraryEntry): PortableServerBinding? =
        runCatching { readSnapshot(accountKey, entry).binding }.getOrNull()

    /** Strict lookup for operations that must distinguish absence from corrupt or stale metadata. */
    fun readSnapshot(accountKey: String, entry: PortableLibraryEntry): PortableServerBindingSnapshot = synchronized(lock) {
        val (version, snapshot) = readStored(accountKey, entry.key)
        snapshot.binding?.let {
            require(entry.document.assets.isEmpty() && DocumentIdentityJson.fromDocument(entry.document) == it.identity) {
                "Portable document changed."
            }
        }
        if (version != 1) return@synchronized snapshot
        // Validate the whole current document before changing a legacy file. Concurrent store
        // instances share this lock and therefore observe the same durable generation.
        val binding = requireNotNull(snapshot.binding).copy(nonce = UUID.randomUUID().toString())
        val migrated = PortableServerBindingSnapshot(binding.nonce, binding)
        write(accountKey, entry.key, migrated)
        migrated
    }

    /** Every explicit save is a new generation, even if all document fields are unchanged. */
    fun save(value: PortableServerBinding): PortableServerBinding = synchronized(lock) {
        validate(value)
        readStored(value.accountKey, value.localKey) // Preserve unsupported or corrupt metadata.
        val saved = value.copy(nonce = UUID.randomUUID().toString())
        write(saved.accountKey, saved.localKey, PortableServerBindingSnapshot(saved.nonce, saved))
        saved
    }

    /** The caller holds the library lock while comparing its latest canonical document. */
    fun saveIfCurrent(accountKey: String, entry: PortableLibraryEntry, expected: PortableServerBindingSnapshot,
        value: PortableServerBinding, guard: () -> Unit = {}): PortableServerBinding = synchronized(lock) {
        guard()
        require(value.accountKey == accountKey && value.localKey == entry.key)
        validate(value)
        require(entry.document.assets.isEmpty() && DocumentIdentityJson.fromDocument(entry.document) == value.identity)
        require(readSnapshot(accountKey, entry) == expected) { "Portable binding changed." }
        val saved = value.copy(nonce = UUID.randomUUID().toString())
        write(accountKey, entry.key, PortableServerBindingSnapshot(saved.nonce, saved), guard)
        saved
    }

    fun remove(accountKey: String, entry: PortableLibraryEntry) = synchronized(lock) {
        readStored(accountKey, entry.key)
        write(accountKey, entry.key, PortableServerBindingSnapshot(UUID.randomUUID().toString(), null))
    }

    /** A late verification failure must never unlink a newer association. */
    fun removeIfCurrent(accountKey: String, entry: PortableLibraryEntry, expected: PortableServerBinding): Boolean = synchronized(lock) {
        if (readSnapshot(accountKey, entry).binding != expected) return@synchronized false
        write(accountKey, entry.key, PortableServerBindingSnapshot(UUID.randomUUID().toString(), null))
        true
    }

    private fun readStored(accountKey: String, localKey: String): Pair<Int, PortableServerBindingSnapshot> {
        val file = file(accountKey, localKey)
        if (!file.exists()) return 2 to PortableServerBindingSnapshot(null, null)
        val bytes = file.inputStream().use { readPortableBytes(it, MAX_BYTES) }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val json = JSONObject(StrictExchangeJson.normalize(text))
        val version = json.get("version")
        require(version is Number && (version.toDouble() == 1.0 || version.toDouble() == 2.0)) {
            "Unsupported portable binding version."
        }
        require(json.get("accountKey") == accountKey && json.get("localKey") == localKey)
        if (version.toInt() == 1) {
            require(json.keys().asSequence().toSet() == setOf("version", "accountKey", "localKey", "recordId", "identity"))
            val binding = PortableServerBinding(accountKey, localKey, json.get("recordId") as String,
                DocumentIdentityJson.decode(json.getJSONObject("identity")))
            validate(binding)
            return 1 to PortableServerBindingSnapshot(null, binding)
        }
        require(json.keys().asSequence().toSet() == setOf("version", "accountKey", "localKey", "nonce", "binding"))
        val nonce = (json.get("nonce") as String).also { require(PortableIdentityVerification.canonicalUuid(it)) }
        val binding = if (json.get("binding") == JSONObject.NULL) null else {
            val value = json.getJSONObject("binding")
            require(value.keys().asSequence().toSet() == setOf("recordId", "identity"))
            PortableServerBinding(accountKey, localKey, value.get("recordId") as String,
                DocumentIdentityJson.decode(value.getJSONObject("identity")), nonce).also(::validate)
        }
        return 2 to PortableServerBindingSnapshot(nonce, binding)
    }

    private fun write(accountKey: String, localKey: String, snapshot: PortableServerBindingSnapshot, guard: () -> Unit = {}) {
        check(lockState.verifiedDepth == 0) { "Cannot change a portable binding during verified access." }
        val binding = snapshot.binding
        val json = JSONObject().put("version", 2).put("accountKey", accountKey).put("localKey", localKey)
            .put("nonce", requireNotNull(snapshot.nonce)).put("binding", binding?.let {
                JSONObject().put("recordId", it.recordId).put("identity", DocumentIdentityJson.encode(it.identity))
            } ?: JSONObject.NULL)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Portable binding exceeds its storage limit." }
        require(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            guard()
            replaceFileAtomically(temporary, file(accountKey, localKey))
        } finally { temporary.delete() }
    }

    private fun validate(value: PortableServerBinding) {
        require(PortableIdentityVerification.canonicalUuid(value.recordId))
        DocumentIdentities.validate(value.identity)
    }

    private fun file(accountKey: String, key: String) =
        File(directory, DocumentIds.sha256("$accountKey\n$key".toByteArray(Charsets.UTF_8)) + ".json")

    companion object {
        private const val MAX_BYTES = 65_536
        private class BindingLock(val monitor: Any = Any(), var verifiedDepth: Int = 0)
        private val locks = mutableMapOf<String, BindingLock>()
    }
}
