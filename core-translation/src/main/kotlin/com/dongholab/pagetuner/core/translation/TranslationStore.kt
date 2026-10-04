package com.dongholab.pagetuner.core.translation

/**
 * A complete saved translation, independent of HTTP, Android storage and database entities.
 *
 * [createdAt] preserves the server's ISO-8601 timestamp without requiring a platform date API.
 * The artifact includes all source and translation settings needed to validate a restore.
 */
data class StoredTranslation(
    val recordId: String,
    val artifact: TranslationArtifact,
    val createdAt: String,
) {
    init {
        require(recordId.isNotBlank()) { "recordId must not be blank." }
        require(createdAt.isNotBlank()) { "createdAt must not be blank." }
    }
}

data class TranslationSaveResult(
    val translation: StoredTranslation,
    val created: Boolean,
)

/**
 * Explicit publication and retrieval of complete paragraph translations.
 *
 * Implementations scope records to their authenticated user, retain revisions, and reuse
 * repeated saves of the same artifact/revision. They own credentials, transport, persistence,
 * error reporting and execution context. A failed lookup must not fabricate an empty artifact.
 * Platform adapters must validate remote identities and hashes before returning a record.
 */
interface TranslationStore {
    suspend fun save(artifact: TranslationArtifact): TranslationSaveResult

    suspend fun get(recordId: String): StoredTranslation
}
