package com.dongholab.pagetuner.source.webnovel

import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Selects how a provider obtains a chapter body.
 *
 * [HttpThenWebView] is the normal production mode: use the provider's lightweight HTTP path
 * first and retain rendered loading as a compatibility fallback when the site changes or
 * requires a browser challenge.
 */
enum class WebNovelChapterLoadStrategy {
    HttpOnly,
    WebViewOnly,
    HttpThenWebView,
}

/** Common fallback semantics shared by site adapters that support both loading mechanisms. */
object WebNovelChapterLoadPolicy {
    suspend fun <T> load(
        strategy: WebNovelChapterLoadStrategy,
        http: suspend () -> T,
        webView: suspend () -> T,
    ): T = when (strategy) {
        WebNovelChapterLoadStrategy.HttpOnly -> http()
        WebNovelChapterLoadStrategy.WebViewOnly -> webView()
        WebNovelChapterLoadStrategy.HttpThenWebView -> {
            val httpFailure = try {
                return http()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                error
            }

            try {
                webView()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                throw IOException(
                    "HTTP chapter loading failed and the rendered WebView fallback also failed.",
                    error,
                ).apply { addSuppressed(httpFailure) }
            }
        }
    }
}
