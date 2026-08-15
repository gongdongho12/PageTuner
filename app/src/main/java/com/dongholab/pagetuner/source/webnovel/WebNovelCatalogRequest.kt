package com.dongholab.pagetuner.source.webnovel

/** Provider-neutral catalog state. Provider adapters own the actual query parameter names. */
data class WebNovelCatalogRequest(
    val query: String = "",
    val page: Int = 1,
    val filters: Map<String, String> = emptyMap(),
)
