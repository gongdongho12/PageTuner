package com.dongholab.pagetuner.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNovelTextExtractorTest {
    @Test
    fun extractNovelText_stripsHtmlTagsAndExtractsCleanText() {
        val rawHtml = """
            <html>
                <head>
                    <title>WTR-Lab Chapter 1</title>
                    <style>body { color: red; }</style>
                    <script>console.log("ad script");</script>
                </head>
                <body>
                    <header><h1>Header Navigation</h1></header>
                    <div class="chapter-content">
                        <p>First paragraph of the web novel.&nbsp;It has clean text.</p>
                        <p>Second paragraph with &quot;quotes&quot; and &amp; symbols.</p>
                    </div>
                    <footer>Footer Ads</footer>
                </body>
            </html>
        """.trimIndent()

        val extracted = WebNovelTextExtractor.extractNovelText(rawHtml)

        assertTrue(extracted.contains("First paragraph of the web novel. It has clean text."))
        assertTrue(extracted.contains("Second paragraph with \"quotes\" and & symbols."))
        assertTrue(!extracted.contains("Header Navigation"))
        assertTrue(!extracted.contains("Footer Ads"))
        assertTrue(!extracted.contains("console.log"))
    }

    @Test
    fun extractNovelTitle_extractsTitleOrFallback() {
        val html = "<html><head><title>The Reincarnated Swordmaster - Chapter 1</title></head><body></body></html>"
        val title = WebNovelTextExtractor.extractNovelTitle(html, fallback = "Unknown Novel")

        assertEquals("The Reincarnated Swordmaster - Chapter 1", title)
    }
}
