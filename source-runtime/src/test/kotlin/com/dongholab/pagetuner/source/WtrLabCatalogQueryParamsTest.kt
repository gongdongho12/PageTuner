package com.dongholab.pagetuner.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WtrLabCatalogQueryParamsTest {
    @Test
    fun buildsServerSideKeywordAndGenreFinderUrl() {
        val url = WtrLabCatalogQueryParams(
            orderBy = "views",
            status = "ongoing",
            genreId = 9,
            query = "magic academy",
            page = 3,
        ).buildUrl("https://wtr-lab.com/en/novel-list?page=8")

        assertEquals(
            "https://wtr-lab.com/en/novel-finder?orderBy=view&status=ongoing&gi=9&text=magic%20academy&page=3",
            url,
        )
    }

    @Test
    fun parsesFinderUrlBackIntoEditableSearchState() {
        val request = WtrLabCatalogQueryParams.fromUrl(
            "https://wtr-lab.com/en/novel-finder?orderBy=reader&gi=22&text=second+chance&page=7",
        )

        assertEquals("readers", request.orderBy)
        assertEquals(22, request.genreId)
        assertEquals("second chance", request.query)
        assertEquals(7, request.page)
    }

    @Test
    fun usesVerifiedWtrGenreIdentifiers() {
        val genres = WtrLabCatalogQueryParams.GENRE_OPTIONS

        assertNull(genres.first().id)
        assertEquals(9, genres.single { it.slug == "fantasy" }.id)
        assertEquals(22, genres.single { it.slug == "romance" }.id)
        assertEquals(37, genres.single { it.slug == "xianxia" }.id)
        assertEquals(40, genres.single { it.slug == "yuri" }.id)
    }
}
