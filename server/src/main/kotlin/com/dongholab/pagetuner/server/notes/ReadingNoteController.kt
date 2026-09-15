package com.dongholab.pagetuner.server.notes

import com.dongholab.pagetuner.server.progress.MAX_PROGRESS_VERSION
import com.dongholab.pagetuner.server.progress.ReadingProgressAnchor
import com.dongholab.pagetuner.server.progress.ReadingProgressKind
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.Principal
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.*
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestController
@RequestMapping("/api/v1/reading-notes")
class ReadingNoteController(private val service: ReadingNoteService, json: ObjectMapper) {
    private val reader = json.readerFor(JsonNode::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val limits = ReadingNoteWriteLimits()

    @GetMapping("/{kind}/{recordId}")
    fun changes(principal: Principal, @PathVariable kind: ReadingProgressKind, @PathVariable recordId: String,
                @RequestParam(defaultValue = "0") afterRevision: Long, @RequestParam(required = false) untilRevision: Long?,
                @RequestParam(defaultValue = "50") limit: Int) = noStore(service.changes(principal.name, kind, uuid(recordId), afterRevision, untilRevision, limit))

    @PutMapping("/{kind}/{recordId}/{noteId}", consumes = ["application/json"])
    fun put(principal: Principal, @PathVariable kind: ReadingProgressKind, @PathVariable recordId: String,
            @PathVariable noteId: String, @RequestBody bytes: ByteArray): ResponseEntity<ReadingNoteItem> {
        limits.acquire(principal.name)
        return noStore(service.put(principal.name, kind, uuid(recordId), uuid(noteId), parse(bytes)))
    }

    private fun parse(bytes: ByteArray): PutReadingNoteRequest {
        val node = try { reader.readValue<JsonNode>(bytes) } catch (_: Exception) { invalidNote() }
        fields(node, "expectedVersion", "mutationId", "deleted", "note")
        val version = integer(node["expectedVersion"], MAX_PROGRESS_VERSION - 1)
        val mutation = uuid(string(node["mutationId"]))
        val deleted = node["deleted"]
        if (!deleted.isBoolean || deleted.booleanValue() != node["note"].isNull) invalidNote()
        val note = if (deleted.booleanValue()) null else {
            val value = node["note"]
            fields(value, "kind", "title", "text", "anchor", "range", "createdAt")
            val kind = try { ReadingNoteKind.valueOf(string(value["kind"])) } catch (_: Exception) { invalidNote() }
            val range = if (value["range"].isNull) null else {
                fields(value["range"], "start", "end")
                ReadingNoteRange(anchor(value["range"]["start"]), anchor(value["range"]["end"]))
            }
            val time = string(value["createdAt"])
            if (!time.matches(TIME_PATTERN) || time.take(4).toInt() == 0) invalidNote()
            val instant = try { OffsetDateTime.parse(time).toInstant() } catch (_: Exception) { invalidNote() }
            ReadingNoteInput(kind, string(value["title"]), string(value["text"]), anchor(value["anchor"]), range, instant)
        }
        return PutReadingNoteRequest(version, mutation, deleted.booleanValue(), note)
    }

    private fun fields(node: JsonNode, vararg required: String) {
        if (!node.isObject || node.fieldNames().asSequence().toSet() != required.toSet()) invalidNote()
    }
    private fun string(node: JsonNode): String {
        if (!node.isTextual || !validNoteUnicode(node.textValue())) invalidNote()
        return node.textValue()
    }
    private fun integer(node: JsonNode, maximum: Long): Long {
        if (!node.isIntegralNumber || !node.canConvertToLong() || node.longValue() !in 0..maximum) invalidNote()
        return node.longValue()
    }
    private fun anchor(node: JsonNode): ReadingProgressAnchor {
        fields(node, "paragraphId", "characterOffset")
        return ReadingProgressAnchor(string(node["paragraphId"]), integer(node["characterOffset"], Int.MAX_VALUE.toLong()).toInt())
    }
    private fun uuid(value: String): UUID {
        if (!value.matches(UUID_PATTERN)) invalidNote()
        return UUID.fromString(value)
    }

    @ExceptionHandler(ReadingNoteFailure::class)
    fun failure(error: ReadingNoteFailure): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.message.orEmpty())
        problem.setProperty("code", error.code)
        error.current?.let { problem.setProperty("current", it) }
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
            .also { response -> error.retryAfterSeconds?.let { response.header("Retry-After", it.toString()) } }.body(problem)
    }
    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun malformed() = failure(ReadingNoteFailure("READING_NOTE_INVALID", 400, "Invalid reading note request."))
    private fun <T> noStore(body: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val TIME_PATTERN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")
    }
}

internal class ReadingNoteWriteLimits(private val nowMillis: () -> Long = System::currentTimeMillis) {
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
    private fun limited(seconds: Int): Nothing = throw ReadingNoteFailure("READING_NOTE_LIMIT", 429,
        "Too many note updates. Retry after the indicated delay.", retryAfterSeconds = seconds)
}
