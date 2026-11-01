package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PortableAccountGlossaryExportTest {
    private fun document() = PortableDocumentMapper.server(NotesFixture.document().source).documents.single()
    @Test fun explicitQueryExportsFullRemoteSnapshotAndNeverUsesDevicePendingRecords() = runTest {
        val document = document(); var verified = 0; var queried = 0
        val entry = com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry("server-entry-id", " Alice ", " 앨리스 ", " 아리 ",
            com.dongholab.pagetuner.translation.glossary.GlossaryTermKind.Character, false, false)
        val exported = PortableAccountGlossaryExport.create(document, NotesFixture.recordId, "ko",
            { record, identity -> verified++; assertEquals(NotesFixture.recordId, record); assertEquals(DocumentIdentityJson.fromDocument(document), identity) },
            { identity -> queried++; ServerBookGlossary(identity, 12, BookGlossaryValue(listOf(entry)), "2026-10-06T00:00:00Z") },
            { document }, { true })
        assertEquals(1, verified); assertEquals(1, queried)
        val snapshot = AccountGlossarySnapshotJson.fromDocument(exported)!!.snapshots.single()
        assertEquals(AccountGlossarySnapshotState.PRESENT, snapshot.state)
        assertEquals(entry.id, snapshot.entries!!.single().id)
        assertFalse(snapshot.entries!!.single().enabled)
        assertEquals(" 아리 ", snapshot.entries!!.single().displayTerm)
        assertEquals(document.paragraphs, exported.paragraphs)
        assertEquals(document.notes, exported.notes)
        assertEquals(document.glossary, exported.glossary)
        assertFalse(exported.extensionsJson!!.contains("expectedVersion"))
        assertFalse(exported.extensionsJson!!.contains("mutationId"))
    }
    @Test fun presentEmptyAbsentAndDeletedAreBasedOnLiveReadOnlyQueryWithoutVersionTransfer() = runTest {
        val document = document()
        for ((version, entries, expected) in listOf(Triple(0L, null, AccountGlossarySnapshotState.ABSENT),
            Triple(2L, null, AccountGlossarySnapshotState.DELETED), Triple(3L, emptyList<com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry>(), AccountGlossarySnapshotState.PRESENT))) {
            val exported = PortableAccountGlossaryExport.create(document, NotesFixture.recordId, "ko", { _, _ -> },
                { identity -> ServerBookGlossary(identity, version, BookGlossaryValue(entries), null) }, { document }, { true })
            assertEquals(expected, AccountGlossarySnapshotJson.fromDocument(exported)!!.snapshots.single().state)
            val json = org.json.JSONObject(exported.extensionsJson!!).getJSONObject(AccountGlossarySnapshotJson.EXTENSION_KEY)
            assertFalse(json.getJSONArray("snapshots").getJSONObject(0).has("version"))
        }
    }
    @Test fun staleAccountAfterDocumentReadAndChangedDocumentAbortInsteadOfExportingCachedData() = runTest {
        val document = document(); var current = true
        var failure: Throwable? = null
        try {
            PortableAccountGlossaryExport.create(document, NotesFixture.recordId, "ko", { _, _ -> },
                { identity -> ServerBookGlossary(identity, 0, BookGlossaryValue(null), null) },
                { current = false; document }, { current })
        } catch (error: Exception) { failure = error }
        assertTrue(failure is IllegalArgumentException)
        failure = null
        try {
            PortableAccountGlossaryExport.create(document, NotesFixture.recordId, "ko", { _, _ -> },
                { identity -> ServerBookGlossary(identity, 0, BookGlossaryValue(null), null) },
                { document.copy(bookTitle = "Changed") }, { true })
        } catch (error: Exception) { failure = error }
        assertTrue(failure is IllegalArgumentException)
        failure = null
        try {
            PortableAccountGlossaryExport.create(document, NotesFixture.recordId, "ko", { _, _ -> },
                { throw java.io.IOException("Read-only query failed") }, { document }, { true })
        } catch (error: Exception) { failure = error }
        assertTrue(failure is java.io.IOException)
    }
}
