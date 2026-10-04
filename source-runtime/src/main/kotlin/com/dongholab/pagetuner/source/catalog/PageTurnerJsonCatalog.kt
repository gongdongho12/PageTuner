package com.dongholab.pagetuner.source.catalog

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

data class JsonCatalogLink(val rel: String, val href: String, val type: String? = null)
data class JsonCatalogTranslationHints(val sourceLanguage: String = "auto", val targetLanguages: List<String> = emptyList())
data class JsonCatalogEntry(
    val id: String,
    val title: String,
    val authors: List<String>,
    /** Normalized to txt, markdown, epub or pdf. */
    val format: String,
    val href: String,
    val language: String? = null,
    val type: String? = null,
    val size: Long? = null,
    val checksum: String? = null,
    val updatedAt: String? = null,
    val cover: String? = null,
    val translationHints: JsonCatalogTranslationHints = JsonCatalogTranslationHints(),
)
data class JsonCatalogDocument(
    val version: String,
    val id: String,
    val title: String,
    val catalogUrl: String,
    val updatedAt: String? = null,
    val links: List<JsonCatalogLink> = emptyList(),
    val items: List<JsonCatalogEntry> = emptyList(),
)

/** Shared v0 parser. Android and the server adapt this same result to their own UI/transport models. */
object PageTurnerJsonCatalogParser {
    const val Version = "pagetuner.catalog.v0"
    const val MaxBytes = 5 * 1024 * 1024

    fun parse(rawJson: String, catalogUrl: String): JsonCatalogDocument {
        require(rawJson.toByteArray(Charsets.UTF_8).size <= MaxBytes) { "Catalog exceeds 5 MB." }
        val root = try { JSONObject(rawJson) } catch (error: Exception) {
            throw IllegalArgumentException("The catalog is not valid JSON.", error)
        }
        val version = root.string("version") ?: Version
        require(version == Version) { "Unsupported PageTurner catalog version: $version" }
        val id = root.required("id")
        val base = URI(catalogUrl)
        require(base.isAbsolute) { "An absolute catalog URL is required." }
        fun resolve(value: String): String {
            require(value.length <= 4096 && value.none(Char::isISOControl) && '\\' !in value) { "Invalid catalog link." }
            // java.net.URI resolves query-only references against the containing directory;
            // catalog pagination must retain the current resource path, as browsers do.
            val resolved = base.resolve(if (value.startsWith("?")) base.rawPath + value else value).toString()
            require(resolved.length <= 4096) { "Catalog link is too long." }
            return resolved
        }
        val links = root.objects("links", 100).map { item ->
            JsonCatalogLink(item.required("rel", 100), resolve(item.required("href", 4096)), item.string("type"))
        }
        val items = root.objects("items", 1000).map { item ->
            val format = when (val raw = item.required("format", 20).lowercase()) {
                "txt", "text" -> "txt"
                "md", "markdown" -> "markdown"
                "epub", "pdf" -> raw
                else -> throw IllegalArgumentException("Unsupported remote book format: $raw")
            }
            val hints = if (!item.has("translationHints") || item.isNull("translationHints")) null
                else item.opt("translationHints") as? JSONObject ?: throw IllegalArgumentException("Invalid translation hints.")
            val size = if (!item.has("size") || item.isNull("size")) null else {
                val number = item.opt("size")
                require(number is Number && number.toDouble().isFinite() && number.toDouble() >= 0 &&
                    number.toDouble() <= 9_007_199_254_740_991.0 && number.toDouble() == number.toLong().toDouble()) { "Catalog size must be a nonnegative safe integer." }
                number.toLong()
            }
            JsonCatalogEntry(item.required("id"), item.required("title"), item.strings("authors", 100), format,
                resolve(item.required("href", 4096)), item.string("language", 24), item.string("type"), size,
                item.string("checksum"), item.string("updatedAt"), item.string("cover", 4096)?.let(::resolve),
                JsonCatalogTranslationHints(hints?.string("sourceLanguage", 24) ?: "auto", hints?.strings("targetLanguages", 100, 24).orEmpty()))
        }
        require(items.map { it.id }.distinct().size == items.size) { "Catalog item IDs must be unique within a page." }
        return JsonCatalogDocument(version, id, root.string("title") ?: id, catalogUrl, root.string("updatedAt"), links, items)
    }

    private fun JSONObject.required(name: String, max: Int = 2000): String =
        string(name, max) ?: throw IllegalArgumentException("Catalog field '$name' is required.")

    private fun JSONObject.string(name: String, max: Int = 2000): String? {
        if (!has(name) || isNull(name)) return null
        val value = opt(name)
        require(value is String && value.length <= max) { "Invalid catalog field '$name'." }
        return value.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.array(name: String, max: Int): JSONArray? {
        if (!has(name) || isNull(name)) return null
        val value = opt(name)
        require(value is JSONArray && value.length() <= max) { "Invalid catalog array '$name' (maximum $max entries)." }
        return value
    }

    private fun JSONObject.objects(name: String, max: Int): List<JSONObject> {
        val values = array(name, max) ?: return emptyList()
        return List(values.length()) { index -> values.opt(index) as? JSONObject
            ?: throw IllegalArgumentException("Catalog '$name' contains an invalid item at $index.") }
    }

    private fun JSONObject.strings(name: String, max: Int, length: Int = 2000): List<String> {
        val values = array(name, max) ?: return emptyList()
        return List(values.length()) { index ->
            val value = values.opt(index)
            require(value is String && value.length <= length) { "Invalid catalog '$name' entry." }
            value
        }.filter(String::isNotBlank)
    }
}
