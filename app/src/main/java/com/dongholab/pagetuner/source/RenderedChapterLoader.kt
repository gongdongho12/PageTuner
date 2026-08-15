package com.dongholab.pagetuner.source

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RenderedChapter(
    val title: String,
    val paragraphs: List<String>,
)

fun interface RenderedChapterLoader {
    suspend fun loadChapter(url: String, chapterNumber: Int): RenderedChapter
}

/**
 * Runtime bridge used by sources created outside Compose. The application installs one
 * process-wide loader backed by the application Context.
 */
object WebNovelPageRuntime {
    @Volatile
    var renderedChapterLoader: RenderedChapterLoader? = null
        private set

    fun install(context: Context) {
        if (renderedChapterLoader == null) {
            synchronized(this) {
                if (renderedChapterLoader == null) {
                    renderedChapterLoader = AndroidWebViewChapterLoader(context.applicationContext)
                }
            }
        }
    }
}

/**
 * Loads only pages whose chapter body is produced after JavaScript hydration. It never exposes
 * the WebView in the UI and returns structured text instead of passing rendered HTML downstream.
 */
class AndroidWebViewChapterLoader(
    context: Context,
) : RenderedChapterLoader {
    private val appContext = context.applicationContext

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun loadChapter(url: String, chapterNumber: Int): RenderedChapter =
        withTimeout(RENDER_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    if (!continuation.isActive) return@post

                    val webView = runCatching { WebView(appContext) }.getOrElse { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                        return@post
                    }
                    var completed = false
                    var pollingStarted = false
                    val startedAt = System.currentTimeMillis()

                    fun cleanUp() {
                        runCatching { webView.stopLoading() }
                        runCatching { webView.removeAllViews() }
                        runCatching { webView.destroy() }
                    }

                    fun fail(error: Throwable) {
                        if (completed) return
                        completed = true
                        cleanUp()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    fun succeed(chapter: RenderedChapter) {
                        if (completed) return
                        completed = true
                        cleanUp()
                        if (continuation.isActive) continuation.resume(chapter)
                    }

                    val extractionScript = buildExtractionScript(chapterNumber)
                    val poll = object : Runnable {
                        override fun run() {
                            if (completed || !continuation.isActive) return
                            if (System.currentTimeMillis() - startedAt >= RENDER_TIMEOUT_MS - 500L) {
                                fail(IOException("Timed out waiting for rendered chapter $chapterNumber."))
                                return
                            }
                            runCatching { webView.evaluateJavascript(extractionScript) { rawResult ->
                                if (completed || !continuation.isActive) return@evaluateJavascript
                                val parsed = parseJavascriptResult(rawResult)
                                if (parsed != null && parsed.paragraphs.joinToString(" ").length >= MIN_CONTENT_CHARS) {
                                    succeed(parsed)
                                } else {
                                    handler.postDelayed(this, POLL_INTERVAL_MS)
                                }
                            } }.onFailure(::fail)
                        }
                    }

                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.loadsImagesAutomatically = false
                    webView.settings.blockNetworkImage = true
                    webView.settings.allowFileAccess = false
                    webView.settings.allowContentAccess = false
                    webView.settings.userAgentString =
                        "${webView.settings.userAgentString} PageTurner/1.0"
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            pollingStarted = false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!pollingStarted && !completed) {
                                pollingStarted = true
                                handler.post(poll)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                fail(IOException("Unable to render chapter page: ${error?.description ?: "unknown WebView error"}"))
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        handler.post {
                            if (!completed) {
                                completed = true
                                cleanUp()
                            }
                        }
                    }
                    runCatching {
                        webView.loadUrl(url, mapOf("Accept-Language" to "en-US,en;q=0.9"))
                    }.onFailure(::fail)
                }
            }
        }

    private fun parseJavascriptResult(rawResult: String?): RenderedChapter? {
        if (rawResult.isNullOrBlank() || rawResult == "null") return null
        return runCatching {
            val decoded = JSONArray("[$rawResult]").optString(0)
            val json = JSONObject(decoded)
            if (!json.optBoolean("ready")) return null
            val rawParagraphs = json.optJSONArray("paragraphs") ?: return null
            val paragraphs = buildList {
                for (index in 0 until rawParagraphs.length()) {
                    rawParagraphs.optString(index)
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
            RenderedChapter(
                title = json.optString("title").trim(),
                paragraphs = paragraphs,
            )
        }.getOrNull()
    }

    private fun buildExtractionScript(chapterNumber: Int): String = """
        (function() {
          const root = document.querySelector('#chapter-$chapterNumber');
          if (!root) return JSON.stringify({ready:false});
          const paragraphs = Array.from(root.querySelectorAll('.wtr-line'))
            .map(function(node) { return (node.innerText || node.textContent || '').trim(); })
            .filter(function(text) { return text.length > 0; });
          const candidates = Array.from(root.querySelectorAll('h1,h2,h3,h4,[class*="font-bold"],[class*="font-semibold"]'))
            .map(function(node) { return (node.innerText || node.textContent || '').trim(); })
            .filter(function(text) { return /^Chapter\s+\d+/i.test(text) && text.length < 300; });
          return JSON.stringify({
            ready: paragraphs.join(' ').length >= $MIN_CONTENT_CHARS,
            title: candidates[0] || document.title || 'Chapter $chapterNumber',
            paragraphs: paragraphs
          });
        })();
    """.trimIndent()

    private companion object {
        const val RENDER_TIMEOUT_MS = 25_000L
        const val POLL_INTERVAL_MS = 250L
        const val MIN_CONTENT_CHARS = 100
    }
}
