package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.source.webnovel.WebNovelSeriesKeys
import com.dongholab.pagetuner.translation.sync.sourceFavorite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceFavoriteIdentityTest {
    @Test fun catalogOriginalProviderIdentityMatchesServiceAndSurvivesStructuredCache() = runTest {
        val source = WebNovelRemoteBookSource("my-local-account", "https://wtr-lab.com/en",
            fetchHtml = { WtrLabDomScraperTest.catalogHtml }, renderedChapterLoader = null)
        val book = source.loadCatalogPage(1).items.single()
        assertTrue(book.identity.remoteId.startsWith("novel_"))
        assertEquals("wtr-lab", book.sourceProviderId)
        assertEquals(WebNovelSeriesKeys.fromUrl(book.downloadUrl), book.sourceBookId)
        assertNotEquals(book.identity.remoteId, book.sourceBookId)
        val catalog = PageTurnerCatalog("1", "local", "Local", items = listOf(book))
        val restored = RemoteCatalogSnapshotJson.decode(RemoteCatalogSnapshotJson.encode(catalog)).items.single()
        assertEquals(book.sourceFavorite(), restored.sourceFavorite())
    }

    @Test fun legacyRecordsNeverInferProviderOrBookIdentityFromTitleOrUrl() {
        val legacy = book()
        assertNull(legacy.sourceFavorite())
        assertNull(legacy.copy(sourceProviderId = "wtr-lab").sourceFavorite())
        assertNull(legacy.copy(sourceBookId = "https://wtr-lab.com/en/novel/42/book").sourceFavorite())
    }

    @Test fun metadataCanBeCleanedWithoutChangingOriginalOpaqueIdentity() {
        val known = book().copy(sourceProviderId = "wtr-lab", sourceBookId = "original:Case:ID", title = " Book ",
            authors = listOf(" Author ", " "), downloadUrl = "https://example.com/책")
        val value = requireNotNull(known.sourceFavorite())
        assertEquals("original:Case:ID", value.identity.bookId)
        assertEquals("Book", value.book!!.title); assertEquals(listOf("Author"), value.book.authors)
        assertEquals("https://example.com/%EC%B1%85", value.book.url)
        assertNull(known.copy(sourceBookId = "x".repeat(501)).sourceFavorite())
        assertNull(known.copy(downloadUrl = "https://user:pass@example.com/book").sourceFavorite())
        assertFalse(sameFavorite(known, known.copy(sourceBookId = "original:case:ID")))
    }

    @Test fun detailRefreshPreservesKnownSourcePairWhenDisplayUrlDiffers() {
        val saved = book().copy(sourceProviderId = "original-provider", sourceBookId = "original-book",
            downloadUrl = "https://another.example.com/new/location")
        assertEquals(saved, saved.withResolvedSourceIdentity("another-adapter"))
        val legacy = book().withResolvedSourceIdentity("wtr-lab")
        assertEquals("wtr-lab", legacy.sourceProviderId)
        assertEquals(WebNovelSeriesKeys.fromUrl(legacy.downloadUrl), legacy.sourceBookId)
    }

    private fun book() = RemoteBookItem(RemoteBookIdentity(RemoteSourceType.WebNovel, "local", "novel_42"),
        "Book", listOf("Author"), DocumentFormat.TEXT, "en", "https://wtr-lab.com/en/novel/42/book")
}
