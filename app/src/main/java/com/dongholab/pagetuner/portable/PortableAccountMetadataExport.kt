package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.ServerReadingConnection

/** Private provenance only; account/client/binding never become ZIP metadata. */
internal class PortableAccountExportSource(val key: String, document: ExchangeDocument,
    val connection: ServerReadingConnection, val binding: PortableServerBinding) {
    private val canonical = detachedMetadataDocument(document)
    val document: ExchangeDocument get() = detachedMetadataDocument(canonical)
}

private fun detachedMetadataDocument(value: ExchangeDocument) = value.copy(paragraphs = value.paragraphs.toList(),
    outline = value.outline.toList(), notes = value.notes.toList(), glossary = value.glossary.toList(),
    organization = value.organization.copy(tags = value.organization.tags.toList()), assets = value.assets.toList())

internal fun detachedAccountExportSource(entry: PortableLibraryEntry, connection: ServerReadingConnection,
    binding: PortableServerBinding): PortableAccountExportSource {
    val selected = detachedTextBindingEntry(entry)
    require(binding.localKey == selected.key && binding.accountKey == connection.accountKey &&
        binding.identity == DocumentIdentityJson.fromDocument(selected.document))
    return PortableAccountExportSource(selected.key, selected.document, connection,
        binding.copy(identity = binding.identity.copy()))
}

internal data class PreparedAccountMetadata(val document: ExchangeDocument, val checkBeforeWrite: () -> Unit)

/** Existing callers keep the same API until the VM explicitly supplies a text operation ticket. */
internal fun combineAccountMetadataExports(glossary: PreparedAccountGlossary?, organization: PreparedAccountOrganization?,
    current: () -> Unit): PreparedAccountMetadata = combineAccountMetadataExports(glossary, organization, null, current)

/** Compose on one captured canonical source, retaining every source guard through final save. */
internal fun combineAccountMetadataExports(glossary: PreparedAccountGlossary?, organization: PreparedAccountOrganization?,
    progress: PreparedAccountReadingProgress?, current: () -> Unit): PreparedAccountMetadata {
    require(glossary != null || organization != null || progress != null)
    val glossaryGuard = glossary?.checkBeforeWrite
    val organizationGuard = organization?.checkBeforeWrite
    val progressGuard = progress?.checkBeforeWrite
    val guard = {
        current()
        glossaryGuard?.invoke()
        organizationGuard?.invoke()
        progressGuard?.invoke()
        current()
    }
    guard()
    val sources = listOfNotNull(glossary?.let { it.source }, organization?.let { it.source }, progress?.let { it.source })
    val count = listOf(glossary, organization, progress).count { it != null }
    // Old synthetic glossary/organization preparations remain supported; progress never borrows them.
    require(progress == null && sources.isEmpty() || sources.size == count) { "All account exports must retain their canonical source." }
    val canonical = sources.firstOrNull()?.also { first ->
        require(sources.all { it.key == first.key && it.document == first.document &&
            it.connection.accountKey == first.connection.accountKey && it.connection.client === first.connection.client &&
            it.binding == first.binding }) { "The account exports belong to different canonical documents or connections." }
    }?.document
    val g = glossary?.snapshot?.let { BookGlossarySnapshotsJson.decode(
        BookGlossarySnapshotsJson.encode(BookGlossarySnapshots(snapshots = listOf(it)))).snapshots.single() }
    val o = organization?.snapshot?.let { LibraryOrganizationSnapshotJson.decode(LibraryOrganizationSnapshotJson.encode(it)) }
    val p = progress?.snapshot?.let { ReadingProgressSnapshotJson.decode(ReadingProgressSnapshotJson.encode(it)) }
    fun withGlossary(document: ExchangeDocument): ExchangeDocument {
        val value = requireNotNull(g)
        val existing = BookGlossarySnapshotsJson.fromDocument(document)?.snapshots.orEmpty()
        val snapshots = if (existing.any { it.identity == value.identity })
            existing.map { if (it.identity == value.identity) value else it } else existing + value
        return BookGlossarySnapshotsJson.withSnapshots(document, BookGlossarySnapshots(snapshots = snapshots))
    }
    if (canonical != null) {
        glossary?.let { require(it.document == withGlossary(canonical)) { "The prepared glossary document changed." } }
        organization?.let { require(it.document == LibraryOrganizationSnapshotJson.withSnapshot(canonical, requireNotNull(o))) {
            "The prepared organization document changed." } }
        progress?.let { require(it.document == ReadingProgressSnapshotJson.withSnapshot(canonical, requireNotNull(p))) {
            "The prepared reading progress document changed." } }
    }
    var document = canonical ?: glossary?.document ?: organization?.document ?: requireNotNull(progress).document
    if (g != null) document = withGlossary(document)
    if (o != null) document = LibraryOrganizationSnapshotJson.withSnapshot(document, o)
    if (p != null) document = ReadingProgressSnapshotJson.withSnapshot(document, p)
    LibraryExchangeCodec.write(LibraryExchangePackage("2000-01-01T00:00:00Z", listOf(document)))
    guard()
    return PreparedAccountMetadata(document, guard)
}
