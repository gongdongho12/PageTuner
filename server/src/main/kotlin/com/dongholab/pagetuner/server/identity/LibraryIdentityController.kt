package com.dongholab.pagetuner.server.identity

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import com.dongholab.pagetuner.server.translation.*
import com.dongholab.pagetuner.server.workflow.SourceChapterStore
import com.dongholab.pagetuner.server.workflow.SourceChapterMetadataUnavailable
import com.dongholab.pagetuner.server.workflow.WorkflowFailure
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.Principal
import java.util.UUID
import org.json.JSONObject
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

data class VerifyLibraryIdentityRequest(val kind: DocumentIdentityKind, val recordId: UUID, val identity: DocumentIdentity)
data class VerifyLibraryIdentityResponse(val kind: DocumentIdentityKind, val recordId: UUID, val verified: Boolean, val identity: JsonNode)
class LibraryIdentityFailure(val code: String, val status: Int, message: String) : RuntimeException(message)

/** Verification deliberately creates no binding, document, sync state or authorization capability. */
@Service
class LibraryIdentityService(private val sources: SourceChapterStore, private val translations: TranslationApplicationService) {
    @Transactional(readOnly = true)
    fun verify(user: String, request: VerifyLibraryIdentityRequest): DocumentIdentity {
        try { DocumentIdentities.validate(request.identity); require(request.kind == request.identity.kind) }
        catch (_: IllegalArgumentException) { throw LibraryIdentityFailure("LIBRARY_IDENTITY_INVALID", 400, "Invalid document identity.") }
        val actual = try {
            when (request.kind) {
                DocumentIdentityKind.ORIGINAL -> DocumentIdentities.original(sources.get(user, request.recordId).content())
                DocumentIdentityKind.TRANSLATION -> translations.get(user, request.recordId).let { stored ->
                    DocumentIdentities.translation(TranslationArtifact(ChapterIdentity(BookIdentity(stored.contentProviderId, stored.bookId), stored.chapterId),
                        stored.sourceRevision, stored.sourceLanguage, stored.targetLanguage, stored.translationProviderId,
                        stored.modelId, stored.promptRevision, stored.glossaryRevision, stored.paragraphs.map { TranslatedParagraph(it.paragraphId, it.text) }))
                }
            }
        } catch (error: WorkflowFailure) {
            if (error.httpStatus == 404) throw notFound()
            throw error
        } catch (_: TranslationNotFound) { throw notFound() }
        catch (_: SourceChapterMetadataUnavailable) { throw unavailable() }
        catch (_: TranslationMetadataUnavailable) { throw unavailable() }
        catch (_: com.fasterxml.jackson.core.JsonProcessingException) { throw unavailable() }
        catch (_: IllegalArgumentException) { throw unavailable() }
        catch (_: IllegalStateException) { throw unavailable() }
        if (request.identity != actual) throw LibraryIdentityFailure("LIBRARY_IDENTITY_MISMATCH", 409,
            "The document does not match this owned server record.")
        return actual
    }
    private fun notFound() = LibraryIdentityFailure("LIBRARY_IDENTITY_NOT_FOUND", 404, "The server document was not found.")
    private fun unavailable() = LibraryIdentityFailure("LIBRARY_IDENTITY_UNAVAILABLE", 409, "This server record has no verifiable complete document identity.")
}

@RestController
@RequestMapping("/api/v1/library-identity")
class LibraryIdentityController(private val service: LibraryIdentityService, private val json: ObjectMapper) {
    private val reader = json.readerFor(JsonNode::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    @PostMapping("/verify", consumes = ["application/json"])
    fun verify(principal: Principal, @RequestBody bytes: ByteArray): ResponseEntity<VerifyLibraryIdentityResponse> {
        if (bytes.size > 64 * 1024) throw LibraryIdentityFailure("LIBRARY_IDENTITY_INVALID", 413, "The verification body exceeds the size limit.")
        val request = try {
            val node = reader.readValue<JsonNode>(bytes)
            require(node.isObject && node.fieldNames().asSequence().toSet() == setOf("kind", "recordId", "identity"))
            require(node["kind"].isTextual && node["recordId"].isTextual && node["identity"].isObject)
            val record = node["recordId"].textValue()
            require(record.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
            // Keep the integer spelling strict before the Android-compatible JSON codec sees it.
            require(node["identity"]["version"]?.isIntegralNumber == true && node["identity"]["version"].intValue() == 1 && node["identity"]["version"].canConvertToInt())
            VerifyLibraryIdentityRequest(DocumentIdentityKind.valueOf(node["kind"].textValue()), UUID.fromString(record),
                DocumentIdentityJson.decode(JSONObject(node["identity"].toString())))
        } catch (_: Exception) { throw LibraryIdentityFailure("LIBRARY_IDENTITY_INVALID", 400, "Invalid document identity request.") }
        val identity = service.verify(principal.name, request)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(VerifyLibraryIdentityResponse(request.kind, request.recordId, true,
            json.readTree(DocumentIdentityJson.encode(identity).toString())))
    }

    @ExceptionHandler(LibraryIdentityFailure::class)
    fun failure(error: LibraryIdentityFailure): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.message.orEmpty())
        problem.setProperty("code", error.code)
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore()).body(problem)
    }
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformed() = failure(LibraryIdentityFailure("LIBRARY_IDENTITY_INVALID", 400, "Invalid document identity request."))
}
