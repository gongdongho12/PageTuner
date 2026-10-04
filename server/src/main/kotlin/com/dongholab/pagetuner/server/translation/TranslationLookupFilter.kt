package com.dongholab.pagetuner.server.translation

/** Exact original identity filters; never split the legacy providerBookId encoding. */
data class TranslationLookupFilter(
    val contentProviderId: String? = null,
    val bookId: String? = null,
    val chapterId: String? = null,
    val sourceRevision: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
) {
    fun validate() {
        require((contentProviderId == null) == (bookId == null)) {
            "contentProviderId and bookId must be supplied together."
        }
        require(listOfNotNull(contentProviderId, bookId, chapterId, sourceRevision, sourceLanguage, targetLanguage)
            .all { it.isNotBlank() && it.length <= 2000 }) { "Invalid translation identity filter." }
    }

    private val fields get() = listOf(
        "content_provider_id" to contentProviderId, "book_id" to bookId, "chapter_id" to chapterId,
        "source_revision" to sourceRevision, "source_language" to sourceLanguage, "target_language" to targetLanguage,
    ).filter { it.second != null }
    val predicates: String get() = fields.joinToString(" ") { "and document.${it.first} = ?" }
    val arguments: List<String> get() = fields.map { requireNotNull(it.second) }
}
