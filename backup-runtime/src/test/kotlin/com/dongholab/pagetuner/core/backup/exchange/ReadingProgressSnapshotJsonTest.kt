package com.dongholab.pagetuner.core.backup.exchange

import com.dongholab.pagetuner.core.content.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReadingProgressSnapshotJsonTest {
    private val workspace = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "contracts/fixtures/reading-progress-snapshot-v1.json").isFile }
    private data class Case(val document: ExchangeDocument, val json: JSONObject) {
        val snapshot get() = ReadingProgressSnapshotJson.decode(json)
    }
    private fun cases(): List<Case> {
        val array = JSONObject(File(workspace, "contracts/fixtures/reading-progress-snapshot-v1.json").readText()).getJSONArray("cases")
        return (0 until array.length()).map { i -> array.getJSONObject(i).let {
            Case(ExchangeJson.decode(it.getJSONObject("document").toString().toByteArray()), it.getJSONObject("snapshot"))
        } }
    }
    private fun original() = cases().first()
    private fun reject(block: () -> Unit) = assertThrows(Exception::class.java, block)
    private fun attach(document: ExchangeDocument, snapshot: Any) = document.copy(extensionsJson =
        JSONObject(document.extensionsJson ?: "{}").put(ReadingProgressSnapshotJson.EXTENSION_KEY, snapshot).toString())
    private fun zip(document: ExchangeDocument) = LibraryExchangeCodec.read(LibraryExchangeCodec.write(
        LibraryExchangePackage("2026-10-26T00:00:00Z", listOf(document)))).documents.single()

    @Test fun sharedFixturesRoundTripExactSnapshotAllLegacyFieldsAndEverySiblingThroughRealZip() {
        val fixtures = cases()
        assertEquals(8, fixtures.size)
        for (fixture in fixtures) {
            val source = fixture.document
            val before = ExchangeJson.encode(source)
            val snapshot = fixture.snapshot
            assertEquals(snapshot, ReadingProgressSnapshotJson.decode(fixture.json.toString()))
            assertTrue(fixture.json.similar(ReadingProgressSnapshotJson.encode(snapshot)))
            val attached = ReadingProgressSnapshotJson.withSnapshot(source, snapshot)
            assertEquals(source.copy(extensionsJson = attached.extensionsJson), attached)
            assertEquals(snapshot, ReadingProgressSnapshotJson.fromDocument(attached))
            val restored = zip(attached)
            assertEquals(snapshot, ReadingProgressSnapshotJson.fromDocument(restored))
            assertEquals(attached.copy(extensionsJson = restored.extensionsJson), restored)
            val old = JSONObject(source.extensionsJson ?: "{}")
            val current = JSONObject(restored.extensionsJson!!)
            old.keys().forEach { key -> assertTrue(JSONObject().put(key, old.get(key)).similar(JSONObject().put(key, current.get(key)))) }
            assertArrayEquals(before, ExchangeJson.encode(source))
            assertFalse(old.has(ReadingProgressSnapshotJson.EXTENSION_KEY))
        }
    }

    @Test fun missingDoesNotBecomeAbsentOrFallBackToLegacyPosition() {
        val source = original()
        assertNull(ReadingProgressSnapshotJson.fromDocument(source.document))
        assertNull(ReadingProgressSnapshotJson.fromDocument(source.document.copy(kind = "local", paragraphs = emptyList(),
            extensionsJson = "{\"documentIdentity\":{\"version\":99}}")))
        val absent = source.snapshot.copy(presence = ReadingProgressSnapshotPresence.ABSENT, anchor = null)
        val attached = ReadingProgressSnapshotJson.withSnapshot(source.document, absent)
        assertEquals(absent, ReadingProgressSnapshotJson.fromDocument(attached))
        assertEquals(source.document.position, attached.position)
        assertNotEquals(source.snapshot, absent)
    }

    @Test fun unknownBrokenOrConflictingOwnSnapshotRemainsGenericDataAndCannotBeReplaced() {
        val source = original()
        val values = listOf<Any>(JSONObject(source.json.toString()).put("version", 99), JSONObject.NULL, "snapshot",
            JSONObject(source.json.toString()).put("presence", "deleted"),
            JSONObject(source.json.toString()).put("anchor", JSONObject().put("paragraphId", "missing").put("characterOffset", 0)),
            JSONObject(source.json.toString()).apply { getJSONObject("identity").put("bookId", "another") })
        for (value in values) {
            val document = attach(source.document, value)
            val restored = zip(document)
            assertTrue(JSONObject(document.extensionsJson!!).similar(JSONObject(restored.extensionsJson!!)))
            reject { ReadingProgressSnapshotJson.fromDocument(restored) }
            reject { ReadingProgressSnapshotJson.withSnapshot(restored, source.snapshot) }
            assertEquals(source.document.position, restored.position)
        }
    }

    @Test fun rawDuplicateKeysTrailingInputNumericCoercionAndUnknownFieldsAreRejected() {
        val raw = ReadingProgressSnapshotJson.encode(original().snapshot).toString()
        val anchor = JSONObject(raw).getJSONObject("anchor").toString()
        listOf(raw.replaceFirst("{", "{\"version\":1,"), raw.replaceFirst("{", "{\"ver\\u0073ion\":1,"),
            raw.replace(anchor, anchor.replaceFirst("{", "{\"characterOffset\":0,")), raw + "{}", raw + " trailing",
            raw.replace("\"version\":1", "\"version\":\"1\""), raw.replaceFirst("{", "{\"serverVersion\":2,"))
            .forEach { reject { ReadingProgressSnapshotJson.decode(it) } }
        listOf("1", "1.0", "1e0").forEach { version ->
            assertEquals(original().snapshot, ReadingProgressSnapshotJson.decode(raw.replace("\"version\":1", "\"version\":$version")))
        }
        val duplicate = "{\"readingProgressSnapshot\":$raw,\"readingProgressSnapshot\":$raw}"
        reject { ReadingProgressSnapshotJson.fromDocument(original().document.copy(extensionsJson = duplicate)) }
        reject { ReadingProgressSnapshotJson.withSnapshot(original().document.copy(extensionsJson = duplicate), original().snapshot) }
    }

    @Test fun strictObjectOverloadAndOffsetBoundariesRejectLossyConversions() {
        fun invalid(change: (JSONObject) -> Unit) = reject { ReadingProgressSnapshotJson.decode(JSONObject(original().json.toString()).also(change)) }
        listOf<Any>(-1, 0.5, 2147483648L, 9007199254740991L, "0", true, JSONObject.NULL).forEach { value ->
            invalid { it.getJSONObject("anchor").put("characterOffset", value) }
        }
        invalid { it.getJSONObject("anchor").put("paragraphId", StringBuilder("p:1")) }
        invalid { it.put("anchor", mapOf("paragraphId" to "p:1", "characterOffset" to 0) as Any) }
        invalid { it.put("presence", StringBuilder("present")) }
        invalid { it.getJSONObject("identity").put("bookId", StringBuilder("book")) }
        val maximum = JSONObject(original().json.toString()).apply { getJSONObject("anchor").put("characterOffset", Int.MAX_VALUE) }
        assertEquals(Int.MAX_VALUE, ReadingProgressSnapshotJson.decode(maximum).anchor!!.characterOffset)
        reject { ReadingProgressSnapshotJson.fromDocument(attach(original().document, maximum)) }
    }

    @Test fun completeTextProofAndSiblingIdentityAreRequiredEvenForAbsentObservations() {
        val source = original()
        val changed = listOf(source.document.copy(paragraphs = source.document.paragraphs.reversed()),
            source.document.copy(paragraphs = source.document.paragraphs.map { it.copy(text = it.text + "x") }),
            source.document.copy(paragraphs = source.document.paragraphs.map { it.copy(paragraphId = it.paragraphId + "x") }),
            source.document.copy(language = "EN"), source.document.copy(kind = "local"),
            source.document.copy(assets = listOf(ExchangeAssetReference("assets/" + "0".repeat(64), "pdf"))))
        changed.forEach { document ->
            reject { ReadingProgressSnapshotJson.withSnapshot(document, source.snapshot) }
            reject { ReadingProgressSnapshotJson.fromDocument(attach(document, source.json)) }
        }
        for (identity in listOf<Any>(JSONObject.NULL, "identity", JSONObject().put("version", 99),
            DocumentIdentityJson.encode(source.snapshot.identity.copy(bookId = "another")))) {
            val document = source.document.copy(extensionsJson = JSONObject(source.document.extensionsJson!!).put("documentIdentity", identity).toString())
            reject { ReadingProgressSnapshotJson.withSnapshot(document, source.snapshot) }
        }
        val noSibling = source.document.copy(extensionsJson = JSONObject(source.document.extensionsJson!!).apply { remove("documentIdentity") }.toString())
        assertEquals(source.snapshot, ReadingProgressSnapshotJson.fromDocument(ReadingProgressSnapshotJson.withSnapshot(noSibling, source.snapshot)))
        val absent = source.snapshot.copy(presence = ReadingProgressSnapshotPresence.ABSENT, anchor = null)
        reject { ReadingProgressSnapshotJson.withSnapshot(changed.first(), absent) }
    }

    @Test fun anchorMembershipOffsetsSurrogateAndEmptyParagraphSemanticsUseTheActualText() {
        val source = original()
        val paragraph = source.document.paragraphs.first()
        val emojiIndex = paragraph.text.indexOf("🌏")
        assertTrue(emojiIndex >= 0)
        val offsets = listOf(0, emojiIndex, emojiIndex + 2, paragraph.text.length)
        offsets.forEach { offset ->
            val snapshot = source.snapshot.copy(anchor = ReadingProgressSnapshotAnchor(paragraph.paragraphId, offset))
            assertEquals(snapshot, ReadingProgressSnapshotJson.fromDocument(ReadingProgressSnapshotJson.withSnapshot(source.document, snapshot)))
        }
        listOf(emojiIndex + 1, paragraph.text.length + 1).forEach { offset ->
            reject { ReadingProgressSnapshotJson.withSnapshot(source.document, source.snapshot.copy(anchor = ReadingProgressSnapshotAnchor(paragraph.paragraphId, offset))) }
        }
        reject { ReadingProgressSnapshotJson.withSnapshot(source.document, source.snapshot.copy(anchor = ReadingProgressSnapshotAnchor("unknown", 0))) }
    }

    @Test fun extensionsBoundaryIncludesEverySiblingAndNeverTruncatesOrMutatesInput() {
        val source = original()
        val small = source.document.copy(extensionsJson = "{\"padding\":\"\"}")
        val baseline = ReadingProgressSnapshotJson.withSnapshot(small, source.snapshot).extensionsJson!!
        val padding = "x".repeat(LibraryExchangeLimits.EXTENSIONS_BYTES - baseline.toByteArray().size)
        val input = small.copy(extensionsJson = JSONObject().put("padding", padding).toString())
        val attached = ReadingProgressSnapshotJson.withSnapshot(input, source.snapshot)
        assertEquals(LibraryExchangeLimits.EXTENSIONS_BYTES, attached.extensionsJson!!.toByteArray().size)
        assertEquals(source.snapshot, ReadingProgressSnapshotJson.fromDocument(zip(attached)))
        val excessive = input.copy(extensionsJson = JSONObject().put("padding", padding + "x").toString())
        reject { ReadingProgressSnapshotJson.withSnapshot(excessive, source.snapshot) }
        assertEquals(padding + "x", JSONObject(excessive.extensionsJson!!).getString("padding"))
        assertFalse(JSONObject(excessive.extensionsJson!!).has(ReadingProgressSnapshotJson.EXTENSION_KEY))
        reject { ReadingProgressSnapshotJson.decode(" ".repeat(LibraryExchangeLimits.EXTENSIONS_BYTES) + source.json) }
    }

    @Test fun unsafeMetadataStillFailsWithoutCoercingMalformedUnicodeBeforeEncoding() {
        val source = original()
        val invalid = listOf("{\"password\":\"secret\"}", "{\"number\":9007199254740992}", "{\"number\":1e999}",
            "{\"number\":1e-999}", "{\"x\":\"bad\\ud800\"}", "{\"x\":\"bad\ud800\"}",
            "{\"bad\udc00\":true}", "{\"x\":" + "{\"x\":".repeat(17) + "true" + "}".repeat(18))
        invalid.forEach { raw ->
            val document = source.document.copy(extensionsJson = raw)
            reject { ReadingProgressSnapshotJson.withSnapshot(document, source.snapshot) }
            reject { ReadingProgressSnapshotJson.fromDocument(document) }
        }
    }

    @Test fun attachmentDetachesAllInputCollectionsAndDecodedValuesAreIndependentOfJson() {
        val source = original()
        val paragraphs = source.document.paragraphs.toMutableList()
        val outline = source.document.outline.toMutableList()
        val notes = source.document.notes.toMutableList()
        val glossary = source.document.glossary.toMutableList()
        val tags = source.document.organization.tags.toMutableList()
        val assets = mutableListOf<ExchangeAssetReference>()
        val input = source.document.copy(paragraphs = paragraphs, outline = outline, notes = notes, glossary = glossary,
            organization = source.document.organization.copy(tags = tags), assets = assets)
        val attached = ReadingProgressSnapshotJson.withSnapshot(input, source.snapshot)
        val encoded = ExchangeJson.encode(attached)
        val json = ReadingProgressSnapshotJson.encode(source.snapshot)
        val decoded = ReadingProgressSnapshotJson.decode(json)
        json.getJSONObject("anchor").put("characterOffset", 999)
        paragraphs.clear(); outline.clear(); notes.clear(); glossary.clear(); tags.clear()
        assets += ExchangeAssetReference("assets/" + "0".repeat(64), "pdf")
        assertArrayEquals(encoded, ExchangeJson.encode(attached))
        assertEquals(source.snapshot, decoded)
        assertEquals(decoded, ReadingProgressSnapshotJson.fromDocument(attached))
    }

    @Test fun completeDocumentEightMiBBoundaryRejectsInputAndOutputExcessBeforeReturning() {
        val source = original()
        fun withText(text: String): Pair<ExchangeDocument, ReadingProgressSnapshot> {
            val paragraphs = listOf(ExchangeParagraph("body", text))
            val document = source.document.copy(paragraphs = paragraphs, outline = emptyList(), notes = emptyList(), position = null, extensionsJson = null)
            val identity = DocumentIdentities.original(ChapterContent(ChapterIdentity(BookIdentity("provider", "book"), "chapter"),
                "Chapter", "en", listOf(ContentParagraph("body", 0, text))))
            return document to source.snapshot.copy(identity = identity, anchor = ReadingProgressSnapshotAnchor("body", 0))
        }
        val (baseDocument, baseSnapshot) = withText("x")
        val extra = LibraryExchangeLimits.DOCUMENT_BYTES - ExchangeJson.encode(ReadingProgressSnapshotJson.withSnapshot(baseDocument, baseSnapshot)).size
        val text = "x" + "한".repeat(extra / 3) + "a".repeat(extra % 3)
        val (document, snapshot) = withText(text)
        val attached = ReadingProgressSnapshotJson.withSnapshot(document, snapshot)
        assertEquals(LibraryExchangeLimits.DOCUMENT_BYTES, ExchangeJson.encode(attached).size)
        assertEquals(snapshot, ReadingProgressSnapshotJson.fromDocument(attached))
        val (overDocument, overSnapshot) = withText(text + "a")
        reject { ReadingProgressSnapshotJson.withSnapshot(overDocument, overSnapshot) }
        reject { ReadingProgressSnapshotJson.fromDocument(attach(overDocument, ReadingProgressSnapshotJson.encode(overSnapshot))) }
        // A larger valid existing offset cannot be replaced first to hide an oversized source.
        val largerAnchor = snapshot.copy(anchor = ReadingProgressSnapshotAnchor("body", 1_000_000))
        val overInput = attach(attached, ReadingProgressSnapshotJson.encode(largerAnchor))
        assertTrue(ExchangeJson.encode(overInput).size > LibraryExchangeLimits.DOCUMENT_BYTES)
        reject { ReadingProgressSnapshotJson.withSnapshot(overInput, snapshot) }
    }
}
