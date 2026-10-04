package com.dongholab.pagetuner.server.translation

import com.dongholab.pagetuner.core.model.library.LibraryFilter
import com.dongholab.pagetuner.server.organization.LibraryFilterSql
import com.dongholab.pagetuner.server.organization.LibraryOrganizationKind
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

/** Bounded library projections: source text and translated bodies never leave PostgreSQL. */
@Repository
class TranslationLibraryRepository(private val jdbc: JdbcTemplate) {
    fun count(userId: String, filter: LibraryFilter = LibraryFilter(), lookup: TranslationLookupFilter = TranslationLookupFilter()): Long {
        val query = LibraryFilterSql(filter, LibraryOrganizationKind.TRANSLATION)
        return requireNotNull(jdbc.queryForObject(
        """
            select count(*) from translation_artifact document ${query.join}
            where document.user_id = ? and document.content_provider_id is not null and document.book_id is not null
            ${query.predicates} ${lookup.predicates}
        """.trimIndent(),
        Long::class.java,
        *(listOf(userId) + query.arguments + lookup.arguments).toTypedArray(),
    ))
    }

    fun list(userId: String, size: Int, offset: Long, filter: LibraryFilter = LibraryFilter(), lookup: TranslationLookupFilter = TranslationLookupFilter()): List<TranslationSummary> {
        val query = LibraryFilterSql(filter, LibraryOrganizationKind.TRANSLATION)
        return jdbc.query(
        """
            select id, content_provider_id, book_id, chapter_id, source_language, target_language,
                   translation_provider_id, model_id, prompt_revision, glossary_revision,
                   source_revision, artifact_id, revision, payload_hash, created_at,
                   book_title, chapter_title, jsonb_array_length(paragraphs_json::jsonb) as paragraph_count
            from (
                select document.id, document.content_provider_id, document.book_id, document.chapter_id,
                       document.source_language, document.target_language, document.translation_provider_id,
                       document.model_id, document.prompt_revision, document.glossary_revision, document.source_revision,
                       document.artifact_id, document.revision, document.payload_hash, document.created_at,
                       document.paragraphs_json, document.book_title, document.chapter_title
                from translation_artifact document ${query.join}
                where document.user_id = ? and document.content_provider_id is not null and document.book_id is not null
                ${query.predicates} ${lookup.predicates}
                order by document.created_at desc, document.id desc
                limit ? offset ?
            ) as library_page
            order by created_at desc, id desc
        """.trimIndent(),
        RowMapper { row, _ ->
            TranslationSummary(
                recordId = row.getObject("id", UUID::class.java),
                contentProviderId = row.getString("content_provider_id"),
                bookId = row.getString("book_id"),
                chapterId = row.getString("chapter_id"),
                sourceLanguage = row.getString("source_language"),
                targetLanguage = row.getString("target_language"),
                translationProviderId = row.getString("translation_provider_id"),
                modelId = row.getString("model_id"),
                promptRevision = row.getString("prompt_revision"),
                glossaryRevision = row.getString("glossary_revision"),
                sourceRevision = row.getString("source_revision"),
                artifactId = row.getString("artifact_id"),
                revision = row.getString("revision"),
                payloadHash = row.getString("payload_hash"),
                createdAt = row.getTimestamp("created_at").toInstant(),
                paragraphCount = row.getInt("paragraph_count"),
                bookTitle = row.getString("book_title"),
                chapterTitle = row.getString("chapter_title"),
            )
        },
        *(listOf(userId) + query.arguments + lookup.arguments + listOf(size, offset)).toTypedArray(),
    )
    }
}
