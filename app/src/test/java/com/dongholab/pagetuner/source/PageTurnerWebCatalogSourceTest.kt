package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTurnerWebCatalogSourceTest {
    @Test
    fun parsesCatalogItemsAndResolvesRelativeUrls() {
        val catalog = PageTurnerWebCatalogParser.parse(
            rawJson = sampleCatalog,
            catalogUrl = "https://example.com/library/catalog.json",
        )

        val item = catalog.items.single()
        assertEquals("personal-library", catalog.id)
        assertEquals("Sample Text", item.title)
        assertEquals(DocumentFormat.TEXT, item.format)
        assertEquals("https://example.com/library/books/sample.txt", item.downloadUrl)
        assertEquals("https://example.com/library/covers/sample.png", item.coverUrl)
        assertEquals(listOf("ko", "en"), item.translationHints.targetLanguages)
    }

    @Test
    fun listsSearchesAndDownloadsViaInjectedFetchers() = runTest {
        val source = PageTurnerWebCatalogSource(
            catalogUrl = "https://example.com/catalog.json",
            fetchCatalog = { sampleCatalog },
            downloadBook = { item -> item.title.toByteArray(Charsets.UTF_8) },
        )

        val connection = source.connect()
        val searchResult = source.search("sample").single()
        val bytes = source.download(searchResult)

        assertEquals(RemoteSourceType.PageTurnerWebCatalog, connection.sourceType)
        assertEquals("personal-library", connection.accountId)
        assertEquals(1, connection.itemCount)
        assertArrayEquals("Sample Text".toByteArray(Charsets.UTF_8), bytes)
    }

    private val sampleCatalog = """
        {
          "version": "pagetuner.catalog.v0",
          "id": "personal-library",
          "title": "Personal Library",
          "updatedAt": "2026-06-28T00:00:00Z",
          "items": [
            {
              "id": "sample-text",
              "title": "Sample Text",
              "authors": ["PageTurner"],
              "format": "txt",
              "language": "en",
              "href": "books/sample.txt",
              "type": "text/plain",
              "cover": "covers/sample.png",
              "translationHints": {
                "sourceLanguage": "en",
                "targetLanguages": ["ko", "en"]
              }
            }
          ]
        }
    """.trimIndent()
}
