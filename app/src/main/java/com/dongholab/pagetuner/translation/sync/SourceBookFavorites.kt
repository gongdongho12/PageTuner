package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.model.library.SourceBookFavoriteMetadata
import com.dongholab.pagetuner.core.model.library.SourceBookFavoriteValidation
import com.dongholab.pagetuner.source.RemoteBookIdentity
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteSourceType
import com.dongholab.pagetuner.document.DocumentFormat
import org.json.JSONArray
import org.json.JSONObject

data class SourceFavoriteIdentity(val providerId: String, val bookId: String) {
    // Length-prefixed components preserve arbitrary opaque IDs, including separators.
    val key get() = "${providerId.length}:$providerId${bookId.length}:$bookId"
}
data class SourceBookFavorite(val identity: SourceFavoriteIdentity, val version: Long, val changeRevision: Long,
    val deleted: Boolean, val book: SourceBookFavoriteMetadata?, val updatedAt: String?)
data class SourceFavoriteDesired(val identity: SourceFavoriteIdentity, val deleted: Boolean, val book: SourceBookFavoriteMetadata?)
data class SourceFavoriteMutation(val desired: SourceFavoriteDesired, val expectedVersion: Long, val mutationId: String)
data class SourceFavoritesPage(val items: List<SourceBookFavorite>, val nextAfterRevision: Long, val watermark: Long, val hasMore: Boolean)
class SourceFavoriteConflict(val current: SourceBookFavorite) : Exception("Source favorite conflict")
class SourceFavoritesRateLimited(val retryAfterSeconds: Long) : Exception("Source favorites rate limited")

interface SourceBookFavoritesRemote {
    suspend fun sourceBookFavorites(afterRevision: Long, limit: Int, untilRevision: Long?): SourceFavoritesPage
    suspend fun saveSourceBookFavorite(mutation: SourceFavoriteMutation): SourceBookFavorite
}

fun RemoteBookItem.sourceFavorite(): SourceFavoriteDesired? = runCatching {
    val identity = SourceFavoriteIdentity(requireNotNull(sourceProviderId), requireNotNull(sourceBookId))
    SourceFavoriteDesired(identity, false, SourceBookFavoriteMetadata(trimLibraryOrganizationText(title), authors.map(::trimLibraryOrganizationText).filter(String::isNotEmpty),
        trimLibraryOrganizationText(language ?: "auto"), java.net.URI(downloadUrl).toASCIIString()))
        .also(SourceFavoritesJson::validate)
}.getOrNull()

fun SourceFavoriteDesired.remoteBook(): RemoteBookItem? = book?.let {
    RemoteBookItem(RemoteBookIdentity(RemoteSourceType.WebNovel, "account-favorite", identity.key),
        it.title, it.authors, DocumentFormat.TEXT, it.language, it.url,
        seriesId = identity.bookId, sourceProviderId = identity.providerId, sourceBookId = identity.bookId)
}

internal object SourceFavoritesJson {
    private fun fields(value: JSONObject, expected: Set<String>) { require(value.keys().asSequence().toSet() == expected) }
    fun identity(value: JSONObject) = SourceFavoriteIdentity(value.get("providerId") as String, value.get("bookId") as String)
        .also { SourceBookFavoriteValidation.validateIdentity(it.providerId, it.bookId) }
    fun validate(value: SourceFavoriteDesired) {
        SourceBookFavoriteValidation.validateIdentity(value.identity.providerId, value.identity.bookId)
        require(value.deleted == (value.book == null)); value.book?.let(SourceBookFavoriteValidation::validateMetadata)
    }
    fun book(value: JSONObject): SourceBookFavoriteMetadata {
        fields(value, setOf("title", "authors", "language", "url"))
        val authors = value.getJSONArray("authors")
        return SourceBookFavoriteMetadata(value.get("title") as String, List(authors.length()) { authors.get(it) as String },
            value.get("language") as String, value.get("url") as String).also(SourceBookFavoriteValidation::validateMetadata)
    }
    fun encode(value: SourceBookFavoriteMetadata): JSONObject = JSONObject().put("title", value.title).put("authors", JSONArray(value.authors))
        .put("language", value.language).put("url", value.url).also { SourceBookFavoriteValidation.validateMetadata(value) }
    fun desired(value: JSONObject) = SourceFavoriteDesired(identity(value), value.get("deleted") as Boolean,
        if (value.get("book") == JSONObject.NULL) null else book(value.getJSONObject("book"))).also(::validate)
    fun encode(value: SourceFavoriteDesired): JSONObject = JSONObject().put("providerId", value.identity.providerId).put("bookId", value.identity.bookId)
        .put("deleted", value.deleted).put("book", value.book?.let(::encode) ?: JSONObject.NULL).also { validate(value) }
    fun item(value: JSONObject): SourceBookFavorite {
        fields(value, setOf("providerId", "bookId", "version", "changeRevision", "deleted", "book", "updatedAt"))
        val desired = desired(value); val version = ServerReadingNotesJson.number(value, "version")
        val revision = ServerReadingNotesJson.number(value, "changeRevision")
        val updated = if (value.get("updatedAt") == JSONObject.NULL) null else ServerReadingNotesJson.timestamp(value.get("updatedAt") as String)
        require(if (version == 0L) revision == 0L && desired.deleted && updated == null else revision > 0 && updated != null)
        return SourceBookFavorite(desired.identity, version, revision, desired.deleted, desired.book, updated)
    }
    fun encode(value: SourceBookFavorite): JSONObject = encode(SourceFavoriteDesired(value.identity, value.deleted, value.book))
        .put("version", value.version).put("changeRevision", value.changeRevision).put("updatedAt", value.updatedAt ?: JSONObject.NULL)
        .also { item(it) }
    fun encode(value: SourceFavoriteMutation): JSONObject = encode(value.desired).put("expectedVersion", value.expectedVersion).put("mutationId", value.mutationId)
        .also {
            require(value.expectedVersion in 0 until MaxReadingVersion); ServerReadingNotesJson.uuid(value.mutationId)
            require(it.toString().toByteArray(Charsets.UTF_8).size <= 32768)
        }
    fun mutation(value: JSONObject): SourceFavoriteMutation {
        fields(value, setOf("providerId", "bookId", "expectedVersion", "mutationId", "deleted", "book"))
        return SourceFavoriteMutation(desired(value), ServerReadingNotesJson.number(value, "expectedVersion"), value.get("mutationId") as String)
            .also { encode(it) }
    }
    fun page(value: JSONObject, after: Long, limit: Int, until: Long?): SourceFavoritesPage {
        fields(value, setOf("items", "nextAfterRevision", "watermark", "hasMore"))
        val watermark = ServerReadingNotesJson.number(value, "watermark"); val next = ServerReadingNotesJson.number(value, "nextAfterRevision")
        val more = value.get("hasMore") as Boolean; val raw = value.getJSONArray("items")
        require(watermark >= after && next in after..watermark && (until == null || until == watermark) && raw.length() <= limit)
        val items = List(raw.length()) { item(raw.getJSONObject(it)) }
        require(items.all { it.version > 0 && it.changeRevision > after && it.changeRevision <= watermark })
        require(items.zipWithNext().all { (a, b) -> a.changeRevision < b.changeRevision })
        require(if (more) items.isNotEmpty() && next == items.last().changeRevision && next < watermark else next == watermark)
        require(items.isEmpty() || next >= items.last().changeRevision)
        return SourceFavoritesPage(items, next, watermark, more)
    }
}
