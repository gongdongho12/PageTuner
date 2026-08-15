package com.dongholab.pagetuner.ui.source

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.source.RemoteBookIdentity
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteSourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class WebNovelDetailPagePanelTest {
    @Test
    fun quickJumpUsesExactChapterNumberWhenTitlesAreDuplicated() {
        val chapters = listOf(
            chapter(number = 1, title = "The Beginning"),
            chapter(number = 10, title = "The Beginning"),
        )

        assertEquals(1, findChapterByNumber(chapters, 1)?.chapterNumber)
        assertEquals(10, findChapterByNumber(chapters, 10)?.chapterNumber)
    }

    private fun chapter(number: Int, title: String) = RemoteBookItem(
        identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "wtr", "chapter_$number"),
        title = title,
        format = DocumentFormat.TEXT,
        downloadUrl = "https://example.test/novel/42/chapter-$number",
        seriesId = "https://example.test/novel/42",
        chapterNumber = number,
    )
}
