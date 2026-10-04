package com.dongholab.pagetuner.server.readingtranslation

import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.server.workflow.JobConfiguration
import com.dongholab.pagetuner.server.workflow.SourceChapterStore
import com.dongholab.pagetuner.server.workflow.StoredChapter
import com.dongholab.pagetuner.server.workflow.glossary
import com.dongholab.pagetuner.server.workflow.settings
import com.dongholab.pagetuner.translation.ChapterTranslationEngine
import com.dongholab.pagetuner.translation.TranslationPaceMode
import java.util.UUID
import org.springframework.stereotype.Component

fun interface ReadingSource { fun get(username: String, id: UUID): StoredChapter }
@Component class StoredReadingSource(private val chapters: SourceChapterStore) : ReadingSource {
    override fun get(username: String, id: UUID) = chapters.get(username, id)
}
fun interface ReadingFragmentTranslator {
    suspend fun translate(chapter: StoredChapter, request: ReadingTranslationRequest, configuration: JobConfiguration, secret: String,
        progress: suspend (Int) -> Unit): List<ReadingTranslationItem>
}

@Component class RuntimeReadingFragmentTranslator : ReadingFragmentTranslator {
    override suspend fun translate(chapter: StoredChapter, request: ReadingTranslationRequest, configuration: JobConfiguration,
        secret: String, progress: suspend (Int) -> Unit) = execute(chapter, request, configuration, secret, progress, ChapterTranslationEngine())

    internal suspend fun execute(chapter: StoredChapter, request: ReadingTranslationRequest, configuration: JobConfiguration,
        secret: String, progress: suspend (Int) -> Unit, engine: ChapterTranslationEngine): List<ReadingTranslationItem> {
        val texts = request.validate(chapter)
        val fragments = ChapterContent(chapter.content().identity, chapter.chapterTitle, configuration.sourceLanguage,
            request.fragments.mapIndexed { index, fragment -> ContentParagraph(fragment.segmentId, index, texts[index]) })
        val result = engine.translate(fragments,
            configuration.settings(secret).copy(readingWordsPerMinute = request.readingWordsPerMinute, paceMode = TranslationPaceMode.valueOf(request.paceMode)),
            configuration.glossary(chapter.content().identity.book.canonicalId), onProgress = { complete, _ -> progress(complete) })
        check(result.map { it.paragraphId } == request.fragments.map { it.segmentId }) { "Reading translation identities do not match." }
        return request.fragments.zip(result).map { (fragment, translated) -> ReadingTranslationItem(fragment.paragraphId, fragment.start, fragment.end, translated.text) }
    }
}
