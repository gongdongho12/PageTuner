package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.TextSegment

/**
 * Builds provider requests across page boundaries while keeping each segment intact.
 * A single oversized segment is sent alone because splitting it would break cache identity.
 */
object TranslationRequestBatcher {
    const val DefaultMaxSegments: Int = 24
    const val DefaultMaxCharacters: Int = 24_000

    fun batch(
        segments: List<TextSegment>,
        maxSegments: Int = DefaultMaxSegments,
        maxCharacters: Int = DefaultMaxCharacters,
    ): List<List<TextSegment>> {
        require(maxSegments > 0) { "maxSegments must be positive." }
        require(maxCharacters > 0) { "maxCharacters must be positive." }
        if (segments.isEmpty()) return emptyList()

        val batches = mutableListOf<List<TextSegment>>()
        var current = mutableListOf<TextSegment>()
        var currentCharacters = 0

        fun flush() {
            if (current.isNotEmpty()) {
                batches += current
                current = mutableListOf()
                currentCharacters = 0
            }
        }

        segments.forEach { segment ->
            val segmentCharacters = segment.text.length
            val exceedsSegmentLimit = current.size >= maxSegments
            val exceedsCharacterLimit = current.isNotEmpty() &&
                currentCharacters + segmentCharacters > maxCharacters
            if (exceedsSegmentLimit || exceedsCharacterLimit) flush()
            current += segment
            currentCharacters += segmentCharacters
        }
        flush()
        return batches
    }
}
