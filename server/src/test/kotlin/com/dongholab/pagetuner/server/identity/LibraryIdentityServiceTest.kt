package com.dongholab.pagetuner.server.identity

import com.dongholab.pagetuner.core.backup.exchange.DocumentIdentities
import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.server.translation.TranslationApplicationService
import com.dongholab.pagetuner.server.workflow.SourceChapterMetadataUnavailable
import com.dongholab.pagetuner.server.workflow.SourceChapterStore
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.InvalidDataAccessApiUsageException

class LibraryIdentityServiceTest {
    private val sources = mock(SourceChapterStore::class.java)
    private val translations = mock(TranslationApplicationService::class.java)
    private val service = LibraryIdentityService(sources, translations)
    private val user = "identity-test"
    private val identity = DocumentIdentities.original(ChapterContent(ChapterIdentity(BookIdentity("source", "book"), "chapter"),
        "Chapter", "en", listOf(ContentParagraph("p1", 0, "Original"))))
    private val request = VerifyLibraryIdentityRequest(identity.kind, UUID.randomUUID(), identity)

    @Test fun `typed stored metadata failure becomes unavailable`() {
        doThrow(SourceChapterMetadataUnavailable(IllegalStateException("Stored revision mismatch")))
            .`when`(sources).get(user, request.recordId)
        val failure = assertThrows(LibraryIdentityFailure::class.java) { service.verify(user, request) }
        assertEquals("LIBRARY_IDENTITY_UNAVAILABLE", failure.code)
        assertEquals(409, failure.status)
        verifyNoInteractions(translations)
    }

    @Test fun `infrastructure and query failures are never disguised as corrupt metadata`() {
        for (failure in listOf(DataAccessResourceFailureException("Database unavailable"), InvalidDataAccessApiUsageException("Invalid query"))) {
            doThrow(failure).`when`(sources).get(user, request.recordId)
            assertSame(failure, assertThrows(DataAccessException::class.java) { service.verify(user, request) })
        }
        verifyNoInteractions(translations)
    }
}
