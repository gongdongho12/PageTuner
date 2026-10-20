package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.DocumentIdentity
import com.dongholab.pagetuner.core.backup.exchange.DocumentIdentityJson
import com.dongholab.pagetuner.core.backup.exchange.ExchangeDocument

/** Text-only binding transactions always acquire the library lock before the binding lock. */
internal class PortableTextBindingAccess(
    private val library: PortableLibraryStore,
    private val bindings: PortableServerBindingStore,
) {
    fun snapshot(accountKey: String, entry: PortableLibraryEntry): PortableServerBindingSnapshot =
        library.withDocument(entry) { document ->
            requireIdentity(document, requireNotNull(DocumentIdentityJson.fromDocument(entry.document)))
            bindings.readSnapshot(accountKey, entry.copy(document = document))
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
