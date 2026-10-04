package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.DocumentIds
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonFileTranslationCacheTest {
    @Test
    fun cacheKeyKeepsTheLegacyHashAfterMovingIdentityToSharedCore() {
        val key = testCacheKey("segment-1")
        val legacyId = DocumentIds.sha256(
            listOf(
                key.documentId,
                key.segmentId,
                key.sourceLanguage,
                key.targetLanguage,
                key.providerId,
            ).joinToString("|"),
        )

        assertEquals(legacyId, key.id)
    }

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

    @Test
    fun resolvesLocalBookCacheBesideStoredBookInTranslateFolder() {
        val libraryDir = temporaryFolder.newFolder("local_library")

        val cacheFile = translationCacheFileForLocalBook(
            libraryDir = libraryDir,
            relativePath = "books/hash-Example Book.txt",
        )

        assertEquals(
            libraryDir.resolve("books/translate/hash-Example Book.translations.json").canonicalFile,
            cacheFile.canonicalFile,
        )
    }

    @Test
    fun conditionalRestoreSeesInterveningWritesFromAnotherCacheInstanceAndWritesNothingOnConflict() = runTest {
        val file = temporaryFolder.newFolder("shared-cache").resolve("cache.json")
        val writer = JsonFileTranslationCache(file)
        val restorer = JsonFileTranslationCache(file)
        val first = testCacheKey("first")
        val second = testCacheKey("second")
        assertTrue(restorer.getMany(listOf(first, second)).isEmpty())
        val local = CachedTranslation(second, "new local edit", 12L)
        writer.putAll(listOf(local))
        val before = file.readText()
        assertFalse(restorer.putAllIfCompatible(listOf(CachedTranslation(first, "remote first", 13L), CachedTranslation(second, "remote second", 13L))))
        assertEquals(before, file.readText())
        assertEquals(mapOf(second.id to local), restorer.getMany(listOf(first, second)))
        assertEquals(mapOf(second.id to local), writer.getMany(listOf(first, second)))
    }

    @Test
    fun concurrentConditionalWritesForSameFileAllowExactlyOneDifferentTranslation() = runTest {
        val file = temporaryFolder.newFolder("concurrent-cache").resolve("cache.json")
        val first = JsonFileTranslationCache(file)
        val second = JsonFileTranslationCache(file)
        val key = testCacheKey("segment")
        val results = listOf(
            async(Dispatchers.Default) { first.putAllIfCompatible(listOf(CachedTranslation(key, "first", 1L))) },
            async(Dispatchers.Default) { second.putAllIfCompatible(listOf(CachedTranslation(key, "second", 2L))) },
        ).awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(first.getMany(listOf(key)), second.getMany(listOf(key)))
    }

    @Test
    fun diskWriteFailurePreservesCommittedMemoryAndFileAcrossCacheInstances() = runTest {
        val file = temporaryFolder.newFolder("failing-cache").resolve("cache.json")
        val writer = JsonFileTranslationCache(file)
        val reader = JsonFileTranslationCache(file)
        val key = testCacheKey("original")
        val original = CachedTranslation(key, "committed", 1L)
        writer.putAll(listOf(original))
        val before = file.readText()
        val temporaryPath = file.resolveSibling("${file.name}.tmp")
        assertTrue(temporaryPath.mkdir())
        temporaryPath.resolve("blocker").writeText("keep this directory nonempty")
        val added = CachedTranslation(testCacheKey("new"), "uncommitted", 2L)
        try { writer.putAllIfCompatible(listOf(added)); error("Expected write failure") } catch (_: IOException) { }
        try { writer.putAll(listOf(original.copy(text = "replacement"))); error("Expected write failure") } catch (_: IOException) { }
        try { writer.deleteMany(listOf(key)); error("Expected write failure") } catch (_: IOException) { }
        assertEquals(before, file.readText())
        assertEquals(mapOf(key.id to original), writer.getMany(listOf(key, added.key)))
        assertEquals(mapOf(key.id to original), reader.getMany(listOf(key, added.key)))
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
