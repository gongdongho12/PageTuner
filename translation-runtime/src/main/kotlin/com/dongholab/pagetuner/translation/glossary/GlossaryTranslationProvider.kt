package com.dongholab.pagetuner.translation.glossary

import com.dongholab.pagetuner.translation.TranslatedSegment
import com.dongholab.pagetuner.translation.TranslationProvider
import com.dongholab.pagetuner.translation.TranslationRequest

/** Provider decorator that protects book-specific names before translation and restores them after it. */
class GlossaryTranslationProvider(
    private val delegate: TranslationProvider,
    glossary: BookGlossary,
) : TranslationProvider {
    private val entries = glossary.activeEntries

    override val id: String = "${delegate.id}:glossary-${glossary.translationFingerprint}"

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        val (protectedSegments, replacementsBySegment) = GlossaryTextProcessor.protectSegments(
            request.segments,
            entries,
        )
        return delegate.translate(request.copy(segments = protectedSegments)).map { segment ->
            segment.copy(
                translatedText = GlossaryTextProcessor.restore(
                    segment.translatedText,
                    replacementsBySegment[segment.segmentId].orEmpty(),
                ),
            )
        }
    }
}
