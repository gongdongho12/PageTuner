package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCatalogSnapshotJsonTest {
    @Test
    fun roundTripPreservesHtmlSourceIdentityAndSeriesMetadata() {
        val item = RemoteBookItem(
            identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "default_wtr_lab", "novel_42"),
            title = "Example Novel",
            authors = listOf("Author"),
            format = DocumentFormat.TEXT,
            language = "en",
            downloadUrl = "https://wtr-lab.com/en/novel/42/example",
            coverUrl = "https://wtr-lab.com/covers/42.jpg",
            description = "Summary",
            chapterCount = 100,
            tags = listOf("Fantasy"),
            seriesId = "https://wtr-lab.com/en/novel/42/example",
            seriesTitle = "Example Novel",
        )
        val catalog = PageTurnerCatalog(
            version = PageTurnerWebCatalogParser.Version,
            id = "default_wtr_lab",
            title = "WTR-LAB",
            items = listOf(item),
        )

        val decoded = RemoteCatalogSnapshotJson.decode(RemoteCatalogSnapshotJson.encode(catalog))

        assertEquals(catalog, decoded)
        assertEquals(RemoteSourceType.WebNovel, decoded.items.single().identity.sourceType)
        assertEquals(item.seriesId, decoded.items.single().seriesId)
    }
}
