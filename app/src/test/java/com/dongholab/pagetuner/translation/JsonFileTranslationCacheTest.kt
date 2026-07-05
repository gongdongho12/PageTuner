package com.dongholab.pagetuner.translation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonFileTranslationCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesCacheFileThroughTemporaryFileAndReloadsRecords() = runTest {
        val cacheFile = temporaryFolder.newFolder("translation-cache")
            .resolve("page-turner-cache.json")
        val key = testCacheKey(segmentId = "segment-1")
        val cache = JsonFileTranslationCache(cacheFile)

        cache.putAll(
            listOf(
                CachedTranslation(
                    key = key,
                    text = "안녕하세요",
                    updatedAtMillis = 1_234L,
                ),
            ),
        )

        val reloaded = JsonFileTranslationCache(cacheFile).getMany(listOf(key))

        assertEquals("안녕하세요", reloaded[key.id]?.text)
        assertTrue(cacheFile.readText(Charsets.UTF_8).contains("\"version\""))
        assertFalse(cacheFile.resolveSibling("${cacheFile.name}.tmp").exists())
    }

    @Test
    fun deleteManyPersistsAtomicallyForFreshCacheInstances() = runTest {
        val cacheFile = temporaryFolder.newFolder("translation-cache-delete")
            .resolve("page-turner-cache.json")
        val firstKey = testCacheKey(segmentId = "segment-1")
        val secondKey = testCacheKey(segmentId = "segment-2")
        val cache = JsonFileTranslationCache(cacheFile)
        cache.putAll(
            listOf(
                CachedTranslation(firstKey, "first", updatedAtMillis = 1L),
                CachedTranslation(secondKey, "second", updatedAtMillis = 2L),
            ),
        )

        assertEquals(1, cache.deleteMany(listOf(firstKey)))

        val reloaded = JsonFileTranslationCache(cacheFile).getMany(listOf(firstKey, secondKey))
        assertFalse(reloaded.containsKey(firstKey.id))
        assertEquals("second", reloaded[secondKey.id]?.text)
        assertFalse(cacheFile.resolveSibling("${cacheFile.name}.tmp").exists())
    }

    private fun testCacheKey(segmentId: String): TranslationCacheKey {
        return TranslationCacheKey(
            documentId = "document",
            segmentId = segmentId,
            sourceLanguage = "en",
            targetLanguage = "ko",
            providerId = "provider",
        )
    }
}
