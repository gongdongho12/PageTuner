package com.dongholab.pagetuner.source.wtr

import com.dongholab.pagetuner.source.webnovel.WebNovelChapterLoadStrategy
import com.dongholab.pagetuner.source.webnovel.WtrLabSiteAdapter
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WtrLabReaderIdentityTest {
    @Test fun acceptsOnlyTheRequestedNovelAndChapter() {
        val parsed = WtrLabDomScraper.parseReaderChapterResponse(42, 7, response())
        assertEquals(42L, parsed.novelId)
        assertEquals(7, parsed.chapterNumber)
        assertTrue(parsed.paragraphs.isNotEmpty())
        for (identity in listOf(43L to 7, 42L to 8)) {
            assertThrows(IOException::class.java) {
                WtrLabDomScraper.parseReaderChapterResponse(identity.first, identity.second, response())
            }
        }
    }

    @Test fun refusesMissingZeroAndFractionalIdentityInsteadOfUsingRequestDefaults() {
        val invalid = listOf(
            JSONObject(response()).apply { remove("chapter") },
            JSONObject(response()).apply { getJSONObject("chapter").remove("raw_id") },
            JSONObject(response()).apply { getJSONObject("chapter").remove("order") },
            JSONObject(response()).apply { getJSONObject("chapter").put("raw_id", 0) },
            JSONObject(response()).apply { getJSONObject("chapter").put("order", 7.5) },
        )
        invalid.forEach { value ->
            assertThrows(IOException::class.java) { WtrLabDomScraper.parseReaderChapterResponse(42, 7, value.toString()) }
        }
    }

    @Test fun httpOnlyAdapterDoesNotPublishAnotherNovelsOrChaptersText() = runBlocking {
        for (wrongChapter in listOf("""{"raw_id":43,"order":7}""", """{"raw_id":42,"order":8}""")) {
            val adapter = WtrLabSiteAdapter(
                chapterLoadStrategy = WebNovelChapterLoadStrategy.HttpOnly,
                postReaderJson = { _, _, _ -> JSONObject(response()).put("chapter", JSONObject(wrongChapter)).toString() },
            )
            val error = runCatching { adapter.loadChapter("https://wtr-lab.com/en/novel/42/sample/chapter-7", "Chapter 7", { error("No HTML request") }, null) }.exceptionOrNull()
            assertTrue(error is IOException)
        }
    }

    private fun response() = """{"success":true,"chapter":{"raw_id":42,"order":7},"data":{"title":"Seventh","body":["${"An original chapter paragraph. ".repeat(8)}"]}}"""
}
