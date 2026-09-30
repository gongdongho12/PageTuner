package com.dongholab.pagetuner.server.preferences

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

@RestController
@RequestMapping("/api/v1/reader-preferences")
class ReaderPreferencesController(private val service: ReaderPreferencesService, json: ObjectMapper) {
    private val reader = json.readerFor(JsonNode::class.java)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val limits = ReaderPreferencesWriteLimits()

    @GetMapping
    fun get(principal: Principal) = noStore(service.get(principal.name))

    @PutMapping(consumes = ["application/json"])
    fun put(principal: Principal, @RequestBody bytes: ByteArray): ResponseEntity<ReaderPreferencesView> {
        limits.acquire(principal.name)
        return noStore(service.put(principal.name, parse(bytes)))
    }

    private fun parse(bytes: ByteArray): PutReaderPreferencesRequest {
        val node = try { reader.readValue<JsonNode>(bytes) } catch (_: Exception) { invalidPreferences() }
        if (!node.isObject || node.fieldNames().asSequence().toSet() != setOf("expectedVersion", "mutationId", "preferences")) invalidPreferences()
        val version = node["expectedVersion"]
        if (!version.isIntegralNumber || !version.canConvertToLong() || version.longValue() !in 0 until MAX_READER_PREFERENCES_VERSION) invalidPreferences()
        val mutation = node["mutationId"]
        if (!mutation.isTextual || !mutation.textValue().matches(UUID_PATTERN)) invalidPreferences()
        val value = node["preferences"]
        if (!value.isObject || value.fieldNames().asSequence().toSet() !=
            setOf("fontSize", "lineHeightPercent", "pageMargin", "touchDirection", "listMode")) invalidPreferences()
        fun integer(name: String): Int {
            val field = value[name]
            if (!field.isIntegralNumber || !field.canConvertToInt()) invalidPreferences()
            return field.intValue()
        }
        fun text(name: String): String {
            val field = value[name]
            if (!field.isTextual) invalidPreferences()
            return field.textValue()
        }
        val preferences = ReaderPreferences(integer("fontSize"), integer("lineHeightPercent"), integer("pageMargin"),
            text("touchDirection"), text("listMode")).also { it.validate() }
        return PutReaderPreferencesRequest(version.longValue(), UUID.fromString(mutation.textValue()), preferences)
    }

    @ExceptionHandler(ReaderPreferencesFailure::class)
    fun failure(error: ReaderPreferencesFailure): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.message.orEmpty())
        problem.setProperty("code", error.code)
        error.current?.let { problem.setProperty("current", it) }
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
            .also { response -> error.retryAfterSeconds?.let { response.header("Retry-After", it.toString()) } }.body(problem)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformed(): ResponseEntity<ProblemDetail> = failure(ReaderPreferencesFailure(
        "READER_PREFERENCES_INVALID", 400, "Invalid reader preferences request."))

    private fun <T> noStore(body: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

/** Per-process bounded write protection; database CAS remains authoritative. */
internal class ReaderPreferencesWriteLimits(private val nowMillis: () -> Long = System::currentTimeMillis) {
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

    private fun limited(seconds: Int): Nothing = throw ReaderPreferencesFailure("READER_PREFERENCES_LIMIT", 429,
        "Too many reader preferences updates. Retry after the indicated delay.", retryAfterSeconds = seconds)
}
