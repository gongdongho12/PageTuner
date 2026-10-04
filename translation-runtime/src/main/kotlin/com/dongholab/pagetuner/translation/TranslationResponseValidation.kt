package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.TextSegment

/** Validate a complete batch before any caller publishes a checkpoint or cache entry. */
fun validateTranslationResponse(
    requested: List<TextSegment>,
    translated: List<TranslatedSegment>,
    providerName: String = "Translation",
) {
    val requestedIds = requested.map(TextSegment::id)
    require(requestedIds.all(String::isNotBlank) && requestedIds.distinct().size == requestedIds.size) {
        "Requested segment IDs must be nonblank and unique."
    }
    val translatedIds = translated.map(TranslatedSegment::segmentId)
    if (translatedIds.distinct().size != translatedIds.size ||
        translatedIds.size != requestedIds.size || translatedIds.toSet() != requestedIds.toSet()
    ) {
        throw providerResponseFormatException(providerName, "Response IDs did not match the complete requested batch.")
    }
    if (translated.any { it.translatedText.isBlank() }) {
        throw providerResponseFormatException(providerName, "Translated text must not be blank.")
    }
}
