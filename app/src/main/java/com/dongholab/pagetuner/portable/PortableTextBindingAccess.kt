package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*

/** Text-only binding transactions always acquire the library lock before the binding lock. */
internal class PortableTextBindingAccess(
    private val library: PortableLibraryStore,
    private val bindings: PortableServerBindingStore,
) {
    /** The caller owns outer account/session authority; callback may only acquire the journal next. */
    fun <T> verified(accountKey: String, entry: PortableLibraryEntry, expected: PortableServerBindingSnapshot,
        current: () -> Unit, block: () -> T): T {
        // Freeze every input list before any external guard can mutate its source.
        val selected = detachedTextBindingEntry(entry)
        val captured = expected.copy(binding = expected.binding?.let { it.copy(identity = it.identity.copy()) })
        val linked = requireNotNull(captured.binding) { "Connect this document first." }
        require(linked.accountKey == accountKey)
        return library.withVerifiedDocument(selected) { document ->
            require(document == selected.document) { "The selected document changed." }
            requireIdentity(document, linked.identity)
            bindings.verified(accountKey, selected, captured, current, block)
        }
    }

    fun snapshot(accountKey: String, entry: PortableLibraryEntry): PortableServerBindingSnapshot =
        library.withDocument(entry) { document ->
            requireIdentity(document, requireNotNull(DocumentIdentityJson.fromDocument(entry.document)))
            bindings.readSnapshot(accountKey, entry.copy(document = document))
        }

    /** Read-only v2 observation. Existing explicit connection flows retain their v1 migration. */
    fun capture(accountKey: String, entry: PortableLibraryEntry): PortableServerBindingSnapshot {
        val selected = detachedTextBindingEntry(entry)
        return library.withVerifiedDocument(selected) { document ->
            require(document == selected.document) { "The selected document changed." }
            bindings.capture(accountKey, selected)
        }
    }

    fun save(entry: PortableLibraryEntry, value: PortableServerBinding, expected: PortableServerBindingSnapshot,
        current: () -> Unit) = library.withDocument(entry) { document ->
        current()
        requireIdentity(document, value.identity)
        bindings.saveIfCurrent(value.accountKey, entry.copy(document = document), expected, value, current)
    }

    fun validate(entry: PortableLibraryEntry, expected: PortableServerBinding, current: () -> Unit) =
        library.withDocument(entry) { document ->
            current()
            requireIdentity(document, expected.identity)
            require(bindings.readSnapshot(expected.accountKey, entry.copy(document = document)).binding == expected) {
                "The server connection changed."
            }
        }

    /** A late failed request may only retire the exact generation it observed. */
    fun removeIfCurrent(entry: PortableLibraryEntry, expected: PortableServerBinding): Boolean =
        library.withDocument(entry) { document ->
            requireIdentity(document, expected.identity)
            bindings.removeIfCurrent(expected.accountKey, entry.copy(document = document), expected)
        }

    private fun requireIdentity(document: ExchangeDocument, expected: DocumentIdentity) {
        require(document.assets.isEmpty() && DocumentIdentityJson.fromDocument(document) == expected) {
            "The selected document changed."
        }
    }
}

/** Preserve exact passive JSON while copying all caller-owned lists and checking the ZIP limits. */
internal fun detachedTextBindingEntry(entry: PortableLibraryEntry): PortableLibraryEntry {
    val value = entry.document
    require(value.assets.isEmpty()) { "Text binding access cannot authorize asset-backed documents." }
    val copied = value.copy(paragraphs = value.paragraphs.toList(), outline = value.outline.toList(),
        notes = value.notes.toList(), organization = value.organization.copy(tags = value.organization.tags.toList()),
        glossary = value.glossary.toList(), assets = value.assets.toList())
    LibraryExchangeCodec.write(LibraryExchangePackage("2000-01-01T00:00:00Z", listOf(copied)))
    requireNotNull(DocumentIdentityJson.fromDocument(copied)) { "This document has no verified text identity." }
    return entry.copy(document = copied)
}
