package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.document.DocumentIds
import java.net.URI

/** Produces a stable work URL shared by a novel detail page and its chapters. */
object WebNovelSeriesKeys {
    fun fromUrl(url: String): String {
        return runCatching {
            val uri = URI(url.trim())
            val path = uri.path.orEmpty()
                .replace(
                    Regex("/(?:chapter|ch)[-_/]?\\d+(?:[-_/][^/?#]+)*/?$", RegexOption.IGNORE_CASE),
                    "",
                )
                .trimEnd('/')
            URI(uri.scheme, uri.authority, path.ifBlank { "/" }, null, null).normalize().toString()
        }.getOrDefault(
            url.substringBefore('#').substringBefore('?').trimEnd('/'),
        )
    }
}

/** Produces a chapter identity that cannot collide with the same chapter number in another work. */
object WebNovelChapterKeys {
    fun fromUrl(url: String, chapterNumber: Int): String {
        val canonicalUrl = runCatching {
            val uri = URI(url.trim())
            URI(uri.scheme, uri.authority, uri.path.orEmpty(), null, null).normalize().toString()
        }.getOrDefault(url.substringBefore('#').substringBefore('?').trimEnd('/'))
        val digest = DocumentIds.sha256("$canonicalUrl\n$chapterNumber").take(24)
        return "chapter_$digest"
    }
}

object WebNovelChapterNumbers {
    fun fromUrl(url: String): Int? =
        Regex(
            "/(?:chapter|ch)[-_/]?(\\d+)(?:[-_/][^/?#]+)*(?:[/?#]|$)",
            RegexOption.IGNORE_CASE,
        )
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
}
