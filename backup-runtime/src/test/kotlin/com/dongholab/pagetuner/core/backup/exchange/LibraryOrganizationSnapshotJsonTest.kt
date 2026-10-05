package com.dongholab.pagetuner.core.backup.exchange

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryOrganizationSnapshotJsonTest {
    private val workspace = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "contracts/fixtures/library-organization-snapshot-v1.json").isFile }
    private data class Case(val name: String, val document: ExchangeDocument, val json: JSONObject) {
        val snapshot get() = LibraryOrganizationSnapshotJson.decode(json)
    }
    private fun cases(): List<Case> {
        val array = JSONObject(File(workspace, "contracts/fixtures/library-organization-snapshot-v1.json").readText()).getJSONArray("cases")
        return (0 until array.length()).map { index -> array.getJSONObject(index).let {
            Case(it.getString("name"), ExchangeJson.decode(it.getJSONObject("document").toString().toByteArray()), it.getJSONObject("snapshot"))
        } }
    }
    private fun original() = cases().first { it.name == "original-present" }
    private fun translated() = cases().first { it.name == "translation-present" }
    private fun reject(block: () -> Unit) = assertThrows(Exception::class.java, block)
    private fun attachRaw(document: ExchangeDocument, value: Any) = document.copy(extensionsJson =
        JSONObject(document.extensionsJson ?: "{}").put(LibraryOrganizationSnapshotJson.EXTENSION_KEY, value).toString())
    private fun roundTrip(document: ExchangeDocument): ExchangeDocument = LibraryExchangeCodec.read(LibraryExchangeCodec.write(
        LibraryExchangePackage("2026-10-05T00:00:00Z", listOf(document)))).documents.single()

    @Test fun everySharedFixtureRoundTripsAllFieldsThroughJsonAndZipAndKeepsLegacyOrganizationAndSiblings() {
        val fixtures = cases()
        assertEquals(7, fixtures.size)
        for (fixture in fixtures) {
            val before = fixture.document
            val snapshot = fixture.snapshot
            assertEquals(snapshot, LibraryOrganizationSnapshotJson.decode(fixture.json.toString()))
            assertTrue(fixture.json.similar(LibraryOrganizationSnapshotJson.encode(snapshot)))
            val attached = LibraryOrganizationSnapshotJson.withSnapshot(before, snapshot)
            assertEquals(before.copy(extensionsJson = attached.extensionsJson), attached)
            assertEquals(snapshot, LibraryOrganizationSnapshotJson.fromDocument(attached))
            val restored = roundTrip(attached)
            assertEquals(snapshot, LibraryOrganizationSnapshotJson.fromDocument(restored))
            assertEquals(before.organization, restored.organization)
            assertEquals(before.glossary, restored.glossary)
            val oldExtensions = JSONObject(before.extensionsJson ?: "{}")
            val newExtensions = JSONObject(restored.extensionsJson!!)
            oldExtensions.keys().forEach { key ->
                assertTrue(JSONObject().put(key, oldExtensions.get(key)).similar(JSONObject().put(key, newExtensions.get(key))))
            }
            assertFalse(oldExtensions.has(LibraryOrganizationSnapshotJson.EXTENSION_KEY))
            assertEquals(oldExtensions.has("documentIdentity"), newExtensions.has("documentIdentity"))
        }
        assertTrue(original().document.organization.folder.length > 200)
        assertEquals(listOf("Reading", "reading", "é", "e\u0301", "원문🌏", "중간  공백", "mid\uFEFFbom", "\u200Bedge"),
            original().snapshot.organization!!.tags)
    }

    @Test fun missingSnapshotRequiresNoProofAndAbsentRemainsDifferentFromExplicitEmptyAndLegacyValues() {
        val source = original()
        assertNull(LibraryOrganizationSnapshotJson.fromDocument(source.document.copy(extensionsJson = null)))
        assertNull(LibraryOrganizationSnapshotJson.fromDocument(source.document.copy(kind = "local", paragraphs = emptyList(),
            extensionsJson = "{\"documentIdentity\":{\"version\":99},\"passive\":true}")))
        val absent = source.snapshot.copy(presence = LibraryOrganizationSnapshotPresence.ABSENT, organization = null)
        val empty = source.snapshot.copy(organization = LibraryOrganizationSnapshotValue("", emptyList(), false))
        val withAbsent = LibraryOrganizationSnapshotJson.withSnapshot(source.document, absent)
        val withEmpty = LibraryOrganizationSnapshotJson.withSnapshot(withAbsent, empty)
        assertEquals(absent, LibraryOrganizationSnapshotJson.fromDocument(withAbsent))
        assertEquals(empty, LibraryOrganizationSnapshotJson.fromDocument(withEmpty))
        assertNotEquals(absent, empty)
        assertEquals(source.document.organization, withEmpty.organization)
    }

    @Test fun unknownMalformedOrConflictingExistingSnapshotStaysPassiveButTypedReadsAndReplacementRefuseIt() {
        val source = original()
        val values = listOf<Any>(JSONObject(source.json.toString()).put("version", 99), JSONObject.NULL, "not an object",
            JSONObject(source.json.toString()).put("presence", "deleted"),
            JSONObject(source.json.toString()).put("organization", JSONObject().put("folder", "x")),
            JSONObject(source.json.toString()).apply { getJSONObject("identity").put("bookId", "another-book") })
        for (value in values) {
            val changed = attachRaw(source.document, value)
            val raw = changed.extensionsJson
            val restored = roundTrip(changed)
            assertTrue(JSONObject(raw!!).similar(JSONObject(restored.extensionsJson!!)))
            reject { LibraryOrganizationSnapshotJson.fromDocument(restored) }
            reject { LibraryOrganizationSnapshotJson.withSnapshot(restored, source.snapshot) }
            assertEquals(raw, changed.extensionsJson)
            assertEquals(source.document.organization, restored.organization)
        }
    }

    @Test fun rawDuplicatesEscapedDuplicatesTrailingInputAndNumericCoercionCannotDisappear() {
        val source = original()
        val raw = LibraryOrganizationSnapshotJson.encode(source.snapshot).toString()
        val organization = JSONObject(raw).getJSONObject("organization").toString()
        val nestedDuplicate = raw.replace(organization, organization.replaceFirst("{", "{\"favorite\":true,"))
        listOf(raw.replaceFirst("{", "{\"version\":1,"), raw.replaceFirst("{", "{\"ver\\u0073ion\":1,"),
            nestedDuplicate, raw + "{}", raw + " trailing", raw.replace("\"version\":1", "\"version\":\"1\""),
            raw.replace("\"version\":1", "\"version\":1.5"), raw.replace("\"version\":1", "\"version\":1e999"),
            raw.replace("\"version\":1", "\"version\":1e-999"), raw.replace("\"version\":1", "\"version\":9007199254740992"))
            .forEach { invalid -> assertNotEquals(raw, invalid); reject { LibraryOrganizationSnapshotJson.decode(invalid) } }
        for (version in listOf("1", "1.0", "1e0")) {
            assertEquals(source.snapshot, LibraryOrganizationSnapshotJson.decode(raw.replace("\"version\":1", "\"version\":$version")))
        }
        val repeated = "{\"libraryOrganizationSnapshot\":$raw,\"libraryOrganizationSnapshot\":$raw}"
        reject { LibraryOrganizationSnapshotJson.fromDocument(source.document.copy(extensionsJson = repeated)) }
        reject { LibraryOrganizationSnapshotJson.withSnapshot(source.document.copy(extensionsJson = repeated), source.snapshot) }
    }

    @Test fun exactFieldsTypesAndPresenceRejectAccountCasAndOutboxStateWithoutDefaults() {
        val source = original()
        fun invalid(change: (JSONObject) -> Unit) {
            val json = JSONObject(source.json.toString()).also(change)
            reject { LibraryOrganizationSnapshotJson.decode(json) }
            reject { LibraryOrganizationSnapshotJson.decode(json.toString()) }
        }
        for (key in listOf("accountId", "origin", "expectedVersion", "mutationId", "outbox", "conflict", "authorization")) {
            invalid { it.put(key, "not portable") }
            invalid { it.getJSONObject("organization").put(key, "not portable") }
        }
        for (key in listOf("version", "identity", "presence", "organization")) invalid { it.remove(key) }
        for (key in listOf("folder", "tags", "favorite")) invalid { it.getJSONObject("organization").remove(key) }
        for (presence in listOf("PRESENT", "deleted", "unknown", "", " present")) invalid { it.put("presence", presence) }
        invalid { it.put("presence", true) }
        invalid { it.put("presence", "absent") }
        invalid { it.put("organization", JSONObject.NULL) }
        invalid { it.put("identity", source.snapshot.identity.toString()) }
        invalid { it.getJSONObject("identity").put("version", "1") }
        invalid { it.getJSONObject("identity").put("expectedVersion", 1) }
        invalid { it.getJSONObject("organization").put("folder", false) }
        invalid { it.getJSONObject("organization").put("tags", "tag") }
        invalid { it.getJSONObject("organization").put("tags", JSONArray().put(42)) }
        invalid { it.getJSONObject("organization").put("favorite", "true") }
        invalid { it.getJSONObject("organization").put("favorite", 1) }
    }

    @Test fun fullBodyOriginLanguageAndTranslationArtifactProofAreRequiredBeforeReadingOrAttaching() {
        for (source in listOf(original(), translated())) {
            val doc = source.document
            val alteredDocuments = listOf(doc.copy(language = if (doc.language == "en") "EN" else "en"),
                doc.copy(kind = "local"), doc.copy(paragraphs = doc.paragraphs.reversed()),
                doc.copy(paragraphs = doc.paragraphs.mapIndexed { index, paragraph -> if (index == 0) paragraph.copy(text = paragraph.text + "changed") else paragraph }),
                doc.copy(paragraphs = doc.paragraphs.mapIndexed { index, paragraph -> if (index == 0) paragraph.copy(paragraphId = "changed-id") else paragraph }),
                doc.copy(assets = listOf(ExchangeAssetReference("assets/" + "a".repeat(64), "pdf"))))
            for (changed in alteredDocuments) {
                reject { LibraryOrganizationSnapshotJson.withSnapshot(changed, source.snapshot) }
                reject { LibraryOrganizationSnapshotJson.fromDocument(attachRaw(changed, source.json)) }
            }
            val keys = listOf("contentProviderId", "bookId", "chapterId", "sourceRevision", "sourceLanguage", "paragraphHash") +
                if (source.snapshot.identity.kind == DocumentIdentityKind.TRANSLATION)
                    listOf("targetLanguage", "translationProviderId", "modelId", "promptRevision", "glossaryRevision", "artifactId", "revision", "payloadHash") else emptyList()
            for (key in keys) {
                val json = JSONObject(source.json.toString())
                val replacement = when (key) {
                    "sourceRevision", "paragraphHash", "artifactId", "revision", "payloadHash" -> "0".repeat(64)
                    "sourceLanguage", "targetLanguage" -> "fr"
                    else -> "different-$key"
                }
                json.getJSONObject("identity").put(key, replacement)
                val snapshot = LibraryOrganizationSnapshotJson.decode(json) // Structure alone is not a proof.
                // ORIGINAL content hashes cannot independently establish provider/book/chapter ownership.
                // The supplied sibling is the comparison evidence for those fields; translation hashes bind them.
                val needsSibling = source.snapshot.identity.kind == DocumentIdentityKind.ORIGINAL &&
                    key in setOf("contentProviderId", "bookId", "chapterId")
                val compared = if (needsSibling) doc else doc.copy(extensionsJson = null)
                reject { LibraryOrganizationSnapshotJson.withSnapshot(compared, snapshot) }
                reject { LibraryOrganizationSnapshotJson.fromDocument(attachRaw(compared, json)) }
            }
        }
    }

    @Test fun suppliedSiblingIdentityMustBeStrictSupportedAndExactlyEqual() {
        val source = original()
        val identity = DocumentIdentityJson.encode(source.snapshot.identity)
        val siblings = listOf<Any>(JSONObject.NULL, "identity", JSONObject(identity.toString()).put("version", 99),
            JSONObject(identity.toString()).put("bookId", "other"), JSONObject(identity.toString()).put("unknown", true))
        for (sibling in siblings) {
            val document = source.document.copy(extensionsJson = JSONObject().put("documentIdentity", sibling).toString())
            reject { LibraryOrganizationSnapshotJson.withSnapshot(document, source.snapshot) }
            reject { LibraryOrganizationSnapshotJson.fromDocument(attachRaw(document, source.json)) }
        }
        val without = source.document.copy(extensionsJson = "{\"passive\":true}")
        val attached = LibraryOrganizationSnapshotJson.withSnapshot(without, source.snapshot)
        assertEquals(source.snapshot, LibraryOrganizationSnapshotJson.fromDocument(attached))
        assertFalse(JSONObject(attached.extensionsJson!!).has("documentIdentity"))
    }

    @Test fun accountValueBoundariesAreLosslessAndNeverUseLegacyNormalization() {
        val source = original()
        val valid = LibraryOrganizationSnapshotValue("가".repeat(200), (0 until 32).map { "${it.toString().padStart(2, '0')}" + "🌏".repeat(29) }, false)
        val snapshot = source.snapshot.copy(organization = valid)
        assertEquals(snapshot, LibraryOrganizationSnapshotJson.decode(LibraryOrganizationSnapshotJson.encode(snapshot)))
        val invalidValues = listOf(valid.copy(folder = "x".repeat(201)), valid.copy(tags = valid.tags + "extra"),
            valid.copy(tags = listOf("x".repeat(61))), valid.copy(tags = listOf("")), valid.copy(tags = listOf("same", "same"))) +
            listOf(" leading", "trailing ", "\u00A0edge", "edge\uFEFF", "a\nb", "a\u0085b", "bad\uD800", "bad\uDC00")
                .flatMap { listOf(valid.copy(folder = it), valid.copy(tags = listOf(it))) }
        for (value in invalidValues) {
            reject { LibraryOrganizationSnapshotJson.encode(source.snapshot.copy(organization = value)) }
            val json = JSONObject(source.json.toString()).put("organization", JSONObject().put("folder", value.folder)
                .put("tags", JSONArray(value.tags)).put("favorite", value.favorite))
            reject { LibraryOrganizationSnapshotJson.decode(json) }
        }
    }

    @Test fun completeExtensionsByteBoundaryIncludesSiblingsAndNeverMutatesOrTruncatesThem() {
        val source = original()
        val small = source.document.copy(extensionsJson = "{\"padding\":\"\"}")
        val base = LibraryOrganizationSnapshotJson.withSnapshot(small, source.snapshot).extensionsJson!!
        val padding = "x".repeat(LibraryExchangeLimits.EXTENSIONS_BYTES - base.toByteArray().size)
        val atLimit = small.copy(extensionsJson = JSONObject().put("padding", padding).toString())
        val attached = LibraryOrganizationSnapshotJson.withSnapshot(atLimit, source.snapshot)
        assertEquals(LibraryExchangeLimits.EXTENSIONS_BYTES, attached.extensionsJson!!.toByteArray().size)
        assertEquals(source.snapshot, LibraryOrganizationSnapshotJson.fromDocument(roundTrip(attached)))
        val excessive = atLimit.copy(extensionsJson = JSONObject().put("padding", padding + "x").toString())
        reject { LibraryOrganizationSnapshotJson.withSnapshot(excessive, source.snapshot) }
        assertEquals(padding + "x", JSONObject(excessive.extensionsJson!!).getString("padding"))
        assertFalse(JSONObject(excessive.extensionsJson!!).has(LibraryOrganizationSnapshotJson.EXTENSION_KEY))
        reject { LibraryOrganizationSnapshotJson.decode(" ".repeat(LibraryExchangeLimits.EXTENSIONS_BYTES) + source.json) }
    }

    @Test fun generalMetadataNumberDepthUnicodeAndCredentialRestrictionsStillApplyToZipAndTypedReaders() {
        val source = original()
        val deep = "{\"other\":" + "{\"x\":".repeat(17) + "true" + "}".repeat(17) + "}"
        val invalid = listOf("{\"other\":9007199254740992}", "{\"other\":1e-999}", "{\"other\":1e999}",
            "{\"password\":\"private\"}", "{\"other\":{\"Access-Token\":\"private\"}}", deep,
            "{\"other\":\"bad\\ud800\"}", "{\"bad\\udc00\":true}")
        for (extensions in invalid) {
            val document = source.document.copy(extensionsJson = extensions)
            reject { roundTrip(document) }
            reject { LibraryOrganizationSnapshotJson.fromDocument(document) }
            reject { LibraryOrganizationSnapshotJson.withSnapshot(document, source.snapshot) }
        }
        // This precheck is stronger than generic UTF-8 conversion: raw lone surrogates cannot turn into '?'.
        for (extensions in listOf("{\"other\":\"bad\uD800\"}", "{\"bad\uDC00\":true}", "{\"other\":[{\"x\":\"\\ud800\"}]}")) {
            reject { LibraryOrganizationSnapshotJson.fromDocument(source.document.copy(extensionsJson = extensions)) }
            reject { LibraryOrganizationSnapshotJson.withSnapshot(source.document.copy(extensionsJson = extensions), source.snapshot) }
        }
        val valid = source.document.copy(extensionsJson = "{\"other\":{\"small\":0.125,\"integer\":9007199254740991,\"unicode\":\"🌏\"}}")
        assertEquals(source.snapshot, LibraryOrganizationSnapshotJson.fromDocument(roundTrip(LibraryOrganizationSnapshotJson.withSnapshot(valid, source.snapshot))))
    }

    @Test fun encodingAndAttachmentCaptureIndependentTagArraysWithoutChangingInputs() {
        val source = original()
        val tags = mutableListOf("z", "A", "a", "é", "e\u0301")
        val value = source.snapshot.copy(organization = LibraryOrganizationSnapshotValue("part  one", tags, true))
        val json = LibraryOrganizationSnapshotJson.encode(value)
        val attached = LibraryOrganizationSnapshotJson.withSnapshot(source.document, value)
        val decoded = LibraryOrganizationSnapshotJson.decode(json)
        val originalRaw = source.document.extensionsJson
        tags.clear(); tags += "changed"
        json.getJSONObject("organization").getJSONArray("tags").put("later mutation")
        assertEquals(listOf("z", "A", "a", "é", "e\u0301"), decoded.organization!!.tags)
        assertEquals(decoded, LibraryOrganizationSnapshotJson.fromDocument(attached))
        assertEquals(originalRaw, source.document.extensionsJson)
        assertFalse(JSONObject(originalRaw!!).has(LibraryOrganizationSnapshotJson.EXTENSION_KEY))
        assertEquals(source.document.organization, attached.organization)
    }

    @Test fun objectOverloadRejectsObjectsThatOrgJsonWouldCoerceToStringsArraysOrObjects() {
        val source = original()
        fun invalid(change: (JSONObject) -> Unit) {
            val value = JSONObject(source.json.toString()).also(change)
            reject { LibraryOrganizationSnapshotJson.decode(value) }
        }
        invalid { it.getJSONObject("organization").put("folder", StringBuilder("Valid")) }
        invalid { it.getJSONObject("organization").put("tags", JSONArray().put(StringBuilder("Valid"))) }
        invalid { it.getJSONObject("identity").put("chapterId", StringBuilder("chapter")) }
        invalid { it.put("presence", StringBuilder("present")) }
        invalid { it.getJSONObject("organization").put("tags", listOf("tag") as Any) }
        invalid { it.put("organization", mapOf("folder" to "Valid", "tags" to emptyList<String>(), "favorite" to true) as Any) }
    }

    @Test fun attachedDocumentDetachesEveryInputCollectionBeforeProofAndRemainsValidAfterInputMutation() {
        val source = original()
        val paragraphs = source.document.paragraphs.toMutableList()
        val outline = mutableListOf(ExchangeOutlineEntry("One", paragraphs.first().paragraphId))
        val notes = mutableListOf(ExchangeNote("bookmark-1", "bookmark", "Marked", "Note", "Excerpt",
            ExchangeAnchor(paragraphs.first().paragraphId, 1), "2026-10-05T00:00:00Z"))
        val tags = source.document.organization.tags.toMutableList()
        val glossary = source.document.glossary.toMutableList()
        val assets = mutableListOf<ExchangeAssetReference>()
        val input = source.document.copy(paragraphs = paragraphs, outline = outline, notes = notes,
            organization = source.document.organization.copy(tags = tags), glossary = glossary, assets = assets)
        val attached = LibraryOrganizationSnapshotJson.withSnapshot(input, source.snapshot)
        val encoded = ExchangeJson.encode(attached)
        assertNotSame(paragraphs, attached.paragraphs); assertNotSame(outline, attached.outline)
        assertNotSame(notes, attached.notes); assertNotSame(tags, attached.organization.tags)
        assertNotSame(glossary, attached.glossary); assertNotSame(assets, attached.assets)
        paragraphs.clear(); outline.clear(); notes.clear(); tags.clear(); glossary.clear()
        assets += ExchangeAssetReference("assets/" + "a".repeat(64), "pdf")
        assertArrayEquals(encoded, ExchangeJson.encode(attached))
        assertEquals(source.snapshot, LibraryOrganizationSnapshotJson.fromDocument(attached))
        assertEquals(source.document.paragraphs, attached.paragraphs)
        assertEquals(source.document.organization, attached.organization)
    }

    @Test fun attachedDocumentHonorsTheCompleteEightMiBEncodedBoundaryBeforeReturning() {
        val source = original()
        fun withText(text: String): Pair<ExchangeDocument, LibraryOrganizationSnapshot> {
            val paragraphs = listOf(ExchangeParagraph("body", text))
            val document = source.document.copy(paragraphs = paragraphs, extensionsJson = null)
            val identity = DocumentIdentities.original(ChapterContent(
                ChapterIdentity(BookIdentity("source:edge", "book|原🌏"), "chapter:one"), document.chapterTitle, "en",
                listOf(ContentParagraph("body", 0, text))))
            return document to source.snapshot.copy(identity = identity)
        }
        val (baseDocument, baseSnapshot) = withText("x")
        val overhead = ExchangeJson.encode(LibraryOrganizationSnapshotJson.withSnapshot(baseDocument, baseSnapshot)).size
        val extra = LibraryExchangeLimits.DOCUMENT_BYTES - overhead
        val text = "x" + "한".repeat(extra / 3) + "a".repeat(extra % 3)
        val (document, snapshot) = withText(text)
        val attached = LibraryOrganizationSnapshotJson.withSnapshot(document, snapshot)
        assertEquals(LibraryExchangeLimits.DOCUMENT_BYTES, ExchangeJson.encode(attached).size)
        assertEquals(snapshot, LibraryOrganizationSnapshotJson.fromDocument(attached))
        val (overDocument, overSnapshot) = withText(text + "a")
        assertTrue("The source fits before the extension is attached", ExchangeJson.encode(overDocument).size < LibraryExchangeLimits.DOCUMENT_BYTES)
        reject { LibraryOrganizationSnapshotJson.withSnapshot(overDocument, overSnapshot) }
        reject { LibraryOrganizationSnapshotJson.fromDocument(attachRaw(overDocument, LibraryOrganizationSnapshotJson.encode(overSnapshot))) }
        assertNull(overDocument.extensionsJson)
    }
}
