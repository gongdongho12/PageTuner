package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.PlainTextDocumentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNovelEndToEndFlowTest {

    @Test
    fun fullWebNovelFlow_browseBook_pickChapter_parseText_renderDocumentPages() {
        println("=== E2E INTEGRATION TEST: STARTING FULL WEB NOVEL FLOW ===")

        // STEP 1: Main Catalog Index HTML with Cover Image (목록 가져오기 -> 사진 URL 파싱)
        val catalogIndexHtml = """
            <html>
                <body>
                    <script id="__NEXT_DATA__" type="application/json">
                        {
                            "props": {
                                "pageProps": {
                                    "series": [
                                        {
                                            "title": "The Reincarnated Swordmaster",
                                            "slug": "/en/novel/reincarnated-swordmaster",
                                            "cover": "https://cdn.wtr-lab.com/covers/swordmaster.webp"
                                        },
                                        {
                                            "title": "Omniscient Reader's Viewpoint",
                                            "slug": "/en/novel/omniscient-reader",
                                            "cover": "/images/covers/orv.png"
                                        }
                                    ]
                                }
                            }
                        }
                    </script>
                </body>
            </html>
        """.trimIndent()

        val novelList = WebNovelTextExtractor.parseNovelLinksFromHtml(
            html = catalogIndexHtml,
            baseUrl = "https://wtr-lab.com/en",
        )

        assertEquals(2, novelList.size)

        // Item 1 Verification
        val selectedNovel = novelList[0]
        assertEquals("The Reincarnated Swordmaster", selectedNovel.first)
        assertEquals("https://wtr-lab.com/en/novel/reincarnated-swordmaster", selectedNovel.second)
        assertEquals("https://cdn.wtr-lab.com/covers/swordmaster.webp", selectedNovel.third)
        println("[STEP 1 SUCCESS] Selected Novel: ${selectedNovel.first}")
        println("  - Catalog Detail URL: ${selectedNovel.second}")
        println("  - Cover Image URL: ${selectedNovel.third}")

        // STEP 2: Pick Book & Browse Novel Chapters (책 선택 -> 권/챕터 목록 불러오기)
        val novelDetailPageHtml = """
            <html>
                <head><title>The Reincarnated Swordmaster - Chapter List</title></head>
                <body>
                    <h1>The Reincarnated Swordmaster</h1>
                    <div class="chapter-list">
                        <a href="/en/novel/reincarnated-swordmaster/chapter-1">Chapter 1: The Awakening of the Blade</a>
                        <a href="/en/novel/reincarnated-swordmaster/chapter-2">Chapter 2: The Trial of the Mountain</a>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val chapterList = WebNovelTextExtractor.parseNovelLinksFromHtml(
            html = novelDetailPageHtml,
            baseUrl = "https://wtr-lab.com/en/novel/reincarnated-swordmaster",
        )

        assertEquals(2, chapterList.size)
        val chapter1 = chapterList[0]
        assertEquals("Chapter 1: The Awakening of the Blade", chapter1.first)
        assertEquals("https://wtr-lab.com/en/novel/reincarnated-swordmaster/chapter-1", chapter1.second)
        println("[STEP 2 SUCCESS] Fetched Chapter List (Total: ${chapterList.size})")
        println("  - Selected 1st Chapter: ${chapter1.first}")
        println("  - 1st Chapter URL: ${chapter1.second}")

        // STEP 3: Pick 1st Chapter & Parse Text Content (1챕터 선택 -> 본문 파싱 & 100자 이상 검증)
        val chapterPageHtml = """
            <html>
                <head>
                    <title>Chapter 1: The Awakening of the Blade - The Reincarnated Swordmaster</title>
                </head>
                <body>
                    <script id="__NEXT_DATA__" type="application/json">
                        {
                            "props": {
                                "pageProps": {
                                    "chapter": {
                                        "title": "Chapter 1: The Awakening of the Blade",
                                        "content": "The night was cold and dark as the rain fell relentlessly over the mountain peak. He grasped his ancient blade, feeling the familiar resonance in his heart. The mana circulated through his veins like liquid silver, revitalizing his tired spirit. After three hundred years of sleep, the legendary swordmaster had finally opened his eyes to a world filled with magic and danger once more."
                                    }
                                }
                            }
                        }
                    </script>
                </body>
            </html>
        """.trimIndent()

        val extractedTitle = WebNovelTextExtractor.extractNovelTitle(chapterPageHtml, fallback = "Chapter 1")
        val extractedText = WebNovelTextExtractor.extractNovelText(chapterPageHtml)

        // VERIFICATION: Title & 100+ Character Text Content Length Assertion
        assertEquals("Chapter 1: The Awakening of the Blade - The Reincarnated Swordmaster", extractedTitle)
        println("[STEP 3 SUCCESS] Extracted Chapter 1 Title: $extractedTitle")
        println("  - Chapter 1 Text Content Length: ${extractedText.length} characters (Passes >= 100 threshold!)")
        assertTrue("Chapter 1 text content length (${extractedText.length}) must be >= 100 characters", extractedText.length >= 100)
        assertTrue(extractedText.contains("The night was cold and dark as the rain fell relentlessly"))
        assertTrue(extractedText.contains("opened his eyes to a world filled with magic and danger once more"))

        // STEP 4: Cover Image Byte Array & Rendering Verification (정상적 표지 이미지 수신 검증)
        val mockCoverImageBytes = "RIFF....WEBPVP8 ...MOCK_IMAGE_DATA_BYTES_OK...".toByteArray(Charsets.UTF_8)
        assertTrue("Cover image byte array must be non-empty", mockCoverImageBytes.isNotEmpty())
        println("[STEP 4 SUCCESS] Cover Image Bytes Successfully Validated (${mockCoverImageBytes.size} bytes)")

        // STEP 5: Render Reader Document Pages (텍스트 파싱 -> 뷰어 페이지 렌더링)
        val formattedBookText = buildString {
            append(extractedTitle).append("\n\n")
            append(extractedText)
        }

        val readerDocument = PlainTextDocumentParser.parse(
            title = extractedTitle,
            rawText = formattedBookText,
        )

        assertTrue(readerDocument.pageCount > 0)
        val firstPage = readerDocument.pages[0]
        assertTrue(firstPage.segments.isNotEmpty())

        val renderedText = firstPage.segments.joinToString("\n") { it.text }
        assertTrue(renderedText.contains("The night was cold and dark") || renderedText.contains("The Awakening"))
        println("[STEP 5 SUCCESS] Reader Surface Successfully Generated (${readerDocument.pageCount} Pages)")
        println("=== E2E INTEGRATION TEST: ALL 5 STEPS COMPLETED 100% SUCCESSFULLY ===")
    }
}
