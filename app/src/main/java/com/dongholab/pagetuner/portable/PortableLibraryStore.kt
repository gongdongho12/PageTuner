package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.storage.replaceFileAtomically
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PortableLibraryEntry(val packageId: String, val documentIndex: Int, val document: ExchangeDocument) {
    val key: String get() = "$packageId:$documentIndex"
    val readerId: String get() = "portable:$key"
}
data class PortableImportResult(val entries: List<PortableLibraryEntry>, val duplicate: Boolean)

/** One validated archive is one atomic commit. Existing native books are never touched. */
class PortableLibraryStore(private val directory: File) {
    private val lock = lockFor(directory)

    fun importArchive(bytes: ByteArray): PortableImportResult = synchronized(lock) {
        val value = LibraryExchangeCodec.read(bytes) // Entire archive validated before any write.
        val canonical = LibraryExchangeCodec.write(value.copy(createdAt = "2000-01-01T00:00:00.000Z"))
        val packageId = DocumentIds.sha256(canonical)
        val target = file(packageId)
        val duplicate = target.exists()
        if (!duplicate) atomicWrite(target, LibraryExchangeCodec.write(value))
        val stored = if (duplicate) LibraryExchangeCodec.read(readBounded(target)) else value
        PortableImportResult(entries(packageId, stored), duplicate)
    }

    fun list(): List<PortableLibraryEntry> = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{64}\\.zip")) }
            .sortedByDescending { it.lastModified() }.flatMap { source ->
                entries(source.nameWithoutExtension, LibraryExchangeCodec.read(readBounded(source)))
            }
    }

    fun read(entry: PortableLibraryEntry): LibraryExchangePackage = synchronized(lock) {
        LibraryExchangeCodec.read(readBounded(file(entry.packageId)))
    }

    fun export(entry: PortableLibraryEntry): ByteArray = synchronized(lock) {
        val source = read(entry)
        val document = source.documents[entry.documentIndex]
        val paths = document.assets.map { it.path }.toSet()
        LibraryExchangeCodec.write(LibraryExchangePackage(portableTimestamp(), listOf(document), source.assets.filter { it.path in paths }))
    }

    fun update(entry: PortableLibraryEntry, transform: (ExchangeDocument) -> ExchangeDocument) = synchronized(lock) {
        val current = read(entry)
        val previous = current.documents[entry.documentIndex]
        val next = transform(previous)
        if (next == previous) return@synchronized
        val updated = current.copy(documents = current.documents.mapIndexed { index, document -> if (index == entry.documentIndex) next else document })
        val bytes = LibraryExchangeCodec.write(updated) // Validate update before replacing committed archive.
        atomicWrite(file(entry.packageId), bytes)
    }

    private fun file(packageId: String): File {
        require(packageId.matches(Regex("[0-9a-f]{64}"))) { "Invalid archive identifier." }
        return File(directory, "$packageId.zip")
    }

    private fun entries(packageId: String, value: LibraryExchangePackage) = value.documents.mapIndexed { index, document -> PortableLibraryEntry(packageId, index, document) }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        directory.mkdirs()
        val temporary = File(directory, "${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            replaceFileAtomically(temporary, target)
        } finally { temporary.delete() }
    }

    companion object {
        private val locks = mutableMapOf<String, Any>()
        private fun lockFor(directory: File): Any = synchronized(locks) { locks.getOrPut(directory.canonicalPath) { Any() } }
        internal fun readBounded(file: File): ByteArray {
            require(file.length() <= LibraryExchangeLimits.ARCHIVE_BYTES) { "Archive exceeds the size limit." }
            return file.inputStream().use(::readPortableBytes)
        }
    }
}

internal fun readPortableBytes(input: java.io.InputStream, maxBytes: Int = LibraryExchangeLimits.ARCHIVE_BYTES): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= maxBytes) { "File exceeds the exchange size limit." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
