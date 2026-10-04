package com.dongholab.pagetuner.library

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.source.RemoteBookIdentity
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteLibraryIdentityTest {
    @Test
    fun chaptersFromSameSeriesResolveToOneLocalBook() {
        val first = chapter("chapter_1", "series-42")
        val second = chapter("chapter_2", "series-42")

        assertEquals(
            first.remoteLibraryIdentityOrNull()?.localBookId,
            second.remoteLibraryIdentityOrNull()?.localBookId,
        )
    }

    @Test
    fun differentSeriesRemainDifferentLocalBooks() {
        assertNotEquals(
            chapter("chapter_1", "series-42").remoteLibraryIdentityOrNull()?.localBookId,
            chapter("chapter_1", "series-99").remoteLibraryIdentityOrNull()?.localBookId,
        )
    }

    @Test
    fun sameSeriesIdFromDifferentProvidersRemainsDifferentLocalBooks() {
        val wtr = chapter("chapter_1", "series-42")
        val novelBuddy = wtr.copy(
            identity = wtr.identity.copy(accountId = "novelbuddy"),
        )

        assertNotEquals(
            wtr.remoteLibraryIdentityOrNull()?.localBookId,
            novelBuddy.remoteLibraryIdentityOrNull()?.localBookId,
        )
    }

    @Test
    fun nonSeriesRemoteItemKeepsLegacyContentIdentityFlow() {
        assertNull(chapter("chapter_1", null).remoteLibraryIdentityOrNull())
    }

    private fun chapter(chapterId: String, seriesId: String?) = RemoteBookItem(
        identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "wtr", chapterId),
        title = chapterId,
        format = DocumentFormat.TEXT,
        downloadUrl = "https://example.com/$chapterId",
        seriesId = seriesId,
    )
}
