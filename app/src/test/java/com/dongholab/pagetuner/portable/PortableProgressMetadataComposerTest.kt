package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Composer-only capabilities are synthetic here; actual ZIP/binding/ticket authority has separate adapter tests. */
class PortableProgressMetadataComposerTest {
    private val connection = NotesFixture.connection { error("Composer must not request account data") }
    private val original = PortableDocumentMapper.server(NotesFixture.document().source).documents.single().let {
        it.copy(position = ExchangeAnchor("p1", 0), organization = ExchangeOrganization(" Legacy ", listOf("é", "é"), true),
            extensionsJson = JSONObject(it.extensionsJson!!).put("unknown", " 保  存 ").toString())
    }
    private val identity = requireNotNull(DocumentIdentityJson.fromDocument(original))
    private val entry = PortableLibraryEntry("a".repeat(64), 0, original)
    private val binding = PortableServerBinding(connection.accountKey, entry.key, NotesFixture.recordId, identity)
    private val snapshot = ReadingProgressSnapshot(identity = identity, presence = ReadingProgressSnapshotPresence.PRESENT,
        anchor = ReadingProgressSnapshotAnchor("p1", 8))
    private val orgSnapshot = LibraryOrganizationSnapshot(identity = identity, presence = LibraryOrganizationSnapshotPresence.PRESENT,
        organization = LibraryOrganizationSnapshotValue("New", listOf("é", "é", "Tag", "tag"), false))
    private fun source(selected: PortableLibraryEntry = entry, account: ServerReadingConnection = connection,
        linked: PortableServerBinding = binding) = detachedAccountExportSource(selected, account, linked)
    private fun progress(provenance: PortableAccountExportSource? = source(), base: ExchangeDocument = original,
        value: ReadingProgressSnapshot = snapshot, guard: () -> Unit = {}) = PreparedAccountReadingProgress(
        ReadingProgressSnapshotJson.withSnapshot(base, value), value, provenance, guard)
    private fun org(provenance: PortableAccountExportSource? = source(), base: ExchangeDocument = original,
        guard: () -> Unit = {}) = PreparedAccountOrganization(
        LibraryOrganizationSnapshotJson.withSnapshot(base, orgSnapshot), orgSnapshot, provenance, guard)

    @Test fun progressOnlyAndAbsentRoundTripWithoutMovingLegacyPositionOrLeakingPrivateSource() {
        for (value in listOf(snapshot, snapshot.copy(presence = ReadingProgressSnapshotPresence.ABSENT, anchor = null))) {
            val prepared = combineAccountMetadataExports(null, null, progress(value = value)) {}
            val read = LibraryExchangeCodec.read(LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(prepared.document)))).documents.single()
            assertEquals(value, ReadingProgressSnapshotJson.fromDocument(read)); assertEquals(original.position, read.position)
            assertEquals(original.organization, read.organization); assertEquals(original.notes, read.notes)
            assertEquals(original.paragraphs, read.paragraphs); assertEquals(identity, DocumentIdentityJson.fromDocument(read))
            assertEquals(" 保  存 ", JSONObject(read.extensionsJson!!).getString("unknown"))
            assertFalse(read.extensionsJson!!.contains(connection.accountKey)); assertFalse(read.extensionsJson!!.contains(binding.nonce))
        }
    }

    @Test fun progressRequiresProvenanceAndCannotBorrowAnUnscopedPreparation() {
        assertThrows(IllegalArgumentException::class.java) { combineAccountMetadataExports(null, null, progress(provenance = null)) {} }
        assertThrows(IllegalArgumentException::class.java) { combineAccountMetadataExports(null, org(provenance = null), progress()) {} }
    }

    @Test fun equalIdentityWithDifferentMetadataOrLocatorCannotBeCombined() {
        val changed = original.copy(bookTitle = "Different saved metadata", position = ExchangeAnchor("p2", 0))
        val changedEntry = entry.copy(document = changed)
        assertEquals(identity, DocumentIdentityJson.fromDocument(changed))
        assertThrows(IllegalArgumentException::class.java) { combineAccountMetadataExports(null,
            org(source(changedEntry), changed), progress()) {} }
        val otherEntry = entry.copy(packageId = "b".repeat(64))
        val otherBinding = binding.copy(localKey = otherEntry.key)
        assertThrows(IllegalArgumentException::class.java) { combineAccountMetadataExports(null,
            org(source(otherEntry, linked = otherBinding)), progress()) {} }
    }

    @Test fun accountClientRecordAndSameUuidNonceGenerationMustAllAgree() {
        val otherAccount = NotesFixture.connection("other") { error("No HTTP") }
        val otherClient = NotesFixture.connection { error("No HTTP") }
        val alternatives = listOf(source(account = otherAccount, linked = binding.copy(accountKey = otherAccount.accountKey)),
            source(account = otherClient), source(linked = binding.copy(recordId = NotesFixture.otherId)),
            source(linked = binding.copy(nonce = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")))
        alternatives.forEach { other ->
            assertThrows(IllegalArgumentException::class.java) { combineAccountMetadataExports(null, org(other), progress()) {} }
        }
    }

    @Test fun mutatingReturnedProvenanceAndPreparedDocumentDoesNotChangePrivateCanonicalSource() {
        val p = progress(); val exposed = p.source!!.document
        (exposed.paragraphs as MutableList).clear()
        (exposed.organization.tags as MutableList).clear()
        (p.document.paragraphs as MutableList).clear()
        assertEquals(original, p.source.document)
        assertThrows(IllegalArgumentException::class.java) { combineAccountMetadataExports(null, null, p) {} }
    }

    @Test fun allSourceGuardsSurviveCompositionAndLaterInvalidationBlocksSave() {
        for (selected in 0..1) {
            var organizationCurrent = true; var progressCurrent = true
            val prepared = combineAccountMetadataExports(null, org(guard = { check(organizationCurrent) }),
                progress(guard = { check(progressCurrent) })) {}
            if (selected == 0) organizationCurrent = false else progressCurrent = false
            assertThrows(IllegalStateException::class.java) { prepared.checkBeforeWrite() }
        }
    }

    @Test fun unsupportedExistingProgressIsRejectedInsteadOfReplacingItWithANewObservation() {
        val broken = original.copy(extensionsJson = JSONObject(original.extensionsJson!!).put("readingProgressSnapshot",
            JSONObject("""{"version":999}""")).toString())
        val invalid = PreparedAccountReadingProgress(broken, snapshot, source(entry.copy(document = broken))) {}
        assertThrows(IllegalArgumentException::class.java) { combineAccountMetadataExports(null, null, invalid) {} }
    }

    @Test fun individuallyValidSnapshotsThatExceedTheCombinedExtensionLimitAreNotTruncated() {
        fun size(value: ExchangeDocument) = value.extensionsJson!!.toByteArray(Charsets.UTF_8).size
        val empty = original.copy(extensionsJson = JSONObject(original.extensionsJson!!).put("payload", "").toString())
        val overhead = maxOf(size(ReadingProgressSnapshotJson.withSnapshot(empty, snapshot)),
            size(LibraryOrganizationSnapshotJson.withSnapshot(empty, orgSnapshot))) - size(empty)
        val full = empty.copy(extensionsJson = JSONObject(empty.extensionsJson!!)
            .put("payload", "x".repeat(LibraryExchangeLimits.EXTENSIONS_BYTES - size(empty) - overhead)).toString())
        val provenance = source(entry.copy(document = full))
        val o = org(provenance, full)
        val p = progress(provenance, full)
        assertTrue(size(o.document) <= LibraryExchangeLimits.EXTENSIONS_BYTES)
        assertTrue(size(p.document) <= LibraryExchangeLimits.EXTENSIONS_BYTES)
        assertThrows(IllegalArgumentException::class.java) { combineAccountMetadataExports(null, o, p) {} }
        assertEquals(full, provenance.document)
    }
}
