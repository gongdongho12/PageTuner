package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit integration task, excluded from the ordinary offline unit suite. */
class GoogleWebTranslationLiveTest {
    @Test fun translatesARealParagraphThroughTheSharedNoKeyEngine() = runBlocking {
        check(System.getenv("RUN_LIVE_TRANSLATION_TESTS") == "1") {
            "Set RUN_LIVE_TRANSLATION_TESTS=1 to run the explicit Google Web network test."
        }
        val source = "The little girl opened the door and looked at the quiet garden."
        val chapter = ChapterContent(ChapterIdentity(BookIdentity("integration", "original-fiction"), "chapter-1"),
            "The garden", "en", listOf(ContentParagraph("opening", 0, source)))
        var checkpoint: String? = null
        val result = ChapterTranslationEngine().translate(chapter,
            TranslationSettings(TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML, "", sourceLanguage = "en", targetLanguage = "ko"),
            onParagraph = { id, text -> assertEquals("opening", id); checkpoint = text })
        assertEquals(1, result.size)
        assertEquals(checkpoint, result.single().text)
        assertNotEquals(source, result.single().text)
        assertTrue(result.single().text.any { it in '가'..'힣' })
    }
}
