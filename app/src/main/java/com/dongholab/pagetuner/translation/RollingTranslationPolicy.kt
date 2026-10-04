package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.core.paging.AlignedPageWindowPolicy
import com.dongholab.pagetuner.core.paging.PageWindow

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
    private val pageWindows = AlignedPageWindowPolicy(windowSize)

    init {
        require(windowSize > 0) { "windowSize must be positive." }
        require(triggerPageCount in 1..windowSize) {
            "triggerPageCount must be between 1 and windowSize."
        }
    }

    fun initialWindow(currentPageIndex: Int, totalPages: Int): RollingTranslationWindow? {
        return pageWindows.containing(currentPageIndex, totalPages)?.toRollingWindow()
    }

    fun nextWindow(
        currentPageIndex: Int,
        totalPages: Int,
        state: RollingTranslationState,
    ): RollingTranslationWindow? {
        if (!state.enabled || totalPages <= 0) return null
        val safePageIndex = currentPageIndex.coerceIn(0, totalPages - 1)
        val activeWindow = PageWindow(
            startIndex = state.windowStartIndex.coerceAtLeast(0),
            endExclusive = state.windowEndExclusive.coerceAtLeast(state.windowStartIndex),
        )
        if (safePageIndex !in activeWindow) {
            return pageWindows.containing(safePageIndex, totalPages)?.toRollingWindow()
        }
        if (safePageIndex < state.triggerPageIndex) return null
        return pageWindows.following(activeWindow, totalPages)?.toRollingWindow()
    }

    private fun PageWindow.toRollingWindow(): RollingTranslationWindow {
        return RollingTranslationWindow(
            startIndex = startIndex,
            endExclusive = endExclusive,
            nextWindowStartIndex = endExclusive,
            triggerPageIndex = pageWindows.triggerIndex(this, triggerPageCount),
        )
    }
}
