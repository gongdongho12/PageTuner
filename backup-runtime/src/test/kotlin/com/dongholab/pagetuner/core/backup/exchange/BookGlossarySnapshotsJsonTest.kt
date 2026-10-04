package com.dongholab.pagetuner.core.backup.exchange

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BookGlossarySnapshotsJsonTest {
    private val workspace = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "contracts/fixtures/book-glossary-snapshots-v1.json").isFile }
    private fun raw() = File(workspace, "contracts/fixtures/book-glossary-snapshots-v1.json").readText()
    private fun fixture() = BookGlossarySnapshotsJson.decode(raw())
    private fun document() = ExchangeDocument("copy-id", "Book", "Chapter", "en", "local",
        listOf(ExchangeParagraph("p-1", "Text")), glossary = listOf(ExchangeGlossaryEntry("Legacy", "기존")),
        extensionsJson = "{\"documentIdentity\":{\"version\":99,\"passive\":true},\"other\":{\"value\":\"keep 🌏\"}}")
    private fun reject(block: () -> Unit) = assertThrows(Exception::class.java, block)

    @Test fun sharedFixtureKeepsAllFieldsLanguagesAndPresenceThroughJsonAndZip() {
        val value = fixture()
        assertEquals(listOf("ko", "en", "ja", "zh-hans"), value.snapshots.map { it.identity.targetLanguage })
        assertEquals(listOf(BookGlossarySnapshotPresence.PRESENT, BookGlossarySnapshotPresence.PRESENT,
            BookGlossarySnapshotPresence.DELETED, BookGlossarySnapshotPresence.ABSENT), value.snapshots.map { it.presence })
        assertEquals(emptyList<Any>(), value.snapshots[1].entries)
        assertNull(value.snapshots[2].entries); assertNull(value.snapshots[3].entries)
        assertEquals("original-entry-2:人物🌏", value.snapshots[0].entries!![0].id)
        assertEquals(" Alice ", value.snapshots[0].entries!![0].sourceTerm)
        assertEquals(" 앨리스 ", value.snapshots[0].entries!![0].translatedTerm)
        assertEquals(" 아리 ", value.snapshots[0].entries!![0].displayTerm)
        assertEquals(value, BookGlossarySnapshotsJson.decode(BookGlossarySnapshotsJson.encode(value).toString()))
        val doc = BookGlossarySnapshotsJson.withSnapshots(document(), value)
        val archive = LibraryExchangeCodec.write(LibraryExchangePackage("2026-10-04T00:00:00Z", listOf(doc)))
        assertEquals(value, BookGlossarySnapshotsJson.fromDocument(LibraryExchangeCodec.read(archive).documents.single()))
    }

    @Test fun replacementPreservesLegacyValuesAndAllAllowedSiblingMetadataWithoutAliasingTheInput() {
        val original = document()
        val changed = BookGlossarySnapshotsJson.withSnapshots(original, fixture())
        assertEquals(original.copy(extensionsJson = changed.extensionsJson), changed)
        val old = JSONObject(original.extensionsJson!!); val extensions = JSONObject(changed.extensionsJson!!)
        assertTrue(old.getJSONObject("documentIdentity").similar(extensions.getJSONObject("documentIdentity")))
        assertTrue(old.getJSONObject("other").similar(extensions.getJSONObject("other")))
        assertFalse(old.has(BookGlossarySnapshotsJson.EXTENSION_KEY))
        val empty = BookGlossarySnapshots(snapshots = emptyList())
        assertEquals(empty, BookGlossarySnapshotsJson.fromDocument(BookGlossarySnapshotsJson.withSnapshots(changed, empty)))
        assertEquals(fixture(), BookGlossarySnapshotsJson.fromDocument(changed))
    }

    @Test fun missingExtensionDiffersFromInvalidAndUnknownVersionsStayPassiveOnZipRoundTrip() {
        assertNull(BookGlossarySnapshotsJson.fromDocument(document().copy(extensionsJson = null)))
        assertNull(BookGlossarySnapshotsJson.fromDocument(document()))
        val values = listOf(JSONObject().put("version", 2).put("snapshots", JSONArray()), JSONObject.NULL, "unexpected")
        for (value in values) {
            val doc = document().copy(extensionsJson = JSONObject().put(BookGlossarySnapshotsJson.EXTENSION_KEY, value).toString())
            val restored = LibraryExchangeCodec.read(LibraryExchangeCodec.write(
                LibraryExchangePackage("2026-10-04T00:00:00Z", listOf(doc)))).documents.single()
            assertTrue(JSONObject(doc.extensionsJson!!).similar(JSONObject(restored.extensionsJson!!)))
            reject { BookGlossarySnapshotsJson.fromDocument(restored) }
        }
    }

    @Test fun rawJsonRejectsDuplicatesTrailingDataCoercionAndUnsafeNumbers() {
        val raw = raw()
        val prefix = "{\"version\":1,\"snapshots\":[]}"
        listOf("{\"version\":1,\"version\":1,\"snapshots\":[]}",
            "{\"version\":1,\"snapshots\":[],\"snap\\u0073hots\":[]}", prefix + "{}", prefix + " garbage",
            raw.replace("\"caseSensitive\": true", "\"caseSensitive\": \"true\""),
            raw.replace("\"enabled\": false", "\"enabled\": 0"),
            raw.replace("\"sourceTerm\": \"River\"", "\"sourceTerm\": 42"),
            prefix.replace(":1,", ":1e999,"), prefix.replace(":1,", ":1e-999,"),
            prefix.replace(":1,", ":9007199254740992,"), prefix.replace(":1,", ":\"1\","),
            prefix.replace(":1,", ":1.5,")).forEach { reject { BookGlossarySnapshotsJson.decode(it) } }
        for (spelling in listOf("1", "1.0", "1e0")) {
            assertEquals(BookGlossarySnapshots(snapshots = emptyList()),
                BookGlossarySnapshotsJson.decode(prefix.replace(":1,", ":$spelling,")))
        }
        reject { BookGlossarySnapshotsJson.fromDocument(document().copy(
            extensionsJson = "{\"bookGlossarySnapshots\":$prefix,\"bookGlossarySnapshots\":$prefix}")) }
    }

    @Test fun unknownOrMissingFieldsCannotTransferAccountStateOrDefaultEntryAttributes() {
        val original = BookGlossarySnapshotsJson.encode(fixture())
        listOf("accountId", "origin", "version", "expectedVersion", "mutationId", "outbox", "authorization").forEach { field ->
            val json = JSONObject(original.toString())
            json.getJSONArray("snapshots").getJSONObject(0).put(field, "not-portable")
            reject { BookGlossarySnapshotsJson.decode(json) }
        }
        for (field in listOf("id", "sourceTerm", "translatedTerm", "displayTerm", "kind", "caseSensitive", "enabled")) {
            val json = JSONObject(original.toString())
            json.getJSONArray("snapshots").getJSONObject(0).getJSONArray("entries").getJSONObject(0).remove(field)
            reject { BookGlossarySnapshotsJson.decode(json) }
        }
        listOf("presence", "entries", "providerId", "bookId", "targetLanguage").forEach { field ->
            val json = JSONObject(original.toString()); json.getJSONArray("snapshots").getJSONObject(0).remove(field)
            reject { BookGlossarySnapshotsJson.decode(json) }
        }
    }

    @Test fun duplicateScopesEntriesAndInvalidPresenceValuesAreRejected() {
        fun mutate(change: (JSONObject) -> Unit) {
            val json = BookGlossarySnapshotsJson.encode(fixture()); change(json)
            reject { BookGlossarySnapshotsJson.decode(json) }
        }
        mutate { it.getJSONArray("snapshots").put(it.getJSONArray("snapshots").getJSONObject(0)) }
        mutate { json -> val entries = json.getJSONArray("snapshots").getJSONObject(0).getJSONArray("entries"); entries.put(entries.getJSONObject(0)) }
        mutate { it.getJSONArray("snapshots").getJSONObject(0).put("entries", JSONObject.NULL) }
        mutate { it.getJSONArray("snapshots").getJSONObject(2).put("entries", JSONArray()) }
        mutate { it.getJSONArray("snapshots").getJSONObject(3).put("entries", JSONArray()) }
        mutate { it.getJSONArray("snapshots").getJSONObject(0).put("presence", "PRESENT") }
        mutate { it.getJSONArray("snapshots").getJSONObject(0).put("targetLanguage", "KO") }
        mutate { it.getJSONArray("snapshots").getJSONObject(0).getJSONArray("entries").getJSONObject(0).put("kind", "term") }
        mutate { it.getJSONArray("snapshots").getJSONObject(0).getJSONArray("entries").getJSONObject(0).put("sourceTerm", "bad\ud800") }
        val rawSurrogate = raw().replace("River", "bad\ud800")
        reject { BookGlossarySnapshotsJson.decode(rawSurrogate) }
    }

    @Test fun byteLimitCountsTheWholeExtensionsObjectAndFailsWithoutTruncatingValidEntries() {
        val snapshot = fixture().snapshots[0]
        val largeEntries = (0 until 500).map { snapshot.entries!![0].copy(id = "id-$it", sourceTerm = "原".repeat(200),
            translatedTerm = "번".repeat(200), displayTerm = "別".repeat(200)) }
        val large = BookGlossarySnapshots(snapshots = listOf(snapshot.copy(entries = largeEntries)))
        BookGlossarySnapshotValidation.validate(large) // Structural limits alone do not imply byte capacity.
        reject { BookGlossarySnapshotsJson.encode(large) }
        assertEquals(500, large.snapshots.single().entries!!.size)
        val siblings = document().copy(extensionsJson = JSONObject().put("other", "x".repeat(
            LibraryExchangeLimits.EXTENSIONS_BYTES - 100)).toString())
        reject { BookGlossarySnapshotsJson.withSnapshots(siblings, fixture()) }
        assertFalse(JSONObject(siblings.extensionsJson!!).has(BookGlossarySnapshotsJson.EXTENSION_KEY))
        reject { BookGlossarySnapshotsJson.decode(" ".repeat(LibraryExchangeLimits.EXTENSIONS_BYTES) + raw()) }
        reject { BookGlossarySnapshotsJson.withSnapshots(document().copy(extensionsJson = "{\"password\":\"secret\"}"), fixture()) }
    }

    @Test fun malformedUnicodeInUnknownSiblingsIsRejectedBeforeItCanBeReplacedDuringUtf8Encoding() {
        for (raw in listOf("{\"other\":\"bad\ud800\"}", "{\"other\":\"bad\\ud800\"}",
            "{\"bad\udc00\":true}", "{\"bad\\udc00\":true}", "{\"other\":[{\"value\":\"\\ud800\"}]}")) {
            reject { BookGlossarySnapshotsJson.withSnapshots(document().copy(extensionsJson = raw), fixture()) }
        }
    }
}
