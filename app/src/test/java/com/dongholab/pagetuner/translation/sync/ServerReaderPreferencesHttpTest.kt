package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.settings.ListLayoutMode
import com.dongholab.pagetuner.settings.ReaderSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerReaderPreferencesHttpTest {
    private val defaults = ReaderSettings().sharedPreferences()
    private val mutation = ReaderPreferencesMutation(0, NotesFixture.noteId, defaults)
    private fun view(version: Long = 1, preferences: SharedReaderPreferences = defaults) =
        ServerReaderPreferences(version, preferences.takeIf { version > 0 }, NotesFixture.time.takeIf { version > 0 })
    private fun response(value: JSONObject, status: Int = 200) = TranslationStoreHttpResponse(status, body = value.toString())

    @Test fun getAndCasPutUseAuthenticatedContractAndCsrfWithoutDeviceOnlyFields() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = NotesFixture.connection { requests += it; response(ServerReaderPreferencesJson.encode(view())) }.client
        assertEquals(defaults, client.readerPreferences().preferences)
        assertEquals(1L, client.saveReaderPreferences(mutation).version)
        assertEquals(listOf("GET", "PUT"), requests.map { it.method })
        assertTrue(requests.all { it.url.endsWith("/api/v1/reader-preferences") })
        val request = requests.last()
        assertTrue(request.headers["Authorization"]!!.startsWith("Basic "))
        assertEquals("csrf", request.headers["X-CSRF-TOKEN"]); assertEquals("JSESSIONID=session", request.headers["Cookie"])
        val payload = JSONObject(request.body!!)
        assertEquals(setOf("expectedVersion", "mutationId", "preferences"), payload.keys().asSequence().toSet())
        assertEquals(setOf("fontSize", "lineHeightPercent", "pageMargin", "touchDirection", "listMode"),
            payload.getJSONObject("preferences").keys().asSequence().toSet())
    }

    @Test fun malformedVersionRangesTimestampAndAckCannotBecomeSettings() {
        val invalid = listOf(
            ServerReaderPreferencesJson.encode(view()).put("version", "1"),
            ServerReaderPreferencesJson.encode(view()).put("version", 1.5),
            ServerReaderPreferencesJson.encode(view()).put("updatedAt", "2026-02-30T00:00:00Z"),
            ServerReaderPreferencesJson.encode(view()).put("preferences", ServerReaderPreferencesJson.encode(defaults).put("lineHeightPercent", 135.5)),
            ServerReaderPreferencesJson.encode(view()).put("preferences", ServerReaderPreferencesJson.encode(defaults).put("pageMargin", 49)),
            ServerReaderPreferencesJson.encode(view()).put("preferences", JSONObject.NULL),
            ServerReaderPreferencesJson.encode(view(0)).put("preferences", ServerReaderPreferencesJson.encode(defaults)),
        )
        invalid.forEach { payload ->
            val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
                NotesFixture.connection { response(payload) }.client.readerPreferences()
            } }
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        }
        listOf(view(2), view(1, defaults.copy(fontSize = 19))).forEach { badAck ->
            val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
                NotesFixture.connection { response(ServerReaderPreferencesJson.encode(badAck)) }.client.saveReaderPreferences(mutation)
            } }
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        }
    }

    @Test fun conflictAndExhaustedAreDistinctAndRetryAfterIsBounded() {
        val error = assertThrows(ReaderPreferencesConflict::class.java) { runBlocking {
            NotesFixture.connection { response(JSONObject().put("code", "READER_PREFERENCES_CONFLICT")
                .put("current", ServerReaderPreferencesJson.encode(view(2))), 409) }.client.saveReaderPreferences(mutation)
        } }
        assertEquals(2L, error.current.version)
        val exhausted = assertThrows(TranslationStoreException::class.java) { runBlocking {
            NotesFixture.connection { response(JSONObject().put("code", "READER_PREFERENCES_EXHAUSTED"), 409) }.client.saveReaderPreferences(mutation)
        } }
        assertEquals(TranslationStoreFailure.CONFLICT, exhausted.failure)
        listOf("120" to 120L, "0" to 60L, "9999999999999" to 60L).forEach { (header, expected) ->
            val limited = assertThrows(ReaderPreferencesRateLimited::class.java) { runBlocking {
                NotesFixture.connection { TranslationStoreHttpResponse(429, mapOf("retry-after" to listOf(header))) }.client.readerPreferences()
            } }
            assertEquals(expected, limited.retryAfterSeconds)
        }
    }

    @Test fun invalidLocalPayloadNeverSendsRequestAndMaximumValidVersionIsAccepted() {
        var calls = 0
        val client = NotesFixture.connection { calls++; response(ServerReaderPreferencesJson.encode(view(MaxReadingVersion))) }.client
        listOf(mutation.copy(expectedVersion = MaxReadingVersion), mutation.copy(mutationId = "legacy"),
            mutation.copy(preferences = defaults.copy(fontSize = 37)), mutation.copy(preferences = defaults.copy(lineHeightPercent = 109)),
            mutation.copy(preferences = defaults.copy(touchDirection = "arbitrary"))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.saveReaderPreferences(invalid) } }
        }
        assertEquals(0, calls)
        assertEquals(MaxReadingVersion, runBlocking { client.saveReaderPreferences(mutation.copy(expectedVersion = MaxReadingVersion - 1)) }.version)
    }

    @Test fun journalRoundTripPreservesUncertainRequestChoiceAndCooldownWithoutCredentials() {
        val account = serverReadingAccountKey("https://reader.example", "reader")
        val source = DeviceReaderPreferences(true, view(), mutation, defaults.copy(fontSize = 25), view(4), 100_000)
        val json = encodeDeviceReaderPreferences(source, account)
        assertEquals(source, decodeDeviceReaderPreferences(JSONObject(json.toString()), account))
        assertFalse(json.toString().contains("reader.example")); assertFalse(json.toString().contains("password"))
        assertThrows(IllegalArgumentException::class.java) { decodeDeviceReaderPreferences(json, "0".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) {
            decodeDeviceReaderPreferences(JSONObject(json.toString()).put("pending", JSONObject.NULL), account)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeDeviceReaderPreferences(JSONObject(json.toString()).put("enabled", false), account)
        }
    }

    @Test fun accountOverlayCoversUnionRangeAndLeavesTranslationAndDeviceRenderingSettingsUnchanged() {
        val device = ReaderSettings(sourceLanguage = "ja", targetLanguage = "en", llmEndpoint = "https://device.example",
            readingWordsPerMinute = 420)
        val shared = SharedReaderPreferences(36, 240, 0, "buttons-only", "scroll")
        val overlay = shared.overlay(device)
        assertEquals(shared, overlay.sharedPreferences()); assertEquals(36, overlay.readerFontSizeSp)
        assertEquals(PageTurnMode.ButtonsOnly, overlay.pageTurnMode); assertEquals(ListLayoutMode.Scroll, overlay.listLayoutMode)
        assertEquals(device.llmEndpoint, overlay.llmEndpoint); assertEquals(device.pdfFitMode, overlay.pdfFitMode)
        assertEquals("en", overlay.targetLanguage); assertEquals(420, overlay.readingWordsPerMinute)
        assertEquals(device, device.sharedPreferences().overlay(device))
        assertEquals(SharedReaderPreferences(14, 110, 48, "left-next", "paged"),
            ReaderPreferencesPatch(14, 110, 48, "left-next", "paged").apply(shared))
    }
}
