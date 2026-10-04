package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.ExchangeDocument
import com.dongholab.pagetuner.reader.ReaderAnnotation
import com.dongholab.pagetuner.reader.ReaderAnnotationType
import com.dongholab.pagetuner.reader.ReaderBookmark
import org.json.JSONArray
import org.json.JSONObject

internal data class PortablePageState(
    val pageIndex: Int? = null,
    val bookmarks: List<ReaderBookmark> = emptyList(),
    val annotations: List<ReaderAnnotation> = emptyList(),
)

/** PDF/image pages have no portable paragraph anchor. Keep their state in a passive extension. */
internal object PortablePageMetadata {
    private const val Prefix = "pageturnerAndroidReader"
    private const val Format = "pageturner.android-reader"

    fun read(value: ExchangeDocument, mapping: PortableReaderMapping, pdf: Boolean): PortablePageState {
        val root = value.extensionsJson?.let(::JSONObject) ?: return PortablePageState()
        val own = find(root, pdf)?.second
        // The old native exporter wrote PDF page notes here. Never overwrite this passive object.
        val source = own ?: if (pdf) root.optJSONObject("android") else null
        source ?: return PortablePageState()
        return project(source, mapping.document.pageCount, if (pdf) emptySet() else value.notes.map { it.id }.toSet())
    }

    fun merge(value: ExchangeDocument, mapping: PortableReaderMapping, pageIndex: Int,
        bookmarks: List<ReaderBookmark>, annotations: List<ReaderAnnotation>, pdf: Boolean): ExchangeDocument {
        val root = value.extensionsJson?.let(::JSONObject) ?: JSONObject()
        val own = find(root, pdf)
        val pageBookmarks = bookmarks.filter { pdf || mapping.anchorFor(it.pageIndex) == null }
        val pageAnnotations = annotations.filter { pdf || mapping.anchorFor(it.pageIndex) == null }
        if (!pdf && own == null && mapping.anchorFor(pageIndex) != null && pageBookmarks.isEmpty() && pageAnnotations.isEmpty()) return value
        val source = own?.second ?: if (pdf) root.optJSONObject("android") else null
        val initial = source?.let { project(it, mapping.document.pageCount, if (pdf) emptySet() else value.notes.map { note -> note.id }.toSet()) } ?: PortablePageState()
        // Copy only our own extension; unknown legacy fields retain their original location and value.
        val next = own?.second?.let { JSONObject(it.toString()) } ?: JSONObject()
        next.put("format", Format).put("version", 1).put("mode", if (pdf) "pdf" else "content").put("pageIndex", pageIndex)
        next.put("pageBookmarks", mergeEntries(source?.optJSONArray("pageBookmarks"), initial.bookmarks.associateBy { it.id }, pageBookmarks.associateBy { it.id }) { item, previous ->
            previous.put("id", item.id).put("pageIndex", item.pageIndex).put("label", item.label ?: JSONObject.NULL).put("createdAtMillis", item.createdAtMillis)
        })
        next.put("pageAnnotations", mergeEntries(source?.optJSONArray("pageAnnotations"), initial.annotations.associateBy { it.id }, pageAnnotations.associateBy { it.id }) { item, previous ->
            previous.put("id", item.id).put("pageIndex", item.pageIndex).put("text", item.text).put("kind", item.type.name).put("createdAtMillis", item.createdAtMillis)
        })
        val key = own?.first ?: generateSequence(0) { it + 1 }.map { if (it == 0) Prefix else "$Prefix$it" }.first { !root.has(it) }
        root.put(key, next)
        return value.copy(extensionsJson = root.toString())
    }

    private fun find(root: JSONObject, pdf: Boolean): Pair<String, JSONObject>? = root.keys().asSequence()
        .filter { it == Prefix || it.startsWith(Prefix) && it.removePrefix(Prefix).all(Char::isDigit) }
        .sorted().mapNotNull { key -> root.optJSONObject(key)?.takeIf { objectValue ->
            objectValue.opt("format") == Format && objectValue.opt("version") == 1 &&
                objectValue.opt("mode") == (if (pdf) "pdf" else "content") &&
                integer(objectValue, "pageIndex") != null && objectValue.opt("pageBookmarks") is JSONArray && objectValue.opt("pageAnnotations") is JSONArray
        }?.let { key to it } }.firstOrNull()

    private fun project(source: JSONObject, pageCount: Int, excludedIds: Set<String>): PortablePageState {
        val rawBookmarks = source.optJSONArray("pageBookmarks")
        val rawAnnotations = source.optJSONArray("pageAnnotations")
        val bookmarks = objects(rawBookmarks).mapNotNull { item ->
            val id = string(item, "id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val page = integer(item, "pageIndex")?.takeIf { it in 0 until pageCount } ?: return@mapNotNull null
            val time = long(item, "createdAtMillis") ?: return@mapNotNull null
            val label = if (item.isNull("label")) null else string(item, "label") ?: return@mapNotNull null
            ReaderBookmark(id, page, label, time)
        }.let { items -> val counts = items.groupingBy { it.id }.eachCount(); items.filter { counts[it.id] == 1 && it.id !in excludedIds } }
        val annotations = objects(rawAnnotations).mapNotNull { item ->
            val id = string(item, "id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val page = integer(item, "pageIndex")?.takeIf { it in 0 until pageCount } ?: return@mapNotNull null
            val time = long(item, "createdAtMillis") ?: return@mapNotNull null
            val text = string(item, "text")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val kind = when (string(item, "kind")) { "Highlight" -> ReaderAnnotationType.Highlight; "Note" -> ReaderAnnotationType.Note; else -> return@mapNotNull null }
            ReaderAnnotation(id, kind, page, text, time)
        }.let { items -> val counts = items.groupingBy { it.id }.eachCount(); items.filter { counts[it.id] == 1 && it.id !in excludedIds } }
        return PortablePageState(integer(source, "pageIndex")?.takeIf { it in 0 until pageCount }, bookmarks, annotations)
    }

    /** Unknown/unsupported items remain byte-equivalent JSON values; known items retain extra fields. */
    private fun <T> mergeEntries(raw: JSONArray?, projected: Map<String, T>, current: Map<String, T>, encode: (T, JSONObject) -> JSONObject): JSONArray {
        val result = JSONArray()
        val written = mutableSetOf<String>()
        for (index in 0 until (raw?.length() ?: 0)) {
            val item = requireNotNull(raw).get(index)
            val id = (item as? JSONObject)?.let { string(it, "id") }
            if (id == null || id !in projected) result.put(item)
            else current[id]?.let { value -> result.put(if (value == projected[id]) item else encode(value, JSONObject(item.toString()))); written += id }
        }
        current.filterKeys { it !in written }.values.forEach { result.put(encode(it, JSONObject())) }
        return result
    }

    private fun objects(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
    private fun string(value: JSONObject, key: String) = value.opt(key) as? String
    private fun long(value: JSONObject, key: String): Long? = when (val number = value.opt(key)) { is Int -> number.toLong(); is Long -> number; else -> null }
    private fun integer(value: JSONObject, key: String): Int? = long(value, key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}
