package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import com.dongholab.pagetuner.translation.glossary.GlossaryTermKind
import com.dongholab.pagetuner.translation.sync.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableAccountMetadataExportTest {
    @Test fun combinedZipPreserves500FullEntriesOtherScopesAndCanonicalOrganization() = runTest {
        val connection = NotesFixture.connection { error("No implicit network request") }
        val source = PortableDocumentMapper.server(NotesFixture.document().source).documents.single()
        val identity = requireNotNull(DocumentIdentityJson.fromDocument(source))
        val scope = BookGlossarySyncIdentity(identity.contentProviderId, identity.bookId, "ko")
        val other = BookGlossarySnapshot(scope.copy(targetLanguage = "ja"), BookGlossarySnapshotPresence.ABSENT, null)
        val canonical = BookGlossarySnapshotsJson.withSnapshots(source.copy(
            organization = ExchangeOrganization("Legacy", listOf("legacy"), false),
            extensionsJson = JSONObject(source.extensionsJson!!).put("unknownSibling", "保 存").toString()),
            BookGlossarySnapshots(snapshots = listOf(other)))
        val entry = PortableLibraryEntry("a".repeat(64), 0, canonical)
        val binding = PortableServerBinding(connection.accountKey, entry.key, NotesFixture.recordId, identity)
        val values = (0 until 500).map { index -> BookGlossaryEntry("id:$index", " Source $index ", " 번역 $index ",
            if (index % 2 == 0) " 별칭 " else "", GlossaryTermKind.entries[index % 3], index % 2 == 0, index % 3 == 0) }
        var glossaryQueries = 0; var organizationQueries = 0
        val glossary = PortableAccountGlossaryExport({ canonical }, { canonical }, { _, _ -> binding }, { DeviceBookGlossary() },
            verify = { _, _, _ -> }, query = { _, target -> glossaryQueries++
                ServerBookGlossary(target, 3, BookGlossaryValue(values), NotesFixture.time) })
            .prepare(entry, "ko", connection) {}
        val organizationValue = LibraryOrganization("Folder  中", listOf("é", "e\u0301", "中  文"), true)
        val organization = PortableAccountOrganizationExport({ canonical }, { canonical }, { _, _ -> binding }, { DeviceLibraryOrganization() },
            verify = { _, _, _ -> }, query = { _, kind, id -> organizationQueries++
                ServerLibraryOrganization(kind, id, 4, organizationValue, NotesFixture.time) }).prepare(entry, connection) {}
        val prepared = combineAccountMetadataExports(glossary, organization) {}
        val tickets = PortableExportTickets(); val request = tickets.begin("combined.zip", connection)
        tickets.prepare(request, LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(prepared.document))),
            connection, prepared.checkBeforeWrite)
        val output = ByteArrayOutputStream(); tickets.write(request.id, { connection }) { output }
        val document = LibraryExchangeCodec.read(output.toByteArray()).documents.single()
        val snapshots = BookGlossarySnapshotsJson.fromDocument(document)!!.snapshots
        assertEquals(other, snapshots.first())
        assertEquals(values.map { it.syncEntry() }, snapshots.last().entries)
        assertEquals(500, snapshots.last().entries!!.size)
        assertEquals(organization.snapshot, LibraryOrganizationSnapshotJson.fromDocument(document))
        assertEquals(canonical.organization, document.organization)
        assertEquals(canonical.paragraphs, document.paragraphs)
        assertEquals(identity, DocumentIdentityJson.fromDocument(document))
        assertEquals("保 存", JSONObject(document.extensionsJson!!).getString("unknownSibling"))
        assertNull(LibraryOrganizationSnapshotJson.fromDocument(canonical))
        assertEquals(1, glossaryQueries); assertEquals(1, organizationQueries)
    }

    @Test fun changingEitherCanonicalSourceAfterPreparationBlocksTheCombinedSafWrite() = runTest {
        val connection = NotesFixture.connection { error("No HTTP") }
        val source = PortableDocumentMapper.server(NotesFixture.document().source).documents.single()
        val proof = requireNotNull(DocumentIdentityJson.fromDocument(source))
        val glossary = BookGlossarySnapshot(BookGlossarySyncIdentity(proof.contentProviderId, proof.bookId, "ko"),
            BookGlossarySnapshotPresence.PRESENT, emptyList())
        val organization = LibraryOrganizationSnapshot(identity = proof, presence = LibraryOrganizationSnapshotPresence.PRESENT,
            organization = LibraryOrganizationSnapshotValue("", emptyList(), false))
        repeat(2) { expired ->
            var glossaryCurrent = true; var organizationCurrent = true
            val g = PreparedAccountGlossary(BookGlossarySnapshotsJson.withSnapshots(source, BookGlossarySnapshots(snapshots = listOf(glossary))),
                glossary) { check(glossaryCurrent) }
            val o = PreparedAccountOrganization(LibraryOrganizationSnapshotJson.withSnapshot(source, organization), organization) { check(organizationCurrent) }
            val prepared = combineAccountMetadataExports(g, o) {}
            val tickets = PortableExportTickets(); val request = tickets.begin("combined.zip", connection)
            tickets.prepare(request, LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(prepared.document))),
                connection, prepared.checkBeforeWrite)
            if (expired == 0) glossaryCurrent = false else organizationCurrent = false
            assertThrows(IllegalStateException::class.java) { tickets.write(request.id, { connection }) { fail("Stale file open"); null } }
        }
    }
}
