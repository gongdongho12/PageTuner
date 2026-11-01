package com.dongholab.pagetuner.translation.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Structural S1 validation, without account I/O or a document-membership claim. */
class ServerReadingProgressJsonValidationTest {
    private val id = "abcdefab-cdef-4abc-8def-abcdefabcdef"
    private val mutationId = "12345678-abcd-4abc-8def-123456789abc"
    private val time = "2026-11-01T00:00:00Z"
    private fun anchor(id: String = "paragraph-one", offset: Any = 0) =
        JSONObject().put("paragraphId", id).put("characterOffset", offset)
    private fun view() = JSONObject().put("kind", "ORIGINAL").put("recordId", id).put("version", 1)
        .put("anchor", anchor()).put("updatedAt", time)
    private fun decode(value: JSONObject) = ServerReadingProgressJson.decode(value, "ORIGINAL", id)

    @Test fun responseAndAnchorUnknownOrMissingFieldsAreRejectedBeforeProjection() {
        assertThrows(IllegalArgumentException::class.java) { decode(view().put("future", true)) }
        assertThrows(IllegalArgumentException::class.java) { decode(view().put("anchor", anchor().put("page", 1))) }
        listOf("kind", "recordId", "version", "anchor", "updatedAt").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { decode(view().also { it.remove(key) }) }
        }
        listOf("paragraphId", "characterOffset").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerReadingProgressJson.anchor(anchor().also { it.remove(key) })
            }
        }
    }

    @Test fun mutationAndNestedAnchorRejectUnknownOrMissingFields() {
        fun mutation() = JSONObject().put("expectedVersion", 0).put("mutationId", mutationId).put("anchor", anchor())
        assertThrows(IllegalArgumentException::class.java) { ServerReadingProgressJson.mutation(mutation().put("queued", true)) }
        assertThrows(IllegalArgumentException::class.java) {
            ServerReadingProgressJson.mutation(mutation().put("anchor", anchor().put("revision", 1)))
        }
        listOf("expectedVersion", "mutationId", "anchor").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerReadingProgressJson.mutation(mutation().also { it.remove(key) })
            }
        }
        assertEquals(ServerReadingMutation(0, mutationId, ServerReadingAnchor("paragraph-one", 0)),
            ServerReadingProgressJson.mutation(mutation()))
    }

    @Test fun exactUnicodeCaseNormalizationAndWhitespaceArePreserved() {
        listOf("  P:é  e\u0301 🌏  ", "\ufeffP\u00a0", "\u200b", "Tag", "tag").forEach { paragraphId ->
            val expected = ServerReadingAnchor(paragraphId, 17)
            val json = ServerReadingProgressJson.encode(expected)
            assertEquals(paragraphId, json.getString("paragraphId"))
            assertEquals(expected, ServerReadingProgressJson.anchor(json))
            assertEquals(expected, decode(view().put("anchor", json)).anchor)
        }
    }

    @Test fun ecmaWhitespaceOnlyAndIsoControlsAreRejectedWithoutTrimming() {
        listOf("", " ", "\u00a0", "\u1680\u2000\u200a", "\u2028\u2029\u202f\u205f\u3000\ufeff",
            "\t", "p\n", "p\u007f", "p\u0085", "p\u009f").forEach { paragraphId ->
            assertThrows(IllegalArgumentException::class.java) { ServerReadingProgressJson.anchor(anchor(paragraphId)) }
            assertThrows(IllegalArgumentException::class.java) { ServerReadingProgressJson.encode(ServerReadingAnchor(paragraphId, 0)) }
        }
    }

    @Test fun unpairedSurrogatesAreRejectedButPairsCountAsTwoUtf16Units() {
        listOf("\ud800", "\udfff", "p\ud800x", "\ud800\ud800", "\udc00\ud800", "ok🌏\udc00").forEach { paragraphId ->
            assertThrows(IllegalArgumentException::class.java) { ServerReadingProgressJson.anchor(anchor(paragraphId)) }
            assertThrows(IllegalArgumentException::class.java) { ServerReadingProgressJson.encode(ServerReadingAnchor(paragraphId, 0)) }
        }
        val maximum = "🌏".repeat(100)
        assertEquals(200, maximum.length)
        assertEquals(maximum, ServerReadingProgressJson.anchor(anchor(maximum)).paragraphId)
        assertThrows(IllegalArgumentException::class.java) { ServerReadingProgressJson.anchor(anchor(maximum + "x")) }
    }

    @Test fun offsetsRemainExactNonnegativeIntValuesWithoutCoercion() {
        listOf(0, Int.MAX_VALUE).forEach { offset ->
            assertEquals(offset, ServerReadingProgressJson.anchor(anchor(offset = offset)).characterOffset)
        }
        listOf(-1, 2147483648L, 1.5, "1", true, JSONObject.NULL).forEach { offset ->
            assertThrows(IllegalArgumentException::class.java) { ServerReadingProgressJson.anchor(anchor(offset = offset)) }
        }
    }

    @Test fun canonicalLowercaseUuidScopeIsRequiredAndNeverNormalized() {
        assertEquals(id, decode(view()).recordId)
        assertThrows(IllegalArgumentException::class.java) { decode(view().put("recordId", id.uppercase())) }
        assertThrows(IllegalArgumentException::class.java) { ServerReadingProgressJson.decode(view(), "ORIGINAL", id.uppercase()) }
        assertThrows(IllegalArgumentException::class.java) { ServerReadingProgressJson.validateTarget("original", id) }
    }

    @Test fun absenceAndPositiveVersionsRetainTheirOriginalNullAndNumberContract() {
        val absent = view().put("version", 0).put("anchor", JSONObject.NULL).put("updatedAt", JSONObject.NULL)
        assertEquals(ServerReadingProgress("ORIGINAL", id, 0, null, null), decode(absent))
        assertEquals(MaxReadingVersion, decode(view().put("version", MaxReadingVersion)).version)
        listOf(view().put("version", 0), view().put("anchor", JSONObject.NULL), view().put("updatedAt", JSONObject.NULL),
            view().put("version", MaxReadingVersion + 1), view().put("version", 1.5), view().put("version", "1")).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { decode(value) }
        }
    }

    @Test fun anExistingJournalWithUnsupportedNestedFieldsIsRejectedWithoutProjectingItsData() {
        val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", id)
        val journal = encodeDeviceReadingProgress(target, DeviceReadingProgress(
            remote = ServerReadingProgress("ORIGINAL", id, 1, ServerReadingAnchor("p", 0), time),
            pending = ServerReadingMutation(1, mutationId, ServerReadingAnchor("p", 1)),
            retryAfterUntil = 12345,
        ))
        journal.getJSONObject("pending").put("unsupportedIntent", "retain for explicit recovery")
        val original = journal.toString()
        assertThrows(IllegalArgumentException::class.java) { decodeDeviceReadingProgress(journal, target) }
        assertEquals(original, journal.toString())
    }
}
