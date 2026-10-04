package com.dongholab.pagetuner.server.translation

import com.dongholab.pagetuner.core.translation.StoredTranslation
import com.dongholab.pagetuner.core.translation.TranslationSaveResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest

@JsonTest
class TranslationWireContractTest {
    @Autowired lateinit var objectMapper: ObjectMapper

    @Test
    fun `shared request fixture produces the shared stored response without contract drift`() {
        val requestJson = fixture("save-request.json")
        val request = objectMapper.treeToValue(requestJson, SaveTranslationRequest::class.java)
        assertEquals(requestJson, objectMapper.valueToTree<JsonNode>(request))

        val responseJson = fixture("stored-response.json")
        val stored = StoredTranslation(
            responseJson["recordId"].asText(),
            request.toArtifact(),
            responseJson["createdAt"].asText(),
        )
        val response = TranslationResponse.from(TranslationSaveResult(stored, created = true))
        assertEquals(responseJson, objectMapper.valueToTree<JsonNode>(response))
        assertEquals(response, objectMapper.treeToValue(responseJson, TranslationResponse::class.java))
    }

    @Test
    fun `library fixture preserves metadata and omits paragraph bodies`() {
        val json = fixture("list-response.json")
        val response = objectMapper.treeToValue(json, TranslationListResponse::class.java)
        // Compare the actual wire JSON: valueToTree retains JVM LongNode vs IntNode distinctions.
        assertEquals(json, objectMapper.readTree(objectMapper.writeValueAsBytes(response)))
        val stored = fixture("stored-response.json")
        val summary = json["items"][0]
        summary.fieldNames().forEachRemaining { name ->
            if (name != "paragraphCount") assertEquals(stored[name], summary[name], name)
        }
        assertEquals(stored["paragraphs"].size(), response.items.single().paragraphCount)
        assertFalse(summary.has("paragraphs"))
    }

    private fun fixture(name: String): JsonNode = requireNotNull(
        javaClass.getResourceAsStream("/translation-v1/$name"),
    ).use { objectMapper.readTree(it) }
}
