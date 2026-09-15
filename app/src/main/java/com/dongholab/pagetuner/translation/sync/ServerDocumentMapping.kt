package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.translation.TranslationRuntimeIdentity
import com.dongholab.pagetuner.translation.TranslationSettings
import com.dongholab.pagetuner.translation.glossary.BookGlossary

object ServerDocumentMapping {
    fun create(document: ReaderDocument, settings: TranslationSettings, glossary: BookGlossary?, cacheProviderId: String): TranslationCacheChapterMapping {
        val segments = document.pages.flatMap { it.segments }
        require(segments.isNotEmpty() && segments.all { it.text.isNotBlank() }) {
            "빈 문단이 있거나 본문이 없는 문서는 서버에 저장할 수 없습니다."
        }
        val runtime = TranslationRuntimeIdentity.describe(settings, glossary)
        val aliasPrompt = if (cacheProviderId.contains(":character-alias-v1")) ":character-alias-v1" else ""
        // Existing cache records contain no per-request history of LLM-discovered aliases.
        // Do not label today's glossary as a verified snapshot of those past requests.
        val glossaryRevision = if (aliasPrompt.isNotEmpty()) "app-dynamic-alias-unrecorded-v1" else runtime.glossaryRevision
        val source = ChapterContent(
            ChapterIdentity(BookIdentity("pageturner-app", document.id), "document"), document.title,
            settings.normalizedSourceLanguage,
            segments.mapIndexed { index, segment -> ContentParagraph(segment.id, index, segment.text) },
        )
        return TranslationCacheChapterMapping(document, source, segments.associate { it.id to it.id },
            TranslationCacheVariant(settings.normalizedSourceLanguage, settings.normalizedTargetLanguage,
                cacheProviderId, cacheProviderId, runtime.modelId, runtime.promptRevision + aliasPrompt, glossaryRevision))
    }
}
