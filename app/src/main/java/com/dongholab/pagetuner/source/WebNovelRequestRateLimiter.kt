package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.common.DiagnosticLogger
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WebNovelRequestKind {
    Document,
    Asset,
}

data class WebNovelRateLimitPolicy(
    val minimumIntervalMillis: Long,
    val jitterMillis: Long,
    val baseBackoffMillis: Long = 5_000L,
    val maximumBackoffMillis: Long = 60_000L,
)

/** Serializes requests per provider so concurrent jobs cannot create a traffic burst. */
class WebNovelRequestRateLimiter internal constructor(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { maximum ->
        if (maximum <= 0L) 0L else Random.nextLong(maximum + 1L)
    },
    private val policyResolver: (String, WebNovelRequestKind) -> WebNovelRateLimitPolicy =
        WebNovelRateLimitPolicies::resolve,
    private val waitObserver: (String, WebNovelRequestKind, Long) -> Unit = { _, _, _ -> },
    private val throttleObserver: (String, WebNovelRequestKind, Long) -> Unit = { _, _, _ -> },
) {
    private val states = mutableMapOf<String, HostState>()

    suspend fun awaitPermit(url: String, kind: WebNovelRequestKind = WebNovelRequestKind.Document) {
        val host = canonicalHost(url)
        val state = state(host, kind)
        val policy = policyResolver(host, kind)
        state.mutex.withLock {
            val waitMillis = (state.nextAllowedAtMillis - nowMillis()).coerceAtLeast(0L)
            if (waitMillis > 0L) {
                waitObserver(host, kind, waitMillis)
                sleeper(waitMillis)
            }
            state.nextAllowedAtMillis = nowMillis() + policy.minimumIntervalMillis +
                jitter(policy.jitterMillis).coerceIn(0L, policy.jitterMillis)
        }
    }

    suspend fun recordThrottled(
        url: String,
        retryAfterMillis: Long?,
        kind: WebNovelRequestKind = WebNovelRequestKind.Document,
    ) {
        val host = canonicalHost(url)
        val state = state(host, kind)
        val policy = policyResolver(host, kind)
        state.mutex.withLock {
            state.consecutiveThrottles += 1
            val exponential = policy.baseBackoffMillis *
                (1L shl (state.consecutiveThrottles - 1).coerceIn(0, 6))
            val boundedBackoff = if (retryAfterMillis != null) {
                retryAfterMillis.coerceIn(policy.minimumIntervalMillis, MaximumRetryAfterMillis)
            } else {
                exponential.coerceIn(policy.minimumIntervalMillis, policy.maximumBackoffMillis)
            }
            state.nextAllowedAtMillis = max(
                state.nextAllowedAtMillis,
                nowMillis() + boundedBackoff + jitter(policy.jitterMillis),
            )
            throttleObserver(host, kind, boundedBackoff)
        }
    }

    suspend fun recordSuccess(url: String, kind: WebNovelRequestKind = WebNovelRequestKind.Document) {
        val host = canonicalHost(url)
        val state = synchronized(states) { states["$host|$kind"] } ?: return
        state.mutex.withLock { state.consecutiveThrottles = 0 }
    }

    private fun state(host: String, kind: WebNovelRequestKind): HostState =
        synchronized(states) { states.getOrPut("$host|$kind", ::HostState) }

    private fun canonicalHost(url: String): String {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
            .removePrefix("www.")
        return when {
            host == "wtr-lab.com" || host.endsWith(".wtr-lab.com") -> "wtr-lab.com"
            host == "novelbuddy.me" || host.endsWith(".novelbuddy.me") -> "novelbuddy.me"
            else -> host.ifBlank { "unknown" }
        }
    }

    private class HostState(
        val mutex: Mutex = Mutex(),
        var nextAllowedAtMillis: Long = 0L,
        var consecutiveThrottles: Int = 0,
    )

    private companion object {
        const val MaximumRetryAfterMillis = 5 * 60 * 1_000L
    }
}

object WebNovelRateLimitPolicies {
    fun resolve(host: String, kind: WebNovelRequestKind): WebNovelRateLimitPolicy {
        if (kind == WebNovelRequestKind.Asset) {
            return WebNovelRateLimitPolicy(minimumIntervalMillis = 350L, jitterMillis = 250L)
        }
        return when (host) {
            "wtr-lab.com" -> WebNovelRateLimitPolicy(2_200L, 650L)
            "novelbuddy.me" -> WebNovelRateLimitPolicy(1_800L, 550L)
            else -> WebNovelRateLimitPolicy(1_500L, 500L)
        }
    }
}

object WebNovelRequestGate {
    private val limiter = WebNovelRequestRateLimiter(
        waitObserver = { host, kind, waitMillis ->
            DiagnosticLogger.log(
                "[WEB RATE WAIT]",
                "host=$host kind=$kind waitMs=$waitMillis",
            )
        },
        throttleObserver = { host, kind, backoffMillis ->
            DiagnosticLogger.log(
                "[WEB RATE THROTTLED]",
                "host=$host kind=$kind backoffMs=$backoffMillis",
            )
        },
    )

    suspend fun awaitPermit(url: String, kind: WebNovelRequestKind = WebNovelRequestKind.Document) =
        limiter.awaitPermit(url, kind)

    suspend fun recordThrottled(
        url: String,
        retryAfterMillis: Long?,
        kind: WebNovelRequestKind = WebNovelRequestKind.Document,
    ) = limiter.recordThrottled(url, retryAfterMillis, kind)

    suspend fun recordSuccess(url: String, kind: WebNovelRequestKind = WebNovelRequestKind.Document) =
        limiter.recordSuccess(url, kind)
}

internal object WebNovelRetryAfter {
    fun parseMillis(value: String?, nowEpochMillis: Long = System.currentTimeMillis()): Long? {
        val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        normalized.toLongOrNull()?.let { seconds ->
            return (seconds.coerceAtLeast(0L) * 1_000L).coerceAtMost(MaxRetryAfterMillis)
        }
        val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }
        return runCatching { parser.parse(normalized)?.time }
            .getOrNull()
            ?.let { epoch -> (epoch - nowEpochMillis).coerceIn(0L, MaxRetryAfterMillis) }
    }

    private const val MaxRetryAfterMillis = 5 * 60 * 1_000L
}
