package com.dongholab.pagetuner.server.progress

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
@RequestMapping("/api/v1/reading-progress")
class ReadingProgressController(private val service: ReadingProgressService, json: ObjectMapper) {
    private val reader = json.readerFor(JsonNode::class.java)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val limits = ReadingProgressWriteLimits()

    @GetMapping("/{kind}/{recordId}")
    fun get(principal: Principal, @PathVariable kind: ReadingProgressKind, @PathVariable recordId: String) =
        noStore(service.get(principal.name, kind, parseUuid(recordId)))

    @PutMapping("/{kind}/{recordId}", consumes = ["application/json"])
    fun put(principal: Principal, @PathVariable kind: ReadingProgressKind, @PathVariable recordId: String,
            @RequestBody bytes: ByteArray): ResponseEntity<ReadingProgressView> {
        limits.acquire(principal.name)
        return noStore(service.put(principal.name, kind, parseUuid(recordId), parse(bytes)))
    }

    private fun parse(bytes: ByteArray): PutReadingProgressRequest {
        val node = try { reader.readValue<JsonNode>(bytes) } catch (_: Exception) { invalidProgress() }
        if (!node.isObject || node.fieldNames().asSequence().toSet() != setOf("expectedVersion", "mutationId", "anchor")) invalidProgress()
        val version = node["expectedVersion"]
        if (!version.isIntegralNumber || !version.canConvertToLong() || version.longValue() !in 0 until MAX_PROGRESS_VERSION) invalidProgress()
        val mutation = node["mutationId"]
        if (!mutation.isTextual || !mutation.textValue().matches(UUID_PATTERN)) invalidProgress()
        val anchor = node["anchor"]
        if (!anchor.isObject || anchor.fieldNames().asSequence().toSet() != setOf("paragraphId", "characterOffset")) invalidProgress()
        val id = anchor["paragraphId"]
        val offset = anchor["characterOffset"]
        if (!id.isTextual || !offset.isIntegralNumber || !offset.canConvertToInt()) invalidProgress()
        return PutReadingProgressRequest(version.longValue(), UUID.fromString(mutation.textValue()),
            ReadingProgressAnchor(id.textValue(), offset.intValue()))
    }

    @ExceptionHandler(ReadingProgressFailure::class)
    fun failure(error: ReadingProgressFailure): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.message.orEmpty())
        problem.setProperty("code", error.code)
        error.current?.let { problem.setProperty("current", it) }
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
            .also { response -> error.retryAfterSeconds?.let { response.header("Retry-After", it.toString()) } }.body(problem)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun malformed(): ResponseEntity<ProblemDetail> = failure(ReadingProgressFailure("READING_PROGRESS_INVALID", 400, "Invalid reading position request."))

    private fun <T> noStore(body: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun parseUuid(value: String): UUID {
        if (!value.matches(UUID_PATTERN)) invalidProgress()
        return UUID.fromString(value)
    }

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

/** Per-process, bounded abuse protection. CAS and ownership remain database-enforced. */
internal class ReadingProgressWriteLimits(private val nowMillis: () -> Long = System::currentTimeMillis) {
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

    private fun limited(seconds: Int): Nothing = throw ReadingProgressFailure("READING_PROGRESS_LIMIT", 429,
        "Too many reading position updates. Retry after the indicated delay.", retryAfterSeconds = seconds)
}
