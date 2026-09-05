package com.dongholab.pagetuner.core.paging

/** Provider-neutral request for one 1-based remote page. */
data class PageRequest(
    val pageNumber: Int,
    val pageSize: Int? = null,
) {
    init {
        require(pageNumber > 0) { "pageNumber must be positive." }
        require(pageSize == null || pageSize > 0) { "pageSize must be positive when supplied." }
    }
}

/** Read-only page contract shared by remote providers and future local data sources. */
interface PageResult<out T> {
    val items: List<T>
    val currentPage: Int
    val totalPages: Int?
    val totalItems: Int?
    val hasPreviousPage: Boolean
    val hasNextPage: Boolean
}

/** UI-independent loader boundary; network, database, and test implementations are interchangeable. */
fun interface PageLoader<T> {
    suspend fun loadPage(request: PageRequest): PageResult<T>
}

/** Immutable page metadata that UI adapters may render without owning pagination rules. */
data class PageMetadata(
    val currentPage: Int = 1,
    val totalPages: Int? = null,
    val totalItems: Int? = null,
    val pageItemCount: Int = 0,
    val hasPreviousPage: Boolean = currentPage > 1,
    val hasNextPage: Boolean = totalPages?.let { currentPage < it } ?: false,
)

fun PageResult<*>.metadata(): PageMetadata = PageMetadata(
    currentPage = currentPage,
    totalPages = totalPages,
    totalItems = totalItems,
    pageItemCount = items.size,
    hasPreviousPage = hasPreviousPage,
    hasNextPage = hasNextPage,
)

/** Zero-based, end-exclusive range used for reader translation and collection slicing. */
data class PageWindow(
    val startIndex: Int,
    val endExclusive: Int,
) {
    init {
        require(startIndex >= 0) { "startIndex must not be negative." }
        require(endExclusive >= startIndex) { "endExclusive must not precede startIndex." }
    }

    val indexes: IntRange
        get() = startIndex until endExclusive

    operator fun contains(pageIndex: Int): Boolean = pageIndex in indexes
}

/** Fixed block policy: visible pages 1-10, 11-20, 21-30 always retain the same identities. */
class AlignedPageWindowPolicy(
    val blockSize: Int,
) {
    init {
        require(blockSize > 0) { "blockSize must be positive." }
    }

    fun containing(pageIndex: Int, totalPages: Int): PageWindow? {
        if (totalPages <= 0) return null
        val safeIndex = pageIndex.coerceIn(0, totalPages - 1)
        val startIndex = (safeIndex / blockSize) * blockSize
        return PageWindow(startIndex, (startIndex + blockSize).coerceAtMost(totalPages))
    }

    fun following(window: PageWindow, totalPages: Int): PageWindow? {
        if (window.endExclusive >= totalPages) return null
        return containing(window.endExclusive, totalPages)
    }

    fun triggerIndex(window: PageWindow, triggerPageCount: Int): Int {
        require(triggerPageCount in 1..blockSize) {
            "triggerPageCount must be between 1 and blockSize."
        }
        if (window.startIndex == window.endExclusive) return window.startIndex
        return (window.startIndex + triggerPageCount - 1).coerceAtMost(window.endExclusive - 1)
    }
}

/** Stable slice returned to a renderer after page-size measurement is complete. */
data class ListPage<out T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageSize: Int,
    val pageCount: Int,
    val itemCount: Int,
    val startItemNumber: Int,
    val endItemNumber: Int,
)

object ListPagePolicy {
    fun coercePageIndex(requestedPageIndex: Int, itemCount: Int, pageSize: Int): Int {
        if (itemCount <= 0 || pageSize <= 0) return 0
        return requestedPageIndex.coerceIn(0, (itemCount - 1) / pageSize)
    }

    fun <T> slice(items: List<T>, requestedPageIndex: Int, pageSize: Int): ListPage<T> {
        require(pageSize > 0) { "pageSize must be positive." }
        if (items.isEmpty()) {
            return ListPage(emptyList(), 0, pageSize, 0, 0, 0, 0)
        }
        val safePageIndex = coercePageIndex(requestedPageIndex, items.size, pageSize)
        val startIndex = safePageIndex * pageSize
        val endExclusive = (startIndex + pageSize).coerceAtMost(items.size)
        return ListPage(
            items = items.subList(startIndex, endExclusive),
            pageIndex = safePageIndex,
            pageSize = pageSize,
            pageCount = (items.size + pageSize - 1) / pageSize,
            itemCount = items.size,
            startItemNumber = startIndex + 1,
            endItemNumber = endExclusive,
        )
    }
}
