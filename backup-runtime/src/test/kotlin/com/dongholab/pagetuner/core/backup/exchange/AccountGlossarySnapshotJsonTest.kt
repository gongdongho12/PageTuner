package com.dongholab.pagetuner.core.backup.exchange

import com.dongholab.pagetuner.core.model.glossary.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountGlossarySnapshotJsonTest {
    private val workspace = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "contracts/fixtures/account-glossary-snapshot-v1/document.json").isFile }
    private fun document() = ExchangeJson.decode(File(workspace, "contracts/fixtures/account-glossary-snapshot-v1/document.json").readBytes())
    private fun snapshots() = JSONObject(File(workspace, "contracts/fixtures/account-glossary-snapshot-v1/snapshots.json").readText())
    @Test fun sharedFullSnapshotAndLegacyMetadataSurviveRealZipRoundTripAndReexport() {
        val document = document(); val identity = requireNotNull(DocumentIdentityJson.fromDocument(document))
        val expected = AccountGlossarySnapshotJson.decode(snapshots(), identity)
        val attached = expected.snapshots.fold(document) { value, snapshot -> AccountGlossarySnapshotJson.withSnapshot(value, snapshot, snapshot.targetLanguage) }
        val archive = LibraryExchangeCodec.write(LibraryExchangePackage("2026-10-06T00:00:00Z", listOf(attached)))
        val read = LibraryExchangeCodec.read(archive).documents.single()
        assertEquals(expected, AccountGlossarySnapshotJson.fromDocument(read))
        assertEquals(document.glossary, read.glossary)
        assertEquals(identity, DocumentIdentityJson.fromDocument(read))
        assertTrue(JSONObject(document.extensionsJson!!).getJSONObject("vendorPassive").similar(JSONObject(read.extensionsJson!!).getJSONObject("vendorPassive")))
        assertEquals(read, LibraryExchangeCodec.read(LibraryExchangeCodec.write(LibraryExchangePackage("2026-10-06T00:00:00Z", listOf(read)))).documents.single())
        assertEquals(listOf("original-entry-2", "disabled-place-1", "term-3"), expected.snapshots[0].entries!!.map { it.id })
        assertFalse(expected.snapshots[0].entries!![1].enabled)
        assertEquals(" 아리 ", expected.snapshots[0].entries!![0].displayTerm)
    }
    @Test fun strictTypedDecodeRejectsExecutableOrCasFieldsAndMalformedPresenceButGenericImportPreservesPassiveData() {
        val source = requireNotNull(DocumentIdentityJson.fromDocument(document()))
        listOf(snapshots().put("mutationId", "secret"), snapshots().put("version", 2),
            snapshots().also { it.getJSONArray("snapshots").getJSONObject(0).put("version", 99) },
            snapshots().also { it.getJSONArray("snapshots").getJSONObject(0).getJSONArray("entries").getJSONObject(0).put("apiKey", "secret") },
            snapshots().also { it.getJSONArray("snapshots").getJSONObject(0).put("entries", JSONObject.NULL) },
            snapshots().also { it.getJSONArray("snapshots").getJSONObject(2).put("entries", org.json.JSONArray()) }).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { AccountGlossarySnapshotJson.decode(invalid, source) }
        }
        val passive = document().copy(extensionsJson = JSONObject(document().extensionsJson!!).put(AccountGlossarySnapshotJson.EXTENSION_KEY,
            JSONObject().put("version", 99).put("future", "passive")).toString())
        val read = LibraryExchangeCodec.read(LibraryExchangeCodec.write(LibraryExchangePackage("2026-10-06T00:00:00Z", listOf(passive)))).documents.single()
        assertEquals("passive", JSONObject(read.extensionsJson!!).getJSONObject(AccountGlossarySnapshotJson.EXTENSION_KEY).getString("future"))
        assertThrows(IllegalArgumentException::class.java) { AccountGlossarySnapshotJson.fromDocument(read) }
    }
    @Test fun matchingScopeReplacementKeepsOtherLanguageSnapshotsAndFullOversizedSnapshotFailsWithoutTruncation() {
        val source = requireNotNull(DocumentIdentityJson.fromDocument(document()))
        val snapshots = AccountGlossarySnapshotJson.decode(snapshots(), source)
        val attached = snapshots.snapshots.fold(document()) { doc, snapshot -> AccountGlossarySnapshotJson.withSnapshot(doc, snapshot, snapshot.targetLanguage) }
        val changed = snapshots.snapshots[0].copy(entries = snapshots.snapshots[0].entries!!.reversed())
        val updated = AccountGlossarySnapshotJson.fromDocument(AccountGlossarySnapshotJson.withSnapshot(attached, changed, "ko"))!!
        assertEquals(listOf(changed) + snapshots.snapshots.drop(1), updated.snapshots)
        val oversized = changed.copy(entries = List(500) { BookGlossarySyncEntry("id-$it", "원".repeat(200), "번".repeat(200), "표".repeat(200), BookGlossarySyncKind.Term, true, false) })
        assertThrows(IllegalArgumentException::class.java) { AccountGlossarySnapshotJson.withSnapshot(attached, oversized, "ko") }
        assertEquals(snapshots, AccountGlossarySnapshotJson.fromDocument(attached))
    }
}
