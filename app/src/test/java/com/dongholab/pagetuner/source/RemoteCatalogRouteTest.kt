package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteCatalogRouteTest {
    @Test
    fun hierarchyReturnsFromBookToItsCatalogThenSourceSystems() {
        val catalog = "https://catalog.example/books"
        val bookRoute = RemoteCatalogRoute.Book(catalog, book())

        val catalogRoute = bookRoute.parent()

        assertEquals(RemoteCatalogRoute.Catalog(catalog), catalogRoute)
        assertEquals(RemoteCatalogRoute.SourceSystems, catalogRoute?.parent())
        assertNull(RemoteCatalogRoute.SourceSystems.parent())
    }

    @Test
    fun bookParentDoesNotDependOnProviderImplementation() {
        val book = book().copy(
            identity = RemoteBookIdentity(RemoteSourceType.PageTurnerWebCatalog, "another", "novel-2"),
        )

        assertEquals(
            RemoteCatalogRoute.Catalog("https://another.example/catalog"),
            RemoteCatalogRoute.Book("https://another.example/catalog", book).parent(),
        )
    }

    private fun book() = RemoteBookItem(
        identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "source", "novel-1"),
        title = "Novel",
        format = DocumentFormat.TEXT,
        downloadUrl = "https://catalog.example/novel-1",
    )
}
