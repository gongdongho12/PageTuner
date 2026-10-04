package com.dongholab.pagetuner.translation.sync

import org.json.JSONArray
import org.json.JSONObject

data class LibraryOrganization(val folder: String = "", val tags: List<String> = emptyList(), val favorite: Boolean = false)
data class ServerLibraryOrganization(val kind: String, val recordId: String, val version: Long,
    val organization: LibraryOrganization?, val updatedAt: String?)
data class LibraryOrganizationMutation(val expectedVersion: Long, val mutationId: String, val organization: LibraryOrganization)
class LibraryOrganizationConflict(val current: ServerLibraryOrganization) : Exception("Library organization conflict")
class LibraryOrganizationRateLimited(val retryAfterSeconds: Long) : Exception("Library organization rate limited")

/** Matches ECMAScript trim, including BOM but excluding NEL and zero-width space. */
fun trimLibraryOrganizationText(value: String) = value.trim { it in "\u0009\u000a\u000b\u000c\u000d\u0020\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff" }

internal object ServerLibraryOrganizationJson {
    private fun text(value: String, max: Int, empty: Boolean) {
        require(value.length <= max && (empty || value.isNotEmpty()) && trimLibraryOrganizationText(value) == value)
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            require(!char.isISOControl())
            if (char.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate())
            else require(!char.isLowSurrogate())
        }
    }
    fun validate(value: LibraryOrganization) {
        text(value.folder, 200, true); require(value.tags.size <= 32 && value.tags.distinct().size == value.tags.size)
        value.tags.forEach { text(it, 60, false) }
    }
    private fun fields(value: JSONObject, expected: Set<String>) { require(value.keys().asSequence().toSet() == expected) }
    fun organization(value: JSONObject): LibraryOrganization {
        fields(value, setOf("folder", "tags", "favorite"))
        val tags = value.getJSONArray("tags")
        return LibraryOrganization(value.get("folder") as String, (0 until tags.length()).map { tags.get(it) as String },
            value.get("favorite") as Boolean).also(::validate)
    }
    fun encode(value: LibraryOrganization): JSONObject {
        validate(value)
        return JSONObject().put("folder", value.folder).put("tags", JSONArray(value.tags)).put("favorite", value.favorite)
    }
    fun view(value: JSONObject, kind: String, recordId: String): ServerLibraryOrganization {
        ServerReadingProgressJson.validateTarget(kind, recordId)
        fields(value, setOf("kind", "recordId", "version", "organization", "updatedAt"))
        require(value.get("kind") == kind && value.get("recordId") == recordId)
        val version = ServerReaderPreferencesJson.number(value, "version")
        val organization = if (value.get("organization") == JSONObject.NULL) null else organization(value.getJSONObject("organization"))
        val updated = if (value.get("updatedAt") == JSONObject.NULL) null else ServerReadingNotesJson.timestamp(value.get("updatedAt") as String)
        require(if (version == 0L) organization == null && updated == null else organization != null && updated != null)
        return ServerLibraryOrganization(kind, recordId, version, organization, updated)
    }
    fun encode(value: ServerLibraryOrganization) = JSONObject().put("kind", value.kind).put("recordId", value.recordId)
        .put("version", value.version).put("organization", value.organization?.let(::encode) ?: JSONObject.NULL)
        .put("updatedAt", value.updatedAt ?: JSONObject.NULL).also { view(it, value.kind, value.recordId) }
    fun encode(value: LibraryOrganizationMutation): JSONObject {
        require(value.expectedVersion in 0 until MaxReadingVersion); ServerReadingNotesJson.uuid(value.mutationId)
        return JSONObject().put("expectedVersion", value.expectedVersion).put("mutationId", value.mutationId)
            .put("organization", encode(value.organization)).also { require(it.toString().toByteArray(Charsets.UTF_8).size <= 8192) }
    }
    fun mutation(value: JSONObject): LibraryOrganizationMutation {
        fields(value, setOf("expectedVersion", "mutationId", "organization"))
        return LibraryOrganizationMutation(ServerReaderPreferencesJson.number(value, "expectedVersion", MaxReadingVersion - 1),
            value.get("mutationId") as String, organization(value.getJSONObject("organization"))).also { encode(it) }
    }
}
