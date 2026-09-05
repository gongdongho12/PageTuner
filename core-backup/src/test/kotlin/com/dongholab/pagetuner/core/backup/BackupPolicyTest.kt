package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPolicyTest {
    private val original = artifact("번역문")
    private val desired = TranslationBackupKey.from("user-1", "drive-1", original)

    @Test
    fun completedSameRevisionSkipsDriveUpload() {
        val decision = TranslationBackupPolicy.decide(
            desired,
            listOf(TranslationBackupRecord(desired, BackupState.Verified, "drive-file-1")),
        )

        assertTrue(decision is BackupDecision.SkipAlreadyBackedUp)
    }

    @Test
    fun concurrentSameRevisionReusesExistingJob() {
        val decision = TranslationBackupPolicy.decide(
            desired,
            listOf(TranslationBackupRecord(desired, BackupState.Uploading)),
        )

        assertTrue(decision is BackupDecision.ReuseActiveJob)
    }

    @Test
    fun serverOnlyOrEditedTranslationEnqueuesOneBackup() {
        assertEquals(BackupDecision.Enqueue, TranslationBackupPolicy.decide(desired, emptyList()))

        val edited = TranslationBackupKey.from("user-1", "drive-1", artifact("수정된 번역문"))
        val decision = TranslationBackupPolicy.decide(
            edited,
            listOf(TranslationBackupRecord(desired, BackupState.Verified, "drive-file-1")),
        )

        assertEquals(BackupDecision.Enqueue, decision)
    }

    private fun artifact(text: String) = TranslationArtifact(
        chapter = ChapterIdentity(BookIdentity("wtr-lab", "book-42"), "chapter-7"),
        sourceRevision = "source-r1",
        sourceLanguage = "en",
        targetLanguage = "ko",
        providerId = "deepseek",
        modelId = "deepseek-chat",
        promptRevision = "prompt-v1",
        glossaryRevision = "glossary-v2",
        paragraphs = listOf(TranslatedParagraph("p-1", text)),
    )
}
