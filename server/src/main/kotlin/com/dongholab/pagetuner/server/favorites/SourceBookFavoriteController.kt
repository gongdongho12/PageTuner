package com.dongholab.pagetuner.server.favorites

import com.dongholab.pagetuner.core.model.library.SourceBookFavoriteMetadata
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.Principal
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.*
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestController
@RequestMapping("/api/v1/source-book-favorites")
class SourceBookFavoriteController(private val service: SourceBookFavoriteService, json: ObjectMapper) {
    private val reader = json.readerFor(JsonNode::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val limits = SourceBookFavoriteWriteLimits()

    @GetMapping
    fun changes(principal: Principal, @RequestParam(defaultValue = "0") afterRevision: Long,
                @RequestParam(required = false) untilRevision: Long?, @RequestParam(defaultValue = "50") limit: Int) =
        noStore(service.changes(principal.name, afterRevision, untilRevision, limit))

    @PutMapping(consumes = ["application/json"])
    fun put(principal: Principal, @RequestBody bytes: ByteArray): ResponseEntity<SourceBookFavoriteItem> {
        limits.acquire(principal.name)
        return noStore(service.put(principal.name, parse(bytes)))
    }

    private fun parse(bytes: ByteArray): PutSourceBookFavoriteRequest {
        val node = try { reader.readValue<JsonNode>(bytes) } catch (_: Exception) { invalidFavorite() }
        fields(node, "providerId", "bookId", "expectedVersion", "mutationId", "deleted", "book")
        val version = node["expectedVersion"]
        if (!version.isIntegralNumber || !version.canConvertToLong() || version.longValue() !in 0 until MAX_FAVORITE_REVISION) invalidFavorite()
        val mutation = string(node["mutationId"])
        if (!mutation.matches(UUID_PATTERN)) invalidFavorite()
        val deleted = node["deleted"]
        if (!deleted.isBoolean || deleted.booleanValue() != node["book"].isNull) invalidFavorite()
        val book = if (deleted.booleanValue()) null else {
            val value = node["book"]
            fields(value, "title", "authors", "language", "url")
            val authors = value["authors"]
            if (!authors.isArray || authors.size() > 20) invalidFavorite()
            SourceBookFavoriteMetadata(string(value["title"]), authors.map(::string), string(value["language"]), string(value["url"]))
        }
        return PutSourceBookFavoriteRequest(string(node["providerId"]), string(node["bookId"]), version.longValue(),
            UUID.fromString(mutation), deleted.booleanValue(), book)
    }

    private fun fields(node: JsonNode, vararg required: String) {
        if (!node.isObject || node.fieldNames().asSequence().toSet() != required.toSet()) invalidFavorite()
    }
    private fun string(node: JsonNode): String {
        if (!node.isTextual) invalidFavorite()
        return node.textValue()
    }

    @ExceptionHandler(SourceBookFavoriteFailure::class)
    fun failure(error: SourceBookFavoriteFailure): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.message.orEmpty())
        problem.setProperty("code", error.code)
        error.current?.let { problem.setProperty("current", it) }
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
            .also { response -> error.retryAfterSeconds?.let { response.header("Retry-After", it.toString()) } }.body(problem)
    }
    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun malformed() = failure(SourceBookFavoriteFailure("SOURCE_BOOK_FAVORITE_INVALID", 400, "Invalid source book favorite request."))
    private fun <T> noStore(body: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

internal class SourceBookFavoriteWriteLimits(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private data class Window(val start: Long, var writes: Int)
    private val windows = mutableMapOf<String, Window>()
    @Synchronized fun acquire(user: String) {
        val now = nowMillis()
        windows.entries.removeIf { now - it.value.start >= 60_000 }
        val window = windows[user] ?: run {
            if (windows.size >= 10_000) limited(60)
            Window(now, 0).also { windows[user] = it }
        }
        if (window.writes >= 120) limited(((60_000 - (now - window.start)).coerceAtLeast(1) + 999).div(1000).toInt())
        window.writes++
    }
    private fun limited(seconds: Int): Nothing = throw SourceBookFavoriteFailure("SOURCE_BOOK_FAVORITE_LIMIT", 429,
        "Too many favorite updates. Retry after the indicated delay.", retryAfterSeconds = seconds)
}
