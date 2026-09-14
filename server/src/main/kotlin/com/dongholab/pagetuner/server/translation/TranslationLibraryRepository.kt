package com.dongholab.pagetuner.server.translation

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

/** Bounded library projections: source text and translated bodies never leave PostgreSQL. */
@Repository
class TranslationLibraryRepository(private val jdbc: JdbcTemplate) {
    fun count(userId: String): Long = requireNotNull(jdbc.queryForObject(
        """
            select count(*) from translation_artifact
            where user_id = ? and content_provider_id is not null and book_id is not null
        """.trimIndent(),
        Long::class.java,
        userId,
    ))

    fun list(userId: String, size: Int, offset: Long): List<TranslationSummary> = jdbc.query(
        """
            select id, content_provider_id, book_id, chapter_id, source_language, target_language,
                   translation_provider_id, model_id, prompt_revision, glossary_revision,
                   source_revision, artifact_id, revision, payload_hash, created_at,
                   book_title, chapter_title, jsonb_array_length(paragraphs_json::jsonb) as paragraph_count
            from (
                select id, content_provider_id, book_id, chapter_id, source_language, target_language,
                       translation_provider_id, model_id, prompt_revision, glossary_revision,
                       source_revision, artifact_id, revision, payload_hash, created_at, paragraphs_json, book_title, chapter_title
                from translation_artifact
                where user_id = ? and content_provider_id is not null and book_id is not null
                order by created_at desc, id desc
                limit ? offset ?
            ) as library_page
            order by created_at desc, id desc
        """.trimIndent(),
        PreparedStatementSetter { statement ->
            statement.setString(1, userId)
            statement.setInt(2, size)
            statement.setLong(3, offset)
        },
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
    )
}
