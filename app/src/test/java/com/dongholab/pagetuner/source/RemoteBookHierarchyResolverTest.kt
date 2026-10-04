package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteBookHierarchyResolverTest {
    @Test
    fun unknownCatalogTypeFallsBackToOneChapterBook() = runTest {
        val book = item(RemoteSourceType.PageTurnerWebCatalog)
        val resolved = RoutingRemoteBookHierarchyResolver(emptyMap()).resolve(book)

        assertEquals(1, resolved.book.chapterCount)
        assertEquals(listOf(book), resolved.chapters)
    }

    @Test
    fun registeredSourceUsesItsOwnHierarchyImplementation() = runTest {
        val book = item(RemoteSourceType.GoogleDrive)
        val chapter = book.copy(title = "Chapter 1", downloadUrl = "https://example.com/chapter-1")
        val resolver = RoutingRemoteBookHierarchyResolver(
            resolvers = mapOf(
                RemoteSourceType.GoogleDrive to RemoteBookHierarchyResolver {
                    RemoteBookHierarchy(book.copy(chapterCount = 1), listOf(chapter))
                },
            ),
        )

        assertEquals(listOf(chapter), resolver.resolve(book).chapters)
    }

    private fun item(type: RemoteSourceType) = RemoteBookItem(
        identity = RemoteBookIdentity(type, "account", "book"),
        title = "Book",
        format = DocumentFormat.TEXT,
        downloadUrl = "https://example.com/book",
    )
}
