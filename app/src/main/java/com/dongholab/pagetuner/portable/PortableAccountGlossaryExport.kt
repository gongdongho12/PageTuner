package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncValidation
import com.dongholab.pagetuner.translation.sync.*

internal data class PreparedAccountGlossary(val document: ExchangeDocument, val snapshot: BookGlossarySnapshot,
    val checkBeforeWrite: () -> Unit)

/** Fresh read adapter. No sync controller, journal writes, or glossary mutation API is available here. */
internal class PortableAccountGlossaryExport(
    private val currentDocument: suspend (PortableLibraryEntry) -> ExchangeDocument,
    private val readDocument: (PortableLibraryEntry) -> ExchangeDocument,
    private val binding: (String, PortableLibraryEntry) -> PortableServerBinding?,
    private val journal: (BookGlossaryTarget) -> DeviceBookGlossary,
    private val verify: suspend (ServerReadingConnection, String, DocumentIdentity) -> Unit = { connection, recordId, identity ->
        connection.client.verifyPortableIdentity(recordId, identity)
    },
    private val query: suspend (ServerReadingConnection, BookGlossarySyncIdentity) -> ServerBookGlossary = { connection, identity ->
        connection.client.bookGlossary(identity)
    },
) {
    suspend fun prepare(entry: PortableLibraryEntry, targetLanguage: String, connection: ServerReadingConnection,
        checkCurrent: () -> Unit): PreparedAccountGlossary {
        checkCurrent()
        val document = currentDocument(entry)
        checkCurrent()
        val selected = entry.copy(document = document)
        val linked = requireNotNull(binding(connection.accountKey, selected)) { "Connect this document to server records first." }
        require(linked.accountKey == connection.accountKey && linked.localKey == selected.key)
        require(document.assets.isEmpty() && DocumentIdentityJson.fromDocument(document) == linked.identity) {
            "The document no longer matches its server connection."
        }
        val identity = linked.identity
        if (identity.kind == DocumentIdentityKind.TRANSLATION) require(targetLanguage == identity.targetLanguage) {
            "Use the exact language of this translation."
        }
        val target = BookGlossaryTarget(connection.accountKey,
            BookGlossarySyncIdentity(identity.contentProviderId, identity.bookId, targetLanguage)
                .also(BookGlossarySyncValidation::validateIdentity))
        // Unsupported existing data must not be overwritten even when the selected scope is replaced.
        val existing = BookGlossarySnapshotsJson.fromDocument(document)?.snapshots.orEmpty()
        var observed: ServerBookGlossary? = null
        fun guard() {
            checkCurrent()
            val latest = selected.copy(document = readDocument(selected))
            require(binding(connection.accountKey, latest) == linked &&
                DocumentIdentityJson.fromDocument(latest.document) == identity) { "The document server connection changed. Export again." }
            val local = journal(target)
            require(local.pending == null && local.queued == null && local.conflict == null) {
                "Resolve pending glossary edits and conflicts before exporting the latest account glossary."
            }
            observed?.let { fresh -> local.remote?.let { known ->
                require(known.identity == fresh.identity && known.version <= fresh.version &&
                    (known.version != fresh.version || known == fresh)) {
                    "The account glossary changed while exporting. Read it again."
                }
            } }
            checkCurrent()
        }
        guard()
        verify(connection, linked.recordId, identity)
        guard()
        val fresh = query(connection, target.identity)
        checkCurrent()
        require(fresh.identity == target.identity)
        // Validate the response as strictly as the HTTP adapter, including version-zero semantics.
        ServerBookGlossaryJson.encode(fresh)
        observed = fresh
        guard()
        val snapshot = BookGlossarySnapshot(target.identity, when {
            fresh.version == 0L -> BookGlossarySnapshotPresence.ABSENT
            fresh.value.entries == null -> BookGlossarySnapshotPresence.DELETED
            else -> BookGlossarySnapshotPresence.PRESENT
        }, fresh.value.entries?.map { it.syncEntry() })
        val snapshots = if (existing.any { it.identity == target.identity })
            existing.map { if (it.identity == target.identity) snapshot else it }
        else existing + snapshot
        val updated = BookGlossarySnapshotsJson.withSnapshots(document, BookGlossarySnapshots(snapshots = snapshots))
        guard()
        return PreparedAccountGlossary(updated, snapshot, ::guard)
    }
}
