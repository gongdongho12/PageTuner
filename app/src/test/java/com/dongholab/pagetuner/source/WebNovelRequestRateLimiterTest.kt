package com.dongholab.pagetuner.source

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WebNovelRequestRateLimiterTest {
    @Test
    fun serializesRequestsPerProviderUsingMinimumInterval() = runTest {
        var now = 1_000L
        val sleeps = mutableListOf<Long>()
        val limiter = limiter(now = { now }, sleep = { wait -> sleeps += wait; now += wait })

        limiter.awaitPermit("https://wtr-lab.com/en/novel-list")
        limiter.awaitPermit("https://wtr-lab.com/en/novel/1/chapter-1")

        assertEquals(listOf(2_000L), sleeps)
    }

    @Test
    fun sharesOneBucketAcrossSubdomainsButNotOtherProviders() = runTest {
        var now = 1_000L
        val sleeps = mutableListOf<Long>()
        val limiter = limiter(now = { now }, sleep = { wait -> sleeps += wait; now += wait })

        limiter.awaitPermit("https://novelbuddy.me/search")
        limiter.awaitPermit("https://api.novelbuddy.me/titles/book/chapters")
        limiter.awaitPermit("https://wtr-lab.com/en/novel-list")

        assertEquals(listOf(2_000L), sleeps)
    }

    @Test
    fun retryAfterOverridesTheNormalInterval() = runTest {
        var now = 1_000L
        val sleeps = mutableListOf<Long>()
        val limiter = limiter(now = { now }, sleep = { wait -> sleeps += wait; now += wait })

        limiter.awaitPermit("https://wtr-lab.com/catalog")
        limiter.recordThrottled("https://wtr-lab.com/catalog", retryAfterMillis = 12_000L)
        limiter.awaitPermit("https://wtr-lab.com/chapter-1")

        assertEquals(listOf(12_000L), sleeps)
    }

    @Test
    fun parsesRetryAfterSecondsAndHttpDate() {
        assertEquals(7_000L, WebNovelRetryAfter.parseMillis("7", nowEpochMillis = 0L))
        assertEquals(
            10_000L,
            WebNovelRetryAfter.parseMillis(
                "Thu, 01 Jan 1970 00:00:10 GMT",
                nowEpochMillis = 0L,
            ),
        )
    }

    private fun limiter(
        now: () -> Long,
        sleep: suspend (Long) -> Unit,
    ) = WebNovelRequestRateLimiter(
        nowMillis = now,
        sleeper = sleep,
        jitter = { 0L },
        policyResolver = { _, _ -> WebNovelRateLimitPolicy(2_000L, 0L) },
    )
}
