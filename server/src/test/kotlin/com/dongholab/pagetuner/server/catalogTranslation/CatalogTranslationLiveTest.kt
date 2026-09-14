package com.dongholab.pagetuner.server.catalogTranslation

import com.dongholab.pagetuner.core.translation.CatalogTranslationEntry
import com.dongholab.pagetuner.server.workflow.WorkflowProviders
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** This class belongs only to the explicitly configured catalogTranslationLiveTest task. */
@Tag("live-catalog-translation")
class CatalogTranslationLiveTest {
    @Test fun sharedCatalogAndRuntimeTranslateAnActualShortGoogleTitle() = runBlocking {
        check(System.getenv("RUN_LIVE_CATALOG_TRANSLATION_TESTS") == "1") { "Explicit catalog live-test opt-in is required." }
        val request = CatalogTranslationRequest(UUID.randomUUID(), listOf(CatalogTranslationEntry("owned-title", "A traveler opens the door", null)), sourceLanguage = "en")
        val result = RuntimeCatalogTextTranslator(WorkflowProviders()).translate(request) { _, _ -> }
        assertEquals(1, result.size); assertEquals("owned-title", result.single().key)
        assertTrue(result.single().title.any { it in '가'..'힣' })
        assertNotEquals(request.items.single().title, result.single().title)
        println("LIVE_CATALOG_TRANSLATION provider=GoogleWeb titles=1 sharedCatalog=true sharedRuntime=true")
    }
}
