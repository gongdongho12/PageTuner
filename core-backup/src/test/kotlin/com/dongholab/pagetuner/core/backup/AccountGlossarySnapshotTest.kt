package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.content.*
import com.dongholab.pagetuner.core.model.glossary.*
import org.junit.Assert.*
import org.junit.Test

class AccountGlossarySnapshotTest {
    private val source = DocumentIdentities.original(ChapterContent(ChapterIdentity(BookIdentity("source", "book"), "chapter"),
        "Title", "en", listOf(ContentParagraph("p", 0, "Text"))))
    private fun snapshot(state: AccountGlossarySnapshotState = AccountGlossarySnapshotState.PRESENT,
        entries: List<BookGlossarySyncEntry>? = emptyList()) = AccountGlossarySnapshot("source", "book", "ko", state, entries)
    private fun reject(value: AccountGlossarySnapshots) = assertThrows(IllegalArgumentException::class.java) { AccountGlossarySnapshotValidation.validate(value, source) }
    @Test fun emptyPresentAbsentAndDeletedStayDistinctWithoutCasOrDeviceIdentifiers() {
        val entries = listOf(BookGlossarySyncEntry("original-id", " Alice ", " 앨리스 ", " 아리 ", BookGlossarySyncKind.Character, false, false))
        AccountGlossarySnapshotValidation.validate(AccountGlossarySnapshots(snapshots = listOf(snapshot(entries = entries))), source)
        for (state in AccountGlossarySnapshotState.values()) AccountGlossarySnapshotValidation.validate(
            AccountGlossarySnapshots(snapshots = listOf(snapshot(state, if (state == AccountGlossarySnapshotState.PRESENT) emptyList() else null))), source)
        reject(AccountGlossarySnapshots(snapshots = listOf(snapshot(entries = null))))
        reject(AccountGlossarySnapshots(snapshots = listOf(snapshot(AccountGlossarySnapshotState.DELETED))))
    }
    @Test fun wrongSourceScopeDuplicateScopeAndUnselectedLanguageAreRejected() {
        reject(AccountGlossarySnapshots(snapshots = listOf(snapshot().copy(providerId = "different"))))
        reject(AccountGlossarySnapshots(snapshots = listOf(snapshot().copy(bookId = "different"))))
        reject(AccountGlossarySnapshots(snapshots = listOf(snapshot(), snapshot())))
        reject(AccountGlossarySnapshots(version = 2, snapshots = listOf(snapshot())))
        reject(AccountGlossarySnapshots(snapshots = List(17) { snapshot().copy(targetLanguage = "lang-${it}") }))
        assertThrows(IllegalArgumentException::class.java) { AccountGlossarySnapshotValidation.validateRequested(snapshot(), source, "en") }
    }
}
