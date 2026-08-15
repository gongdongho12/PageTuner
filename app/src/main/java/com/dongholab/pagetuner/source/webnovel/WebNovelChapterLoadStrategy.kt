package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.common.DiagnosticLogger
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

class WebNovelAuthenticationRequiredException(
    val providerName: String,
) : IOException("$providerName sign-in is required before this chapter can be saved.")

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
            DiagnosticLogger.log("[WEB CHAPTER HTTP START]", "Trying lightweight HTTP loader")
            val httpFailure = try {
                return http().also {
                    DiagnosticLogger.log("[WEB CHAPTER HTTP SUCCESS]", "HTTP loader returned chapter content")
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (error is WebNovelAuthenticationRequiredException) {
                    DiagnosticLogger.log(
                        "[WEB CHAPTER AUTH REQUIRED]",
                        "provider=${error.providerName}; rendered fallback skipped",
                    )
                    throw error
                }
                DiagnosticLogger.log(
                    "[WEB CHAPTER FALLBACK]",
                    "HTTP ${error.javaClass.simpleName}: ${error.message}; switching to rendered WebView",
                )
                error
            }

            try {
                DiagnosticLogger.log("[WEB CHAPTER WEBVIEW START]", "Starting rendered fallback")
                webView().also {
                    DiagnosticLogger.log("[WEB CHAPTER WEBVIEW SUCCESS]", "Rendered fallback returned chapter content")
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                DiagnosticLogger.log(
                    "[WEB CHAPTER WEBVIEW FAILURE]",
                    "${error.javaClass.simpleName}: ${error.message}",
                )
                throw IOException(
                    "HTTP chapter loading failed and the rendered WebView fallback also failed.",
                    error,
                ).apply { addSuppressed(httpFailure) }
            }
        }
    }
}
