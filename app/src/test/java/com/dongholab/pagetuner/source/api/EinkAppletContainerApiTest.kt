package com.dongholab.pagetuner.source.api

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.source.RemoteBookIdentity
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteSourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EinkAppletContainerApiTest {
    @Test
    fun einkAppletContainerApi_implementationsReturnValidReaderDocument() = runTest {
        val sampleItem = RemoteBookItem(
            identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "applet_1", "book_1"),
            title = "Test Novel",
            format = DocumentFormat.TEXT,
            downloadUrl = "https://example.com/novel/1",
        )

        val fakeApi = object : EinkAppletContainerApi {
            override suspend fun getBookList(): List<RemoteBookItem> = listOf(sampleItem)

            override suspend fun searchBooks(query: String): List<RemoteBookItem> = listOf(sampleItem)

            override suspend fun prepareBookViewer(item: RemoteBookItem): ReaderDocument {
                return ReaderDocument(
                    id = item.identity.remoteId,
                    title = item.title,
                    format = item.format,
                    pages = listOf(
                        ReaderPage(
                            index = 0,
                            segments = listOf(TextSegment("s1", 0, 0, "Chapter 1 Content")),
                        ),
                    ),
                )
            }

            override suspend fun openInMonochromeViewer(item: RemoteBookItem): ReaderDocument {
                return prepareBookViewer(item)
            }
        }

        val catalog = fakeApi.getBookList()
        val search = fakeApi.searchBooks("Test")
        val viewerDoc = fakeApi.openInMonochromeViewer(sampleItem)

        assertEquals(1, catalog.size)
        assertEquals(1, search.size)
        assertEquals("Test Novel", viewerDoc.title)
        assertEquals(1, viewerDoc.pageCount)
        assertEquals("Chapter 1 Content", viewerDoc.pages[0].plainText)
    }
}
