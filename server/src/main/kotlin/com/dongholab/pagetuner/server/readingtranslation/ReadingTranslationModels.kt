package com.dongholab.pagetuner.server.readingtranslation

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.server.workflow.StoredChapter
import com.dongholab.pagetuner.server.workflow.WorkflowGlossaryEntry
import java.time.Instant
import java.util.UUID

data class ReadingFragment(val paragraphId: String, val start: Int, val end: Int) {
    val key: String get() = "${paragraphId.length}:$paragraphId:$start:$end"
    val segmentId: String get() = "reading-${StableContentHash.sha256(key).take(32)}"
}

/** A disposable reading request. Never serialize this object into task state or logs. */
class ReadingTranslationRequest(
    val requestId: UUID, val chapterRecordId: UUID, val sourceRevision: String,
    val fragments: List<ReadingFragment>, val providerKind: String = "GOOGLE_WEB_TRANSLATE_HTML",
    val targetLanguage: String = "ko", val sourceLanguage: String? = null,
    val endpoint: String? = null, val model: String? = null, val apiKey: String? = null,
    val glossary: List<WorkflowGlossaryEntry> = emptyList(), val readingWordsPerMinute: Int = 210,
    val paceMode: String = "READING",
) {
    override fun toString() = "ReadingTranslationRequest(requestId=$requestId, credentials=REDACTED)"
    fun snapshot() = ReadingTranslationRequest(requestId, chapterRecordId, sourceRevision, fragments.map { it.copy() },
        providerKind, targetLanguage, sourceLanguage, endpoint, model, apiKey, glossary.map { it.copy() }, readingWordsPerMinute, paceMode)
    val sourceHash: String get() = StableContentHash.sha256(listOf(chapterRecordId.toString(), sourceRevision,
        fragments.joinToString("\n") { it.key }).joinToString("\n"))

    fun validate(chapter: StoredChapter): List<String> {
        require(chapter.recordId == chapterRecordId && sourceRevision == chapter.sourceRevision && chapter.content().sourceRevision == sourceRevision) { "Reading source revision changed." }
        require(fragments.size in 1..64 && fragments.map { it.key }.distinct().size == fragments.size) { "Select 1 to 64 distinct reading fragments." }
        require(readingWordsPerMinute in 120..420 && paceMode in setOf("READING", "FAST", "OFFLINE_PREFETCH")) { "Invalid reading speed or translation pace." }
        require((apiKey?.length ?: 0) <= 4096 && apiKey.orEmpty().none { it == '\r' || it == '\n' }) { "Invalid provider credential." }
        require((endpoint?.length ?: 0) <= 2_000 && (model?.length ?: 0) <= 200) { "Translation endpoint or model exceeds its limit." }
        val paragraphs = chapter.paragraphs.associateBy { it.paragraphId }
        var totalCharacters = 0L
        val texts = fragments.map { fragment ->
            val text = paragraphs[fragment.paragraphId]?.text ?: throw IllegalArgumentException("Unknown reading paragraph.")
            require(fragment.start >= 0 && fragment.end > fragment.start && fragment.end <= text.length) { "Reading range is outside the source paragraph." }
            // Bound allocation before copying ranges, including overlapping parts of a large paragraph.
            totalCharacters += fragment.end.toLong() - fragment.start
            require(totalCharacters <= 24_000) { "Reading request exceeds 24000 source characters." }
            for (offset in listOf(fragment.start, fragment.end)) require(offset == 0 || offset == text.length ||
                !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())) { "Reading range splits a surrogate pair." }
            text.substring(fragment.start, fragment.end).also { require(it.isNotBlank()) { "Reading fragments must contain text." } }
        }
        return texts
    }
}

data class ReadingTranslationItem(val paragraphId: String, val start: Int, val end: Int, val text: String)
data class ReadingTranslationView(
    val requestId: UUID, val status: String, val chapterRecordId: UUID, val sourceRevision: String, val sourceHash: String,
    val providerKind: String, val targetLanguage: String, val completedFragments: Int, val totalFragments: Int,
    val items: List<ReadingTranslationItem>, val errorCode: String?, val updatedAt: Instant,
    /** Literal scope makes partial reading output distinguishable from an immutable full-chapter artifact. */
    val scope: String = "READING_PREVIEW",
)
