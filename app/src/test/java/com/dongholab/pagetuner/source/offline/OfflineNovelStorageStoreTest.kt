package com.dongholab.pagetuner.source.offline

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.source.RemoteBookIdentity
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OfflineNovelStorageStoreTest {
    private val item = RemoteBookItem(
        identity = RemoteBookIdentity(
            sourceType = RemoteSourceType.WebNovel,
            accountId = "novel-42",
            remoteId = "chapter-7",
        ),
        title = "Chapter 7",
        format = DocumentFormat.TEXT,
        language = "en",
        downloadUrl = "https://example.test/novel/42/chapter/7",
    )

    @Test
    fun keepsOriginalAndMultipleTranslationLanguagesTogether() {
        val store = OfflineNovelStorageStore(null)

        store.saveOriginalChapter(item, chapterNumber = 7, contentText = "Original text")
        store.saveTranslation(item, 7, "ko", "번역문", "test-provider")
        store.saveTranslation(item, 7, "ja", "翻訳", "test-provider")

        val packageData = requireNotNull(store.getOfflineChapter(item))
        assertEquals("Original text", packageData.originalText)
        assertEquals("번역문", packageData.preferredText("ko").first)
        assertEquals("翻訳", packageData.preferredText("ja").first)
        assertEquals("Original text", packageData.preferredText("fr").first)
        assertEquals(setOf("en", "ko", "ja"), store.downloadedLanguages(item))
    }

    @Test
    fun usesRemoteChapterIdentityInsteadOfDisplayNumber() {
        val store = OfflineNovelStorageStore(null)
        val anotherItem = item.copy(
            identity = item.identity.copy(remoteId = "chapter-special"),
        )

        store.saveOriginalChapter(item, 1, "First")

        assertTrue(store.isChapterDownloaded(item))
        assertFalse(store.isChapterDownloaded(anotherItem))
    }

    @Test
    fun sameAccountAndChapterIdInDifferentSeriesDoNotOverwriteEachOther() {
        val store = OfflineNovelStorageStore(null)
        val firstBook = item.copy(seriesId = "https://example.test/novel/42")
        val secondBook = item.copy(seriesId = "https://example.test/novel/99")

        store.saveOriginalChapter(firstBook, 7, "First book chapter")
        store.saveOriginalChapter(secondBook, 7, "Second book chapter")

        assertEquals("First book chapter", store.getOfflineChapter(firstBook)?.originalText)
        assertEquals("Second book chapter", store.getOfflineChapter(secondBook)?.originalText)
    }

    @Test
    fun restoresOriginalAndTranslationsAfterStoreRestart() {
        val directory = Files.createTempDirectory("offline-novel-test").toFile()
        try {
            OfflineNovelStorageStore.forDirectory(directory).apply {
                saveOriginalChapter(item, 7, "Original text")
                saveTranslation(item, 7, "ko", "번역문", "test-provider")
            }

            val restored = requireNotNull(
                OfflineNovelStorageStore.forDirectory(directory).getOfflineChapter(item),
            )
            assertEquals("Original text", restored.originalText)
            assertEquals("번역문", restored.preferredText("ko").first)
            assertTrue(directory.listFiles().orEmpty().single().name.endsWith(".json"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
