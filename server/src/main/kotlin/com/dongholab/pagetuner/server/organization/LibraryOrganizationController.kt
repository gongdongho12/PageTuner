package com.dongholab.pagetuner.server.organization

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
@RequestMapping("/api/v1/library-organization/{kind}/{recordId}")
class LibraryOrganizationController(private val service: LibraryOrganizationService, json: ObjectMapper) {
    private val reader = json.readerFor(JsonNode::class.java)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val limits = LibraryOrganizationWriteLimits()

    @GetMapping
    fun get(principal: Principal, @PathVariable kind: LibraryOrganizationKind, @PathVariable recordId: String) =
        noStore(service.get(principal.name, kind, uuid(recordId)))

    @PutMapping(consumes = ["application/json"])
    fun put(principal: Principal, @PathVariable kind: LibraryOrganizationKind, @PathVariable recordId: String,
            @RequestBody bytes: ByteArray): ResponseEntity<LibraryOrganizationView> {
        limits.acquire(principal.name)
        return noStore(service.put(principal.name, kind, uuid(recordId), parse(bytes)))
    }

    private fun parse(bytes: ByteArray): PutLibraryOrganizationRequest {
        val node = try { reader.readValue<JsonNode>(bytes) } catch (_: Exception) { invalidOrganization() }
        if (!node.isObject || node.fieldNames().asSequence().toSet() != setOf("expectedVersion", "mutationId", "organization")) invalidOrganization()
        val version = node["expectedVersion"]
        if (!version.isIntegralNumber || !version.canConvertToLong() || version.longValue() !in 0 until MAX_LIBRARY_ORGANIZATION_VERSION) invalidOrganization()
        val mutation = node["mutationId"]
        if (!mutation.isTextual) invalidOrganization()
        val value = node["organization"]
        if (!value.isObject || value.fieldNames().asSequence().toSet() != setOf("folder", "tags", "favorite")) invalidOrganization()
        val folder = value["folder"]; val tags = value["tags"]; val favorite = value["favorite"]
        if (!folder.isTextual || !tags.isArray || tags.any { !it.isTextual } || !favorite.isBoolean) invalidOrganization()
        val organization = LibraryOrganization(folder.textValue(), tags.map { it.textValue() }, favorite.booleanValue()).also { it.validate() }
        return PutLibraryOrganizationRequest(version.longValue(), uuid(mutation.textValue()), organization)
    }

    private fun uuid(value: String): UUID {
        if (!value.matches(UUID_PATTERN)) invalidOrganization()
        return UUID.fromString(value)
    }

    @ExceptionHandler(LibraryOrganizationFailure::class)
    fun failure(error: LibraryOrganizationFailure): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.message.orEmpty())
        problem.setProperty("code", error.code)
        error.current?.let { problem.setProperty("current", it) }
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
            .also { response -> error.retryAfterSeconds?.let { response.header("Retry-After", it.toString()) } }.body(problem)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun malformed() = failure(LibraryOrganizationFailure("LIBRARY_ORGANIZATION_INVALID", 400, "Invalid library organization request."))

    private fun <T> noStore(body: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

/** One bounded per-account write limit shared by all documents in this process. */
internal class LibraryOrganizationWriteLimits(private val nowMillis: () -> Long = System::currentTimeMillis) {
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
    private fun limited(seconds: Int): Nothing = throw LibraryOrganizationFailure("LIBRARY_ORGANIZATION_LIMIT", 429,
        "Too many library organization updates. Retry after the indicated delay.", retryAfterSeconds = seconds)
}
