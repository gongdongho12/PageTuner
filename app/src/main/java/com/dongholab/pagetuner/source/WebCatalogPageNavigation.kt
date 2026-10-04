package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.core.paging.PageMetadata

/** App navigation policy: null means no new catalog request is needed. */
internal fun PageMetadata.catalogNavigationTarget(requestedPage: Int): Int? {
    val lastPage = totalPages ?: Int.MAX_VALUE
    if (lastPage < 1) return null
    val target = requestedPage.coerceIn(1, lastPage)
    return target.takeUnless { it == currentPage }
}
