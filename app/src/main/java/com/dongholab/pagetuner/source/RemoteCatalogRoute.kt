package com.dongholab.pagetuner.source

/**
 * Source-independent hierarchy for every remote reading system.
 * Chapter reading lives in the main reader; [Book] remains its explicit parent route.
 */
sealed interface RemoteCatalogRoute {
    data object SourceSystems : RemoteCatalogRoute

    data class Catalog(
        val catalogUrl: String,
    ) : RemoteCatalogRoute

    data class Book(
        val catalogUrl: String,
        val book: RemoteBookItem,
    ) : RemoteCatalogRoute

    fun parent(): RemoteCatalogRoute? = when (this) {
        SourceSystems -> null
        is Catalog -> SourceSystems
        is Book -> Catalog(catalogUrl)
    }
}
