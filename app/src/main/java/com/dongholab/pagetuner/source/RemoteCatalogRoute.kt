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

    /** Stable key used to retain page-local UI state while navigating the hierarchy. */
    fun pageStateKey(): String = when (this) {
        SourceSystems -> "remote-source-systems"
        is Catalog -> "remote-catalog:$catalogUrl"
        is Book -> "remote-book:${book.identity.sourceType}:${book.identity.accountId}:${book.identity.remoteId}"
    }
}
