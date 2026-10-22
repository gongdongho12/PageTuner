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
        // Detach every list before retaining a save guard; callers may own mutable collections.
        val document = LibraryExchangeCodec.read(LibraryExchangeCodec.write(
            LibraryExchangePackage(portableTimestamp(), listOf(currentDocument(entry))))).documents.single()
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
        var highWater: ServerBookGlossary? = null
        fun detached(value: ServerBookGlossary) = ServerBookGlossaryJson.view(ServerBookGlossaryJson.encode(value), target.identity)
        fun requireFresh(known: ServerBookGlossary, fresh: ServerBookGlossary) {
            require(known.identity == fresh.identity && known.version <= fresh.version &&
                (known.version != fresh.version || known == fresh)) {
                "The account glossary changed while exporting. Read it again."
            }
        }
        fun canonical() {
            checkCurrent()
            val latest = selected.copy(document = readDocument(selected))
            require(latest.document == document && binding(connection.accountKey, latest) == linked &&
                DocumentIdentityJson.fromDocument(latest.document) == identity) { "The document server connection changed. Export again." }
            checkCurrent()
        }
        fun guard() {
            canonical()
            val local = journal(target)
            require(local.pending == null && local.queued == null && local.conflict == null) {
                "Resolve pending glossary edits and conflicts before exporting the latest account glossary."
            }
            local.remote?.let(::detached)?.let { known ->
                val previous = highWater
                if (previous == null || known.version > previous.version) highWater = known
                else if (known.version == previous.version) requireFresh(previous, known)
            }
            observed?.let { fresh -> highWater?.let { requireFresh(it, fresh) } }
            // A blocking journal read must not hide a concurrent reader write or archive deletion.
            canonical()
        }
        guard()
        verify(connection, linked.recordId, identity)
        guard()
        val fresh = detached(query(connection, target.identity))
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
