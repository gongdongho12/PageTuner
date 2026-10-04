package com.dongholab.pagetuner.server.library

import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.time.Instant
import java.util.UUID

/** Stable envelope independent of Spring Data's internal Page serialization. */
data class PageResponse<T>(val items: List<T>, val page: Int, val size: Int, val totalItems: Long) {
    val totalPages: Long get() = (totalItems + size - 1) / size
}

data class ImportBookRequest(
    @field:NotBlank @field:Size(max = 500) val title: String,
    @field:Size(max = 500) val author: String = "",
    @field:NotBlank @field:Size(max = 24) val sourceLanguage: String,
    @field:Size(min = 1, max = 500) @field:Valid val chapters: List<ImportChapterRequest>,
)
data class ImportChapterRequest(
    @field:NotBlank @field:Size(max = 500) val title: String,
    @field:Size(min = 1, max = 10000) @field:Valid val paragraphs: List<ParagraphRequest>,
)
data class ParagraphRequest(
    @field:NotBlank @field:Size(max = 240) val paragraphId: String,
    @field:NotBlank @field:Size(max = 100000) val text: String,
)
data class BookResponse(
    val id: UUID, val title: String, val author: String, val sourceLanguage: String,
    val chapterCount: Int, val createdAt: Instant,
)
data class ChapterSummary(val id: UUID, val ordinal: Int, val title: String, val sourceRevision: String)
data class ChapterResponse(
    val id: UUID, val bookId: UUID, val ordinal: Int, val title: String,
    val contentProviderId: String = "library", val sourceLanguage: String,
    val sourceRevision: String, val paragraphs: List<ParagraphRequest>,
)
data class AnchorRequest(
    val chapterId: UUID,
    @field:NotBlank @field:Size(max = 240) val paragraphId: String,
    @field:Min(0) val characterOffset: Int = 0,
)
data class SaveProgressRequest(@field:Valid val anchor: AnchorRequest, @field:Min(0) val version: Long)
data class ProgressResponse(val anchor: AnchorRequest?, val version: Long, val updatedAt: Instant?)
data class SaveBookmarkRequest(
    @field:Valid val anchor: AnchorRequest,
    @field:Size(max = 2000) val note: String = "",
)
data class BookmarkResponse(val id: UUID, val anchor: AnchorRequest, val note: String, val createdAt: Instant)
