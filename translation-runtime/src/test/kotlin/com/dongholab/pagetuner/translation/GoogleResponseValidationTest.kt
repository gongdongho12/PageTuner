package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.TextSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GoogleResponseValidationTest {
    @Test fun rejectsExtraDuplicateAndMissingHtmlAnchors() {
        for (response in listOf(
            """[["<a i=0>첫째</a><a i=1>둘째</a><a i=2>추가</a>"]]""",
            """[["<a i=0>첫째</a><a i=0>반복</a><a i=1>둘째</a>"]]""",
            """[["<a i=0>첫째</a>"]]""",
            """[[["첫째","둘째","추가"]]]""",
        )) assertTrue(GoogleWebTranslateHtmlResponseParser.parse(response, 2).isFailure)
    }

    @Test fun refusesMalformedPublicSentenceInsteadOfReturningPartialText() {
        assertTrue(GoogleWebTranslateTextResponseParser.parse("""[[["첫째","First"],null,["둘째","Second"]]]""").isFailure)
        assertTrue(GoogleWebTranslateTextResponseParser.parse("""[[["첫째","First"],[null,"Second"]]]""").isFailure)
    }

    @Test fun cloudBlankTranslationIsRejectedBeforeReturning() = runTest {
        val provider = GoogleCloudTranslationProvider("test", LlmHttpTransport { _, _, _ -> """{"data":{"translations":[{"translatedText":" "}]}}""" })
        val error = runCatching { provider.translate(TranslationRequest("en", "ko", listOf(TextSegment("p", 0, 0, "Source")))) }.exceptionOrNull()
        assertEquals(TranslationProviderErrorKind.ResponseFormat, (error as TranslationProviderException).failure.kind)
    }
}
