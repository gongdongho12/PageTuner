package com.dongholab.pagetuner.server.glossary

import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncEntry
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncKind
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
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/book-glossary")
class BookGlossaryController(private val service: BookGlossaryService, json: ObjectMapper) {
    private val reader = json.readerFor(JsonNode::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val limits = BookGlossaryWriteLimits()

    @GetMapping
    fun get(principal: Principal, @RequestParam params: MultiValueMap<String, String>): ResponseEntity<BookGlossaryView> {
        if (params.keys != setOf("providerId", "bookId", "targetLanguage") || params.values.any { it.size != 1 }) invalidGlossary()
        return noStore(service.get(principal.name, BookGlossarySyncIdentity(params.getFirst("providerId")!!, params.getFirst("bookId")!!, params.getFirst("targetLanguage")!!)))
    }
    /** POST keeps long original IDs out of HTTP request-line limits; this remains a read-only query. */
    @PostMapping("/query", consumes = ["application/json"])
    fun query(principal: Principal, @RequestBody bytes: ByteArray): ResponseEntity<BookGlossaryView> {
        if (bytes.size > 16 * 1024) throw BookGlossaryFailure("BOOK_GLOSSARY_INVALID", 413, "The glossary query body exceeds the size limit.")
        val node = try { reader.readValue<JsonNode>(bytes) } catch (_: Exception) { invalidGlossary() }
        fields(node, "providerId", "bookId", "targetLanguage")
        return noStore(service.get(principal.name, BookGlossarySyncIdentity(string(node["providerId"]), string(node["bookId"]), string(node["targetLanguage"]))))
    }
    @PutMapping(consumes = ["application/json"])
    fun put(principal: Principal, @RequestBody bytes: ByteArray): ResponseEntity<BookGlossaryView> {
        limits.acquire(principal.name)
        return noStore(service.put(principal.name, parse(bytes)))
    }
    private fun parse(bytes: ByteArray): PutBookGlossaryRequest {
        if (bytes.size > 1024 * 1024) throw BookGlossaryFailure("BOOK_GLOSSARY_INVALID", 413, "The glossary request body exceeds the size limit.")
        val node = try { reader.readValue<JsonNode>(bytes) } catch (_: Exception) { invalidGlossary() }
        fields(node, "providerId", "bookId", "targetLanguage", "expectedVersion", "mutationId", "entries")
        val version = node["expectedVersion"]
        if (!version.isIntegralNumber || !version.canConvertToLong() || version.longValue() !in 0 until MAX_BOOK_GLOSSARY_VERSION) invalidGlossary()
        val mutation = string(node["mutationId"])
        if (!UUID_PATTERN.matches(mutation)) invalidGlossary()
        val items = node["entries"]
        val entries = if (items.isNull) null else {
            if (!items.isArray || items.size() > 500) invalidGlossary()
            items.map { entry ->
                fields(entry, "id", "sourceTerm", "translatedTerm", "displayTerm", "kind", "caseSensitive", "enabled")
                val kind = try { BookGlossarySyncKind.valueOf(string(entry["kind"])) } catch (_: IllegalArgumentException) { invalidGlossary() }
                if (!entry["caseSensitive"].isBoolean || !entry["enabled"].isBoolean) invalidGlossary()
                BookGlossarySyncEntry(string(entry["id"]), string(entry["sourceTerm"]), string(entry["translatedTerm"]),
                    string(entry["displayTerm"]), kind, entry["caseSensitive"].booleanValue(), entry["enabled"].booleanValue())
            }
        }
        return PutBookGlossaryRequest(string(node["providerId"]), string(node["bookId"]), string(node["targetLanguage"]), version.longValue(), UUID.fromString(mutation), entries)
    }
    private fun fields(node: JsonNode, vararg expected: String) {
        if (!node.isObject || node.fieldNames().asSequence().toSet() != expected.toSet()) invalidGlossary()
    }
    private fun string(node: JsonNode): String { if (!node.isTextual) invalidGlossary(); return node.textValue() }

    @ExceptionHandler(BookGlossaryFailure::class)
    fun failure(error: BookGlossaryFailure): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.message.orEmpty())
        problem.setProperty("code", error.code); error.current?.let { problem.setProperty("current", it) }
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
            .also { response -> error.retryAfterSeconds?.let { response.header("Retry-After", it.toString()) } }.body(problem)
    }
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformed() = failure(BookGlossaryFailure("BOOK_GLOSSARY_INVALID", 400, "Invalid book glossary request."))
    private fun <T> noStore(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
    companion object { private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") }
}

internal class BookGlossaryWriteLimits(private val nowMillis: () -> Long = System::currentTimeMillis) {
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
    private fun limited(seconds: Int): Nothing = throw BookGlossaryFailure("BOOK_GLOSSARY_LIMIT", 429,
        "Too many glossary updates. Retry after the indicated delay.", retryAfterSeconds = seconds)
}
