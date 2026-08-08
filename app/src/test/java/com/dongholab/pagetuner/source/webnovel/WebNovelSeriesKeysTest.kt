package com.dongholab.pagetuner.source.webnovel

import org.junit.Assert.assertEquals
import org.junit.Test

class WebNovelSeriesKeysTest {
    @Test
    fun detailAndChapterUrlsResolveToSameSeriesKey() {
        val detail = "https://wtr-lab.com/en/novel/88774/god-emperor-of-devouring"
        val chapter = "$detail/chapter-219?reader=1#content"

        assertEquals(WebNovelSeriesKeys.fromUrl(detail), WebNovelSeriesKeys.fromUrl(chapter))
    }
}
