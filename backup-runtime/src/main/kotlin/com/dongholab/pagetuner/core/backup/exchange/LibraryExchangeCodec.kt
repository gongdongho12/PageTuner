package com.dongholab.pagetuner.core.backup.exchange

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Versioned, content-addressed interchange. read() validates every entry before returning data. */
object LibraryExchangeCodec {
    const val FORMAT = "pageturner.library"
    const val VERSION = 1

    fun read(bytes: ByteArray): LibraryExchangePackage {
        require(bytes.size <= LibraryExchangeLimits.ARCHIVE_BYTES) { "Library ZIP exceeds 32 MiB." }
        val directory = ExchangeZip.inspect(bytes)
        val entries = LinkedHashMap<String, ByteArray>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val descriptor = directory[entry.name] ?: error("Unlisted ZIP entry.")
                require(!entry.isDirectory && entry.name !in entries) { "Duplicate or directory ZIP entry." }
                val limit = when {
                    entry.name == "manifest.json" -> LibraryExchangeLimits.MANIFEST_BYTES
                    entry.name.startsWith("documents/") -> LibraryExchangeLimits.DOCUMENT_BYTES
                    else -> LibraryExchangeLimits.ARCHIVE_BYTES
                }
                val output = ByteArrayOutputStream(minOf(descriptor.bytes, 64 * 1024))
                val buffer = ByteArray(16 * 1024)
                var entryBytes = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    entryBytes += count; expanded += count
                    require(entryBytes <= limit && expanded <= LibraryExchangeLimits.EXPANDED_BYTES) { "Expanded ZIP data exceeds its limit." }
                    output.write(buffer, 0, count)
                }
                require(entryBytes == descriptor.bytes.toLong()) { "ZIP entry size does not match its directory." }
                entries[entry.name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        require(entries.keys == directory.keys) { "ZIP directory and local entries differ." }
        val manifestBytes = entries["manifest.json"] ?: error("Missing manifest.json.")
        val manifest = ExchangeJson.parseObject(manifestBytes)
        manifest.only("format", "version", "createdAt", "documents", "assets")
        require(manifest.string("format") == FORMAT && manifest.integer("version") == VERSION) { "Unsupported library package format or version." }
        val createdAt = manifest.string("createdAt").also(ExchangeJson::timestamp)
        val documents = manifest.getJSONArray("documents")
        val assets = manifest.getJSONArray("assets")
        require(documents.length() in 1..LibraryExchangeLimits.MAX_DOCUMENTS) { "Invalid document count." }
        require(documents.length() + assets.length() + 1 <= LibraryExchangeLimits.MAX_ENTRIES) { "Too many manifest entries." }
        val listed = mutableSetOf("manifest.json")
        fun payload(descriptor: JSONObject, document: Boolean): ByteArray {
            descriptor.only(*if (document) arrayOf("path", "sha256", "bytes") else arrayOf("path", "sha256", "bytes", "mimeType"))
            val hash = descriptor.string("sha256")
            require(Regex("[0-9a-f]{64}").matches(hash)) { "Invalid SHA-256." }
            val expectedPath = if (document) "documents/$hash.json" else "assets/$hash"
            val path = descriptor.string("path")
            require(path == expectedPath && listed.add(path)) { "Invalid or duplicate manifest path." }
            val data = entries[path] ?: error("Missing manifest entry.")
            require(data.size == descriptor.integer("bytes") && exchangeSha256(data) == hash) { "Library entry checksum or size mismatch." }
            return data
        }
        val decodedDocuments = (0 until documents.length()).map { ExchangeJson.decode(payload(documents.getJSONObject(it), true)) }
        val decodedAssets = (0 until assets.length()).map {
            val descriptor = assets.getJSONObject(it)
            ExchangeAsset(payload(descriptor, false), descriptor.string("mimeType"))
        }
        require(listed == entries.keys) { "ZIP includes undeclared entries." }
        return LibraryExchangePackage(createdAt, decodedDocuments, decodedAssets).also(::validatePackage)
    }

    fun write(value: LibraryExchangePackage): ByteArray {
        validatePackage(value)
        val files = LinkedHashMap<String, ByteArray>()
        val documentDescriptors = JSONArray()
        value.documents.forEach { document ->
            val bytes = ExchangeJson.encode(document)
            require(bytes.size <= LibraryExchangeLimits.DOCUMENT_BYTES) { "Document JSON exceeds 8 MiB." }
            val hash = exchangeSha256(bytes)
            val path = "documents/$hash.json"
            require(files.put(path, bytes) == null) { "Duplicate document payload." }
            documentDescriptors.put(JSONObject().put("path", path).put("sha256", hash).put("bytes", bytes.size))
        }
        val assetDescriptors = JSONArray()
        value.assets.forEach { asset ->
            files[asset.path] = asset.bytes
            assetDescriptors.put(JSONObject().put("path", asset.path).put("sha256", asset.sha256)
                .put("bytes", asset.bytes.size).put("mimeType", asset.mimeType))
        }
        val manifest = JSONObject().put("format", FORMAT).put("version", VERSION).put("createdAt", value.createdAt)
            .put("documents", documentDescriptors).put("assets", assetDescriptors).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= LibraryExchangeLimits.MANIFEST_BYTES) { "Manifest exceeds 1 MiB." }
        require(files.values.sumOf { it.size.toLong() } + manifest.size <= LibraryExchangeLimits.EXPANDED_BYTES) { "Expanded package exceeds 64 MiB." }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                zip.write(bytes); zip.closeEntry()
                require(output.size() <= LibraryExchangeLimits.ARCHIVE_BYTES) { "Library ZIP exceeds 32 MiB." }
            }
            put("manifest.json", manifest)
            files.forEach { (path, bytes) -> put(path, bytes) }
        }
        require(output.size() <= LibraryExchangeLimits.ARCHIVE_BYTES) { "Library ZIP exceeds 32 MiB." }
        return output.toByteArray()
    }

    private fun validatePackage(value: LibraryExchangePackage) {
        ExchangeJson.timestamp(value.createdAt)
        require(value.documents.size in 1..LibraryExchangeLimits.MAX_DOCUMENTS) { "Invalid document count." }
        require(value.documents.size + value.assets.size + 1 <= LibraryExchangeLimits.MAX_ENTRIES) { "Too many package entries." }
        require(value.documents.map { it.id }.distinct().size == value.documents.size) { "Duplicate document ID." }
        value.documents.forEach(ExchangeJson::validate)
        val assets = value.assets.associateBy { it.path }
        require(assets.size == value.assets.size) { "Duplicate asset." }
        value.assets.forEach {
            require(it.bytes.isNotEmpty() && it.bytes.size <= LibraryExchangeLimits.ARCHIVE_BYTES) { "Invalid asset size." }
            require(it.mimeType in LibraryExchangeLimits.ASSET_MIME_TYPES) { "Unsupported asset MIME type." }
        }
        val referenced = mutableSetOf<String>()
        value.documents.forEach { document -> document.assets.forEach { ref ->
            val asset = assets[ref.path] ?: error("Missing referenced asset.")
            require(if (ref.role == "pdf") asset.mimeType == "application/pdf" else asset.mimeType.startsWith("image/")) { "Asset MIME type does not match its role." }
            referenced.add(ref.path)
        } }
        require(referenced == assets.keys) { "Package includes unreferenced assets." }
    }
}
