package com.dongholab.pagetuner.core.translation

import com.dongholab.pagetuner.core.content.StableContentHash

data class TranslatableField(val id: String, val text: String)

data class ContentTranslationRequest(
    val namespace: String,
    val title: String,
    val fields: List<TranslatableField>,
)

data class ContentTranslationResult(
    val values: Map<String, String>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val providerId: String,
    val completedFromCache: Boolean,
)

data class ContentTranslationProgress(
    val completedSegments: Int,
    val totalSegments: Int,
    val status: String,
    val currentText: String,
) {
    val fraction: Float
        get() = if (totalSegments == 0) 1f else completedSegments.toFloat() / totalSegments
}

data class TranslationLanguages(val source: String, val target: String) {
    init {
        require(source.isNotBlank() && target.isNotBlank()) { "Translation languages must not be blank." }
    }
}

/** Platform adapters own credentials, provider selection, storage and dispatchers. */
fun interface ContentTranslationPort {
    suspend fun translate(
        request: ContentTranslationRequest,
        languages: TranslationLanguages,
        onProgress: suspend (ContentTranslationProgress) -> Unit,
    ): ContentTranslationResult
}

data class TranslationFieldSegment(val id: String, val text: String, val ordinal: Int)

data class TranslationFieldPlan(
    val documentId: String,
    val segments: List<TranslationFieldSegment>,
    val fieldSegments: Map<String, List<String>>,
)

/** Retains Android v1 cache identities, including the 400-character chunk boundary. */
object TranslationFieldSegmenter {
    fun create(request: ContentTranslationRequest): TranslationFieldPlan {
        require(request.namespace.isNotBlank()) { "Translation namespace cannot be blank." }
        val activeFields = request.fields.filter { it.text.isNotBlank() }
        require(activeFields.map { it.id }.distinct().size == activeFields.size) {
            "Translation field IDs must be unique within a request."
        }
        val documentId = StableContentHash.sha256("content-translation:${request.namespace}")
        val fieldSegments = linkedMapOf<String, List<String>>()
        val segments = buildList {
            activeFields.forEach { field ->
                require(field.id.isNotBlank()) { "Translation field ID cannot be blank." }
                fieldSegments[field.id] = field.text.chunked(400).mapIndexed { index, text ->
                    val id = StableContentHash.sha256("$documentId:${field.id}:$index:$text").take(24)
                    add(TranslationFieldSegment(id, text, size))
                    id
                }
            }
        }
        return TranslationFieldPlan(documentId, segments, fieldSegments)
    }
}
