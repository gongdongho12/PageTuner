package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.content.*
import com.dongholab.pagetuner.core.translation.*
import com.dongholab.pagetuner.translation.sync.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PortableIdentityVerificationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun server() = NotesFixture.document().source
    private fun entry() = PortableLibraryEntry("a".repeat(64), 0, PortableDocumentMapper.server(server()).documents.single())
    private fun connection() = NotesFixture.connection { error("No implicit HTTP request is allowed") }

    @Test fun canonicalServerExportSurvivesReadingChangesAndReexportWithoutLocalIdInference() {
        val exported = PortableDocumentMapper.server(server())
        val source = requireNotNull(server().sourceContent)
        val proof = requireNotNull(DocumentIdentityJson.fromDocument(exported.documents.single()))
        assertEquals(source.identity.book.providerId, proof.contentProviderId)
        assertEquals(source.identity.book.bookId, proof.bookId)
        assertEquals(source.identity.chapterId, proof.chapterId)
        val store = PortableLibraryStore(temporary.newFolder())
        val entry = store.importArchive(LibraryExchangeCodec.write(exported)).entries.single()
        store.update(entry) { it.copy(bookTitle = "Renamed", position = ExchangeAnchor("p1", 6),
            organization = ExchangeOrganization("Folder", listOf("tag"), true)) }
        val reopened = LibraryExchangeCodec.read(store.export(entry)).documents.single()
        assertEquals(proof, DocumentIdentityJson.fromDocument(reopened))
        assertEquals("Renamed", reopened.bookTitle)
        assertEquals(NotesFixture.recordId, PortableIdentityVerification.recordHint(reopened))
        assertEquals(NotesFixture.recordId, JSONObject(reopened.extensionsJson!!).getString("serverRecordId"))
        val legacyHint = reopened.copy(extensionsJson = JSONObject(reopened.extensionsJson).also { it.remove("serverRecordId") }.toString())
        assertEquals(NotesFixture.recordId, PortableIdentityVerification.recordHint(legacyHint))
        val distinctHint = reopened.copy(extensionsJson = JSONObject(reopened.extensionsJson).put("serverRecordId", NotesFixture.otherId).toString())
        assertEquals(NotesFixture.otherId, PortableIdentityVerification.recordHint(distinctHint))
        assertEquals("", PortableIdentityVerification.recordHint(reopened.copy(extensionsJson = JSONObject(reopened.extensionsJson)
            .put("serverRecordId", "invalid").toString())))
    }

    @Test fun translationUsesOriginalSourceIdentityAndArtifactLanguageNotTranslatorAsContentProvider() {
        val source = server().sourceContent!!
        val artifact = TranslationArtifact(source.identity, source.sourceRevision, "en", "ko", "google-web", "model", "prompt", "glossary",
            source.paragraphs.map { TranslatedParagraph(it.paragraphId, "번역 ${it.ordinal}") })
        val value = ServerLibraryDocument(ServerLibraryEntry(NotesFixture.otherId, "Translation", "ko", 3,
            source.sourceRevision, ServerLibraryKind.Translations), artifact.paragraphs.map { it.text },
            StoredTranslation(NotesFixture.otherId, artifact, NotesFixture.time))
        val document = PortableDocumentMapper.server(value).documents.single()
        val identity = requireNotNull(DocumentIdentityJson.fromDocument(document))
        assertEquals(DocumentIdentityKind.TRANSLATION, identity.kind)
        assertEquals("source", identity.contentProviderId)
        assertEquals("google-web", identity.translationProviderId)
        assertEquals("ko", identity.targetLanguage)
        assertEquals(artifact.payloadHash, identity.payloadHash)
    }

    @Test fun legacyLongSourceIdentityStillExportsAndInvalidProofDoesNotBlockReadingOrReexport() {
        val source = server().sourceContent!!
        val longSource = source.copy(identity = source.identity.copy(book = BookIdentity("source", "書".repeat(2001))))
        val legacy = PortableDocumentMapper.server(server().copy(sourceContent = longSource)).documents.single()
        assertNull(DocumentIdentityJson.fromDocument(legacy))
        assertEquals(R.string.portable_identity_legacy, PortableIdentityVerification.inspect(legacy).second)
        val normal = entry().document
        val changed = normal.copy(paragraphs = normal.paragraphs.mapIndexed { index, paragraph ->
            if (index == 0) paragraph.copy(text = "Different") else paragraph })
        val roundTrip = LibraryExchangeCodec.read(LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(changed)))).documents.single()
        assertEquals(changed, roundTrip)
        assertEquals(R.string.portable_identity_invalid, PortableIdentityVerification.inspect(roundTrip).second)
        assertEquals("Different", PortableDocumentMapper.reader(roundTrip, emptyList(), "reader").document.pages.first().plainText)
        assertEquals(R.string.portable_identity_assets, PortableIdentityVerification.inspect(normal.copy(
            assets = listOf(ExchangeAssetReference("assets/" + "b".repeat(64), "pdf")))).second)
    }

    @Test fun verificationRequiresExplicitActionAndNeverMutatesDocument() = runTest {
        val entry = entry()
        val connection = connection()
        var requests = 0
        val verification = PortableIdentityVerification(this, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { _, record, identity ->
            requests++; assertEquals(NotesFixture.recordId, record)
            assertEquals(DocumentIdentityJson.fromDocument(entry.document), identity)
        }
        verification.select(entry, connection)
        runCurrent()
        assertEquals(0, requests)
        verification.check(verification.state.value.session, connection) { it.document }
        runCurrent()
        assertEquals(1, requests)
        assertEquals(R.string.portable_identity_verified, verification.state.value.message)
        assertEquals(entry, verification.state.value.entry)
    }

    @Test fun sameAccountReconnectionDifferentDocumentAndEditedUuidDiscardLateSuccess() = runTest {
        for (change in listOf("connection", "document", "record", "close")) {
            val entry = entry()
            val connection = connection()
            val released = CompletableDeferred<Unit>()
            val verification = PortableIdentityVerification(this, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { _, _, _ -> withContext(NonCancellable) { released.await() } }
            verification.select(entry, connection)
            runCurrent()
            verification.check(verification.state.value.session, connection) { it.document }
            runCurrent()
            assertTrue(verification.state.value.busy)
            when (change) {
                "connection" -> verification.connect(this@PortableIdentityVerificationTest.connection())
                "document" -> verification.select(entry.copy(documentIndex = 1), connection)
                "record" -> verification.updateRecord(verification.state.value.session, NotesFixture.otherId)
                "close" -> verification.close()
            }
            runCurrent()
            val current = verification.state.value
            released.complete(Unit)
            runCurrent()
            assertEquals(change, current, verification.state.value)
            assertFalse(verification.state.value.busy)
            assertNotEquals(R.string.portable_identity_verified, verification.state.value.message)
        }
    }

    @Test fun invalidRecordOrChangedArchiveNeverReachesTheServerAndMismatchCanRetry() = runTest {
        val entry = entry()
        val connection = connection()
        var requests = 0
        val verification = PortableIdentityVerification(this, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { _, _, _ ->
            if (++requests == 1) throw PortableIdentityVerificationException(PortableIdentityFailure.Mismatch)
        }
        verification.select(entry, connection)
        runCurrent()
        verification.updateRecord(verification.state.value.session, " " + NotesFixture.recordId)
        verification.check(verification.state.value.session, connection) { it.document }
        assertEquals(R.string.portable_identity_record_invalid, verification.state.value.message)
        verification.updateRecord(verification.state.value.session, NotesFixture.recordId)
        verification.check(verification.state.value.session, connection) { it.document.copy(paragraphs = it.document.paragraphs.reversed()) }
        runCurrent()
        assertEquals(0, requests)
        assertEquals(R.string.portable_identity_invalid, verification.state.value.message)
        verification.check(verification.state.value.session, connection) { it.document }
        runCurrent()
        assertEquals(R.string.portable_identity_mismatch, verification.state.value.message)
        verification.check(verification.state.value.session, connection) { it.document }
        runCurrent()
        assertEquals(2, requests)
        assertEquals(R.string.portable_identity_verified, verification.state.value.message)
    }
    @Test fun bindingRequiresSeparateSuccessfulCheckAndRechecksBeforeSaving() = runTest {
        val entry = entry()
        val connection = connection()
        var checks = 0
        val saved = mutableListOf<PortableServerBinding>()
        val controller = PortableIdentityVerification(this, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { _, _, _ -> checks++ }
        controller.select(entry, connection)
        runCurrent()
        controller.bind(controller.state.value.session, connection, { it.document }, { saved += it })
        runCurrent()
        assertTrue(saved.isEmpty())
        controller.check(controller.state.value.session, connection) { it.document }
        runCurrent()
        assertTrue(controller.state.value.verified)
        assertEquals(1, checks)
        assertTrue(saved.isEmpty())
        controller.bind(controller.state.value.session, connection, { it.document }, { saved += it })
        runCurrent()
        assertEquals(2, checks)
        assertEquals(1, saved.size)
        assertEquals(connection.accountKey, saved.single().accountKey)
        assertEquals(entry.key, saved.single().localKey)
        assertEquals(DocumentIdentityJson.fromDocument(entry.document), saved.single().identity)
    }

    @Test fun recordEditAndLogoutDiscardPermissionToBind() = runTest {
        val entry = entry()
        val connection = connection()
        var saves = 0
        val controller = PortableIdentityVerification(this, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { _, _, _ -> }
        controller.select(entry, connection); runCurrent()
        controller.check(controller.state.value.session, connection) { it.document }; runCurrent()
        controller.updateRecord(controller.state.value.session, NotesFixture.otherId)
        assertFalse(controller.state.value.verified)
        controller.bind(controller.state.value.session, connection, { it.document }, { saves++ }); runCurrent()
        assertEquals(0, saves)
        controller.check(controller.state.value.session, connection) { it.document }; runCurrent()
        controller.connect(null); runCurrent()
        assertFalse(controller.state.value.verified)
        controller.bind(controller.state.value.session, null, { it.document }, { saves++ }); runCurrent()
        assertEquals(0, saves)
    }

    @Test fun unbindRemovesOnlySelectedAccountAssociationAndInvalidatesCheckedSession() = runTest {
        val entry = entry()
        val connection = connection()
        val removed = mutableListOf<Pair<String, String>>()
        val controller = PortableIdentityVerification(this, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { _, _, _ -> }
        controller.select(entry, connection); runCurrent()
        controller.check(controller.state.value.session, connection) { it.document }; runCurrent()
        val session = controller.state.value.session
        controller.unbind(session, connection) { account, selected -> removed += account to selected.key }
        assertEquals(listOf(connection.accountKey to entry.key), removed)
        assertFalse(controller.state.value.verified)
        assertTrue(controller.state.value.session != session)
        assertEquals(entry, controller.state.value.entry)
        controller.unbind(session, connection) { _, _ -> fail("Stale action must not delete a binding") }
    }

}
