package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncEntry
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncKind
import org.junit.Assert.*
import org.junit.Test

class BookGlossarySnapshotsTest {
    private val identity = BookGlossarySyncIdentity("provider|原", "book:42|🌏", "ko")
    private val entry = BookGlossarySyncEntry("opaque-2🌏", " River ", " 강 ", "  ", BookGlossarySyncKind.Place, false, false)
    private fun present(entries: List<BookGlossarySyncEntry> = listOf(entry)) =
        BookGlossarySnapshot(identity, BookGlossarySnapshotPresence.PRESENT, entries)
    private fun validate(vararg snapshots: BookGlossarySnapshot) =
        BookGlossarySnapshotValidation.validate(BookGlossarySnapshots(snapshots = snapshots.toList()))
    private fun reject(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java, block)

    @Test fun originalScopeOrderMetadataAndDuplicateSourcesArePreservedWithoutNormalization() {
        val entries = listOf(entry, entry.copy(id = "opaque-1", kind = BookGlossarySyncKind.Character, enabled = true),
            entry.copy(id = "opaque-3", kind = BookGlossarySyncKind.Term, displayTerm = "", caseSensitive = true))
        val snapshots = BookGlossarySnapshots(snapshots = listOf(present(entries), present(entries).copy(identity = identity.copy(targetLanguage = "en"))))
        BookGlossarySnapshotValidation.validate(snapshots)
        assertEquals(entries, snapshots.snapshots[0].entries)
        assertEquals(listOf("ko", "en"), snapshots.snapshots.map { it.identity.targetLanguage })
        assertEquals(" River ", snapshots.snapshots[0].entries!![0].sourceTerm)
        assertEquals("  ", snapshots.snapshots[0].entries!![0].displayTerm)
        assertFalse(snapshots.snapshots[0].entries!![0].enabled)
    }

    @Test fun absentDeletedAndEmptyPresentRemainDifferentValues() {
        val absent = BookGlossarySnapshot(identity, BookGlossarySnapshotPresence.ABSENT, null)
        val deleted = absent.copy(presence = BookGlossarySnapshotPresence.DELETED)
        listOf(absent, deleted, present(emptyList())).forEach { validate(it) }
        assertNotEquals(absent, deleted)
        assertNotEquals(deleted, present(emptyList()))
        reject { validate(absent.copy(entries = emptyList())) }
        reject { validate(deleted.copy(entries = emptyList())) }
        reject { validate(present().copy(entries = null)) }
        BookGlossarySnapshotValidation.validate(BookGlossarySnapshots(snapshots = emptyList()))
    }

    @Test fun duplicateScopesUseFullTupleInsteadOfAnAmbiguousJoinedKey() {
        reject { validate(present(), present()) }
        validate(present(), present().copy(identity = identity.copy(providerId = "other")))
        validate(present().copy(identity = BookGlossarySyncIdentity("a|b", "c", "ko")),
            present().copy(identity = BookGlossarySyncIdentity("a", "b|c", "ko")))
        reject { validate(present(listOf(entry, entry.copy(sourceTerm = "Other")))) }
    }

    @Test fun scopeAndEntryBoundsRejectInvalidValuesRatherThanRepairingThem() {
        listOf(identity.copy(providerId = " provider"), identity.copy(bookId = "book\ufeff"),
            identity.copy(targetLanguage = "KO"), identity.copy(targetLanguage = "auto"),
            identity.copy(bookId = "book\u0085"), identity.copy(bookId = "bad\ud800")).forEach {
            reject { validate(present().copy(identity = it)) }
        }
        listOf(entry.copy(id = " padded "), entry.copy(id = "i".repeat(201)), entry.copy(sourceTerm = "\u00a0"),
            entry.copy(translatedTerm = "t".repeat(201)), entry.copy(displayTerm = "\udc00"),
            entry.copy(sourceTerm = "line\nfeed")).forEach { reject { validate(present(listOf(it))) } }
    }

    @Test fun structuralLimitsAllowTheirBoundaryWithoutPromisingSerializedByteCapacity() {
        val fiveHundred = (0 until 500).map { entry.copy(id = "id-$it") }
        validate(present(fiveHundred))
        reject { validate(present(fiveHundred + entry.copy(id = "id-500"))) }
        val scopes = (0 until 100).map { present(emptyList()).copy(identity = identity.copy(bookId = "book-$it")) }
        BookGlossarySnapshotValidation.validate(BookGlossarySnapshots(snapshots = scopes))
        reject { BookGlossarySnapshotValidation.validate(BookGlossarySnapshots(snapshots = scopes + present())) }
        reject { BookGlossarySnapshotValidation.validate(BookGlossarySnapshots(version = 2, snapshots = emptyList())) }
    }
}
