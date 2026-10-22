package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*

internal data class PreparedAccountOrganization(
    val document: ExchangeDocument,
    val snapshot: LibraryOrganizationSnapshot,
    val checkBeforeWrite: () -> Unit,
)

/** Read-only account observation. No controller, mutation API, or journal writer is opened. */
internal class PortableAccountOrganizationExport(
    private val currentDocument: suspend (PortableLibraryEntry) -> ExchangeDocument,
    private val readDocument: (PortableLibraryEntry) -> ExchangeDocument,
    private val binding: (String, PortableLibraryEntry) -> PortableServerBinding?,
    private val journal: (ServerReadingTarget) -> DeviceLibraryOrganization,
    private val verify: suspend (ServerReadingConnection, String, DocumentIdentity) -> Unit = { connection, recordId, identity ->
        connection.client.verifyPortableIdentity(recordId, identity)
    },
    private val query: suspend (ServerReadingConnection, String, String) -> ServerLibraryOrganization = { connection, kind, recordId ->
        connection.client.libraryOrganization(kind, recordId)
    },
) {
    suspend fun prepare(entry: PortableLibraryEntry, connection: ServerReadingConnection,
        checkCurrent: () -> Unit): PreparedAccountOrganization {
        checkCurrent()
        val document = detach(currentDocument(entry))
        checkCurrent()
        val selected = entry.copy(document = document)
        val linked = requireNotNull(binding(connection.accountKey, selected)) { "Connect this document to server records first." }
        require(linked.accountKey == connection.accountKey && linked.localKey == selected.key)
        require(document.assets.isEmpty() && DocumentIdentityJson.fromDocument(document) == linked.identity) {
            "The document no longer matches its server connection."
        }
        val identity = linked.identity
        val target = ServerReadingTarget(connection.accountKey, identity.kind.name, linked.recordId)
        ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
        // Validate all document limits and any existing own snapshot before making a request.
        LibraryExchangeCodec.write(LibraryExchangePackage("2000-01-01T00:00:00Z", listOf(document)))
        LibraryOrganizationSnapshotJson.fromDocument(document)
        var highWater: ServerLibraryOrganization? = null
        var observed: ServerLibraryOrganization? = null
        fun capture(value: ServerLibraryOrganization): ServerLibraryOrganization =
            ServerLibraryOrganizationJson.view(ServerLibraryOrganizationJson.encode(value), target.kind, target.recordId)
        fun atLeast(value: ServerLibraryOrganization, known: ServerLibraryOrganization) {
            require(value.version >= known.version && (value.version != known.version || value == known)) {
                "The account organization changed while exporting. Read it again."
            }
        }
        fun canonical() {
            checkCurrent()
            val latest = readDocument(selected)
            require(latest == document && binding(connection.accountKey, selected.copy(document = latest)) == linked) {
                "The document or its server connection changed. Export again."
            }
            checkCurrent()
        }
        fun guard() {
            canonical()
            val local = journal(target)
            require(local.pending == null && local.queued == null && local.conflict == null) {
                "Resolve pending organization edits and conflicts before exporting the latest account organization."
            }
            local.remote?.let { remote ->
                val known = capture(remote)
                highWater?.let { atLeast(known, it) }
                highWater = known
            }
            // Keep the pre-GET observation even if a concurrent journal read loses its remote.
            observed?.let { fresh -> highWater?.let { atLeast(fresh, it) } }
            canonical()
        }
        guard()
        verify(connection, linked.recordId, identity)
        guard()
        val response = query(connection, target.kind, target.recordId)
        checkCurrent()
        val fresh = capture(response)
        observed = fresh
        guard()
        val snapshot = LibraryOrganizationSnapshot(identity = identity,
            presence = if (fresh.version == 0L) LibraryOrganizationSnapshotPresence.ABSENT else LibraryOrganizationSnapshotPresence.PRESENT,
            organization = fresh.organization?.let { LibraryOrganizationSnapshotValue(it.folder, it.tags.toList(), it.favorite) })
        val updated = LibraryOrganizationSnapshotJson.withSnapshot(document, snapshot)
        guard()
        return PreparedAccountOrganization(updated, snapshot, ::guard)
    }

    /** Keep raw extension strings while detaching every mutable collection before any suspension. */
    private fun detach(value: ExchangeDocument): ExchangeDocument = value.copy(
        paragraphs = value.paragraphs.toList(), outline = value.outline.toList(), notes = value.notes.toList(),
        organization = value.organization.copy(tags = value.organization.tags.toList()),
        glossary = value.glossary.toList(), assets = value.assets.toList(),
    )
}
