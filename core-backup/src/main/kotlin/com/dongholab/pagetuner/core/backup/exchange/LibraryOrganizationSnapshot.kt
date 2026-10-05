package com.dongholab.pagetuner.core.backup.exchange

import com.dongholab.pagetuner.core.model.library.LibraryFilter

enum class LibraryOrganizationSnapshotPresence { ABSENT, PRESENT }

/** Exact account classification values, separate from the broader legacy exchange organization. */
data class LibraryOrganizationSnapshotValue(
    val folder: String,
    val tags: List<String>,
    val favorite: Boolean,
)

/** A passive observed value, without account ownership, server versions or mutation authority. */
data class LibraryOrganizationSnapshot(
    val version: Int = 1,
    val identity: DocumentIdentity,
    val presence: LibraryOrganizationSnapshotPresence,
    val organization: LibraryOrganizationSnapshotValue?,
)

object LibraryOrganizationSnapshotValidation {
    const val MAX_FOLDER_CODE_UNITS = 200
    const val MAX_TAGS = 32
    const val MAX_TAG_CODE_UNITS = 60

    /** Structural validation only; document-bound consumers must also validate the complete text. */
    fun validate(value: LibraryOrganizationSnapshot) {
        require(value.version == 1) { "Unsupported library organization snapshot version." }
        DocumentIdentities.validate(value.identity)
        when (value.presence) {
            LibraryOrganizationSnapshotPresence.ABSENT -> require(value.organization == null) {
                "An absent organization snapshot requires null organization."
            }
            LibraryOrganizationSnapshotPresence.PRESENT -> validateValue(requireNotNull(value.organization) {
                "A present organization snapshot requires complete organization values."
            })
        }
    }

    /** Text proof is not an account binding; asset-backed documents remain unsupported by identity v1. */
    fun validateDocument(document: ExchangeDocument, snapshot: LibraryOrganizationSnapshot) {
        validate(snapshot)
        DocumentIdentities.validateDocument(document, snapshot.identity)
    }

    private fun validateValue(value: LibraryOrganizationSnapshotValue) {
        canonical(value.folder, 0, MAX_FOLDER_CODE_UNITS)
        require(value.tags.size <= MAX_TAGS) { "Too many organization snapshot tags." }
        require(value.tags.toSet().size == value.tags.size) { "Duplicate organization snapshot tag." }
        value.tags.forEach { canonical(it, 1, MAX_TAG_CODE_UNITS) }
    }

    private fun canonical(value: String, minimum: Int, maximum: Int) {
        require(value.length in minimum..maximum) { "Organization snapshot text exceeds its length limit." }
        require(LibraryFilter.trim(value) == value) { "Organization snapshot text must already be trimmed." }
        require(value.none(Char::isISOControl)) { "Organization snapshot text contains a control character." }
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                require(index < value.length && value[index++].isLowSurrogate()) {
                    "Organization snapshot text contains an unpaired surrogate."
                }
            } else require(!char.isLowSurrogate()) {
                "Organization snapshot text contains an unpaired surrogate."
            }
        }
    }
}
