package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.storage.replaceFileAtomically
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class PortablePdfBinding(val recordId: String, val proof: PortableContentProof)
data class PortablePdfBindingSnapshot(val nonce: String?, val binding: PortablePdfBinding?)

/** Device-private PDF associations. An unbound tombstone retains the nonce to detect unlink/relink ABA. */
class PortablePdfBindingStore(private val library: PortableLibraryStore, private val directory: File) {
    private val lock = synchronized(locks) { locks.getOrPut(directory.canonicalPath) { Any() } }

    fun inspect(accountKey: String, origin: String, entry: PortableLibraryEntry, proof: PortableContentProof): PortablePdfBindingSnapshot =
        library.withPdfContent(entry) { _, current -> synchronized(lock) {
            require(current.proof == proof) { "PDF content changed." }
            read(accountKey, origin, entry.key)
        } }

    fun commit(accountKey: String, origin: String, entry: PortableLibraryEntry, proof: PortableContentProof,
        expected: PortablePdfBindingSnapshot, recordId: String?, guard: () -> Unit): PortablePdfBindingSnapshot =
        library.withPdfContent(entry) { _, current -> synchronized(lock) {
            guard()
            require(current.proof == proof) { "PDF content changed." }
            require(read(accountKey, origin, entry.key) == expected) { "PDF binding changed." }
            recordId?.let(PdfContentValidation::validateUuid)
            val next = PortablePdfBindingSnapshot(UUID.randomUUID().toString(), recordId?.let { PortablePdfBinding(it, proof.copy(assets = proof.assets.toList())) })
            val bytes = encode(accountKey, origin, entry.key, next)
            require(bytes.size <= MAX_BYTES)
            directory.mkdirs()
            val temporary = File(directory, "${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                guard() // After disk IO, immediately before the atomic durable replacement.
                replaceFileAtomically(temporary, file(accountKey, origin, entry.key))
            } finally { temporary.delete() }
            next
        } }

    fun <T> verified(accountKey: String, origin: String, entry: PortableLibraryEntry, proof: PortableContentProof,
        expected: PortablePdfBindingSnapshot, guard: () -> Unit, block: (LibraryExchangePackage) -> T): T =
        library.withPdfContent(entry) { archive, current -> synchronized(lock) {
            guard()
            require(current.proof == proof && expected.binding?.proof == proof) { "PDF content changed." }
            require(read(accountKey, origin, entry.key) == expected) { "PDF binding changed." }
            block(archive)
        } }

    private fun read(accountKey: String, origin: String, localKey: String): PortablePdfBindingSnapshot {
        val file = file(accountKey, origin, localKey)
        if (!file.exists()) return PortablePdfBindingSnapshot(null, null)
        // Corrupt metadata is an error, never a silently reset absent binding.
        val bytes = file.inputStream().use { readPortableBytes(it, MAX_BYTES) }
        return DataInputStream(bytes.inputStream()).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1)
            require(input.text(64) == accountKey && input.text(2048) == origin && input.text(100) == localKey)
            val nonce = input.text(36).also(PdfContentValidation::validateUuid)
            val present = input.readUnsignedByte(); require(present in 0..1)
            val binding = if (present == 0) null else {
                val id = input.text(36).also(PdfContentValidation::validateUuid)
                val version = input.readInt()
                val language = input.text(35); val paragraphHash = input.text(64)
                val originalLength = input.readLong(); val originalHash = input.text(64); val hash = input.text(64)
                val count = input.readInt(); require(count in 1..PdfContentValidation.MAX_REFERENCES)
                val proof = PortableContentProof(version, PortableRepresentation.PDF, language, paragraphHash, originalLength, originalHash,
                    List(count) { PortableAssetProof(input.text(71), input.text(5), input.nullable(500), input.nullable(2000),
                        input.text(32), input.readLong(), input.text(64)) }, hash)
                PdfContentValidation.validateProof(proof)
                PortablePdfBinding(id, proof)
            }
            require(input.read() == -1) { "Trailing PDF binding data." }
            PortablePdfBindingSnapshot(nonce, binding)
        }
    }

    // Length-framed, private device format: no JSON coercion/duplicates or unbounded object graph.
    private fun encode(accountKey: String, origin: String, localKey: String, value: PortablePdfBindingSnapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(1); out.text(accountKey); out.text(origin); out.text(localKey); out.text(requireNotNull(value.nonce))
            val binding = value.binding
            out.writeByte(if (binding == null) 0 else 1)
            if (binding != null) {
                val p = binding.proof; PdfContentValidation.validateProof(p)
                out.text(binding.recordId); out.writeInt(p.version); out.text(p.language); out.text(p.paragraphHash)
                out.writeLong(requireNotNull(p.originalFileByteLength)); out.text(requireNotNull(p.originalFileSha256)); out.text(p.sha256)
                out.writeInt(p.assets.size)
                p.assets.forEach { a -> out.text(a.path); out.text(a.role); out.nullable(a.paragraphId); out.nullable(a.alt)
                    out.text(a.mimeType); out.writeLong(a.byteLength); out.text(a.sha256) }
            }
        }
        return bytes.toByteArray()
    }
    private fun DataOutputStream.text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes) }
    private fun DataOutputStream.nullable(value: String?) { if (value == null) writeInt(-1) else text(value) }
    private fun DataInputStream.nullable(max: Int): String? { val length = readInt(); return if (length == -1) null else text(max, length) }
    private fun DataInputStream.text(max: Int, length: Int = readInt()): String {
        require(length in 0..max * 4 && length <= available())
        val bytes = ByteArray(length); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString().also { require(it.length <= max) }
    }

    private fun file(accountKey: String, origin: String, localKey: String): File {
        require(accountKey.matches(Regex("[a-f0-9]{64}")) && origin.length in 1..2048 && localKey.matches(Regex("[a-f0-9]{64}:[0-9]+")))
        return File(directory, DocumentIds.sha256("$accountKey\n$origin\n$localKey".toByteArray()) + ".pdfbinding")
    }
    companion object { private const val MAGIC = 0x50545042; private const val MAX_BYTES = 3 * 1024 * 1024; private val locks = mutableMapOf<String, Any>() }
}
