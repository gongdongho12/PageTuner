package com.dongholab.pagetuner.translation

enum class TranslationPageFlag {
    Queued,
    Translating,
    Ready,
    Failed,
}

data class RollingTranslationState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val windowSize: Int = 10,
    val triggerPageCount: Int = 5,
    val windowStartIndex: Int = 0,
    val windowEndExclusive: Int = 0,
    val nextWindowStartIndex: Int = 0,
    val triggerPageIndex: Int = 0,
    val activePageIndex: Int? = null,
    val pageFlags: Map<Int, TranslationPageFlag> = emptyMap(),
    val lastError: String? = null,
) {
    val windowPageCount: Int
        get() = (windowEndExclusive - windowStartIndex).coerceAtLeast(0)

    val readyPageCount: Int
        get() = (windowStartIndex until windowEndExclusive).count {
            pageFlags[it] == TranslationPageFlag.Ready
        }

    val fraction: Float
        get() = if (windowPageCount == 0) 0f else readyPageCount.toFloat() / windowPageCount.toFloat()

    fun flagFor(pageIndex: Int): TranslationPageFlag? = pageFlags[pageIndex]
}

data class RollingTranslationWindow(
    val startIndex: Int,
    val endExclusive: Int,
    val nextWindowStartIndex: Int,
    val triggerPageIndex: Int,
) {
    val pageIndexes: List<Int>
        get() = (startIndex until endExclusive).toList()
}

class RollingTranslationPolicy(
    val windowSize: Int = 10,
    val triggerPageCount: Int = 5,
) {
    init {
        require(windowSize > 0) { "windowSize must be positive." }
        require(triggerPageCount in 1..windowSize) {
            "triggerPageCount must be between 1 and windowSize."
        }
    }

    fun initialWindow(currentPageIndex: Int, totalPages: Int): RollingTranslationWindow? {
        if (totalPages <= 0) return null
        return window(currentPageIndex.coerceIn(0, totalPages - 1), totalPages)
    }

    fun nextWindow(
        currentPageIndex: Int,
        totalPages: Int,
        state: RollingTranslationState,
    ): RollingTranslationWindow? {
        if (!state.enabled || totalPages <= 0) return null
        if (currentPageIndex < state.triggerPageIndex) return null
        if (state.nextWindowStartIndex >= totalPages) return null

        val skippedPastNextWindow = currentPageIndex >= state.nextWindowStartIndex + windowSize
        val startIndex = if (skippedPastNextWindow) {
            currentPageIndex.coerceIn(0, totalPages - 1)
        } else {
            state.nextWindowStartIndex
        }
        return window(startIndex, totalPages)
    }

    private fun window(startIndex: Int, totalPages: Int): RollingTranslationWindow {
        val endExclusive = (startIndex + windowSize).coerceAtMost(totalPages)
        return RollingTranslationWindow(
            startIndex = startIndex,
            endExclusive = endExclusive,
            nextWindowStartIndex = endExclusive,
            // Human page positions are 1-based: count 5 means the fifth page in this window.
            triggerPageIndex = (startIndex + triggerPageCount - 1).coerceAtMost(endExclusive - 1),
        )
    }
}
