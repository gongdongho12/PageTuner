package com.dongholab.pagetuner.source

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CoverThumbnailRepositoryTest {
    @Test
    fun duplicateAndRevisitedUrlsReuseCache() = runTest {
        val fetched = mutableListOf<String>()
        val repository = CoverThumbnailRepository(fetch = { url, _ -> fetched += url; byteArrayOf(1) })
        assertEquals(setOf("a", "b"), repository.load(listOf("a", "a", "b")).keys)
        repository.load(listOf("b", "a"))
        assertEquals(listOf("a", "b"), fetched)
    }

    @Test
    fun imageFailureAndOversizedPayloadDoNotFailOtherThumbnails() = runTest {
        val repository = CoverThumbnailRepository(fetch = { url, limit ->
            if (url == "failure") throw IOException("offline")
            ByteArray(if (url == "large") limit + 1 else 1)
        }, maxImageBytes = 2)
        assertEquals(setOf("ok"), repository.load(listOf("failure", "large", "ok")).keys)
    }

    @Test
    fun byteBudgetEvictsLeastRecentlyUsedEntry() = runTest {
        val fetched = mutableListOf<String>()
        val repository = CoverThumbnailRepository(fetch = { url, _ -> fetched += url; byteArrayOf(1) }, maxCacheBytes = 2)
        repository.load(listOf("a", "b"))
        repository.load(listOf("a"))
        repository.load(listOf("c"))
        repository.load(listOf("a"))
        repository.load(listOf("b"))
        assertEquals(listOf("a", "b", "c", "b"), fetched)
    }

    @Test
    fun cancellationPropagatesAndDoesNotCacheCancelledFetch() = runTest {
        var calls = 0
        val repository = CoverThumbnailRepository(fetch = { _, _ ->
            if (++calls == 1) throw CancellationException("cancel")
            byteArrayOf(1)
        })
        try { repository.load(listOf("a")); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(setOf("a"), repository.load(listOf("a")).keys)
        assertEquals(2, calls)
    }
}
