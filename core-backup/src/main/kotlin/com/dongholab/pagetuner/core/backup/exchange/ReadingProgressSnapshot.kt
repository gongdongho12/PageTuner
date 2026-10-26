package com.dongholab.pagetuner.core.backup.exchange

import com.dongholab.pagetuner.core.model.library.LibraryFilter

enum class ReadingProgressSnapshotPresence { ABSENT, PRESENT }

/** Exact UTF-16 text anchor, without a device page or account mutation version. */
data class ReadingProgressSnapshotAnchor(val paragraphId: String, val characterOffset: Int)

/** Passive account observation. Neither importing it nor validating it changes reading position. */
data class ReadingProgressSnapshot(
    val version: Int = 1,
    val identity: DocumentIdentity,
    val presence: ReadingProgressSnapshotPresence,
    val anchor: ReadingProgressSnapshotAnchor?,
)

object ReadingProgressSnapshotValidation {
    const val MAX_PARAGRAPH_ID_CODE_UNITS = 200

    /** Structural validation alone does not establish document membership, ownership or freshness. */
    fun validate(value: ReadingProgressSnapshot) {
        require(value.version == 1) { "Unsupported reading progress snapshot version." }
        DocumentIdentities.validate(value.identity)
        when (value.presence) {
            ReadingProgressSnapshotPresence.ABSENT -> require(value.anchor == null) {
                "An absent reading progress snapshot requires null anchor."
            }
            ReadingProgressSnapshotPresence.PRESENT -> {
                val anchor = requireNotNull(value.anchor) { "A present reading progress snapshot requires an anchor." }
                require(anchor.paragraphId.length in 1..MAX_PARAGRAPH_ID_CODE_UNITS) { "Snapshot paragraph ID exceeds the account limit." }
                require(LibraryFilter.trim(anchor.paragraphId).isNotEmpty()) { "Snapshot paragraph ID must not be blank." }
                require(anchor.paragraphId.none(Char::isISOControl)) { "Snapshot paragraph ID contains a control character." }
                var index = 0
                while (index < anchor.paragraphId.length) {
                    val char = anchor.paragraphId[index++]
                    if (char.isHighSurrogate()) require(index < anchor.paragraphId.length && anchor.paragraphId[index++].isLowSurrogate()) {
                        "Snapshot paragraph ID contains an unpaired surrogate."
                    } else require(!char.isLowSurrogate()) { "Snapshot paragraph ID contains an unpaired surrogate." }
                }
                require(anchor.characterOffset >= 0) { "Snapshot offset must be nonnegative." }
            }
        }
    }

    fun validateDocument(document: ExchangeDocument, value: ReadingProgressSnapshot) {
        validate(value)
        DocumentIdentities.validateDocument(document, value.identity)
        value.anchor?.let { anchor ->
            val paragraph = requireNotNull(document.paragraphs.singleOrNull { it.paragraphId == anchor.paragraphId }) {
                "Snapshot anchor does not identify exactly one document paragraph."
            }
            val offset = anchor.characterOffset
            require(offset <= paragraph.text.length) { "Snapshot offset exceeds paragraph text." }
            require(offset == 0 || offset == paragraph.text.length ||
                !(paragraph.text[offset - 1].isHighSurrogate() && paragraph.text[offset].isLowSurrogate())) {
                "Snapshot offset splits a surrogate pair."
            }
        }
    }
}
