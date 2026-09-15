package com.dongholab.pagetuner.server.workflow

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import java.time.Instant
import java.util.UUID

data class WorkflowPage<T>(val items: List<T>, val page: Int, val size: Int, val totalItems: Long) {
    val totalPages: Int = ((totalItems + size - 1) / size).toInt()
    val hasNext: Boolean = page.toLong() + 1 < totalPages
}

data class ImportChapterRequest(val url: String, val bookUrl: String? = null)
data class SourceParagraph(val paragraphId: String, val ordinal: Int, val text: String)
data class UploadedChapterRequest(val bookId: String, val bookTitle: String, val chapterId: String, val chapterTitle: String,
    val sourceLanguage: String, val paragraphs: List<SourceParagraph>)
data class StoredChapter(
    val recordId: UUID,
    val providerId: String,
    val bookId: String,
    val bookTitle: String,
    val bookUrl: String,
    val chapterId: String,
    val chapterTitle: String,
    val chapterUrl: String,
    val sourceLanguage: String,
    val sourceRevision: String,
    val paragraphs: List<SourceParagraph>,
    val createdAt: Instant,
) {
    fun content(source: String = sourceLanguage): ChapterContent = ChapterContent(
        ChapterIdentity(BookIdentity(providerId, bookId), chapterId), chapterTitle, source,
        paragraphs.map { ContentParagraph(it.paragraphId, it.ordinal, it.text) },
    )
    fun summary() = ChapterSummary(recordId, providerId, bookId, bookTitle, bookUrl, chapterId, chapterTitle,
        chapterUrl, sourceLanguage, sourceRevision, paragraphs.size, createdAt)
}
data class ChapterSummary(
    val recordId: UUID, val providerId: String, val bookId: String, val bookTitle: String, val bookUrl: String,
    val chapterId: String, val chapterTitle: String, val chapterUrl: String, val sourceLanguage: String,
    val sourceRevision: String, val paragraphCount: Int, val createdAt: Instant,
)
/** Omitted defaults preserve the JSON used by existing job idempotency hashes. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
data class WorkflowGlossaryEntry(
    val source: String, val target: String, val kind: String? = null, val displayTerm: String? = null,
    val caseSensitive: Boolean? = null, val enabled: Boolean? = null,
)

// Deliberately not a data class: never generate a toString containing credentials.
class CreateTranslationJobRequest(
    val chapterRecordId: UUID,
    val providerKind: String,
    val targetLanguage: String,
    val idempotencyKey: UUID,
    val sourceLanguage: String? = null,
    val endpoint: String? = null,
    val model: String? = null,
    val apiKey: String? = null,
    val glossary: List<WorkflowGlossaryEntry> = emptyList(),
    val retryOf: UUID? = null,
) {
    override fun toString(): String = "CreateTranslationJobRequest(chapterRecordId=$chapterRecordId, credentials=REDACTED)"
}
data class JobConfiguration(
    val providerKind: String, val sourceLanguage: String, val targetLanguage: String,
    val endpoint: String, val model: String, val glossary: List<WorkflowGlossaryEntry>,
    val translationProviderId: String, val promptRevision: String, val glossaryRevision: String,
)
data class TranslationJobView(
    val jobId: UUID, val status: String, val chapterRecordId: UUID, val bookTitle: String, val chapterTitle: String,
    val providerKind: String, val targetLanguage: String, val completedParagraphs: Int, val totalParagraphs: Int,
    val translationRecordId: UUID?, val errorCode: String?, val errorMessage: String?, val createdAt: Instant, val updatedAt: Instant,
    val settings: TranslationRetrySettings,
) {
    val canRetry: Boolean = status in setOf("FAILED", "CANCELLED", "INTERRUPTED")
}

data class TranslationRetrySettings(val sourceLanguage: String, val targetLanguage: String, val endpoint: String,
    val model: String, val glossary: List<WorkflowGlossaryEntry>)
data class TranslationProviderInfo(
    val id: String, val displayName: String, val configured: Boolean, val requiresKey: Boolean,
    val defaultEndpoint: String, val defaultModel: String,
)
data class TranslationProviderList(val providers: List<TranslationProviderInfo>)

class WorkflowFailure(val code: String, val httpStatus: Int, message: String) : RuntimeException(message)
fun validateWorkflowPage(page: Int, size: Int) {
    require(page >= 0 && size in 1..50 && page.toLong() * size <= Int.MAX_VALUE) { "Invalid page or page size." }
}
