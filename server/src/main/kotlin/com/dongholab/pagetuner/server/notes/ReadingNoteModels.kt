package com.dongholab.pagetuner.server.notes

import com.dongholab.pagetuner.server.progress.ReadingProgressAnchor
import com.dongholab.pagetuner.server.progress.ReadingProgressKind
import java.time.Instant
import java.util.UUID

enum class ReadingNoteKind { BOOKMARK, NOTE, HIGHLIGHT }
data class ReadingNoteRange(val start: ReadingProgressAnchor, val end: ReadingProgressAnchor)
data class ReadingNoteInput(
    val kind: ReadingNoteKind,
    val title: String,
    val text: String,
    val anchor: ReadingProgressAnchor,
    val range: ReadingNoteRange?,
    val createdAt: Instant,
)
data class ReadingNote(
    val kind: ReadingNoteKind,
    val title: String,
    val text: String,
    val anchor: ReadingProgressAnchor,
    val range: ReadingNoteRange?,
    val createdAt: Instant,
    val excerpt: String,
)
data class PutReadingNoteRequest(val expectedVersion: Long, val mutationId: UUID, val deleted: Boolean, val note: ReadingNoteInput?)
data class ReadingNoteItem(
    val noteId: UUID, val version: Long, val changeRevision: Long, val deleted: Boolean,
    val note: ReadingNote?, val updatedAt: Instant?,
)
data class ReadingNoteChanges(
    val kind: ReadingProgressKind, val recordId: UUID, val items: List<ReadingNoteItem>,
    val nextAfterRevision: Long, val watermark: Long, val hasMore: Boolean,
)
class ReadingNoteFailure(
    val code: String, val status: Int, message: String,
    val current: ReadingNoteItem? = null, val retryAfterSeconds: Int? = null,
) : RuntimeException(message)
internal fun invalidNote(): Nothing = throw ReadingNoteFailure("READING_NOTE_INVALID", 400, "Invalid reading note request.")

/** Reject unpaired surrogates instead of persisting text that another client cannot represent. */
internal fun validNoteUnicode(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (Character.isHighSurrogate(char)) {
            if (index >= text.length || !Character.isLowSurrogate(text[index++])) return false
        } else if (Character.isLowSurrogate(char)) return false
    }
    return true
}
