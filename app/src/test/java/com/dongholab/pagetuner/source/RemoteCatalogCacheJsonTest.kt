package com.dongholab.pagetuner.source

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCatalogCacheJsonTest {
    @Test
    fun encodesAndDecodesCachedCatalogs() {
        val catalogs = listOf(
            CachedWebCatalog(
                url = "https://example.com/catalog.json",
                fetchedAtMillis = 1_234L,
                rawJson = """{"version":"pagetuner.catalog.v0"}""",
                catalogId = "personal",
                title = "Personal Library",
                updatedAt = "2026-07-05T00:00:00Z",
                itemCount = 2,
            ),
        )

        val decoded = RemoteCatalogCacheJson.decode(RemoteCatalogCacheJson.encode(catalogs))

        assertEquals(catalogs, decoded)
    }
}
