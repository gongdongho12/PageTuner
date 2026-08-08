package com.dongholab.pagetuner.source.webnovel

import java.net.URI

/** Produces a stable work URL shared by a novel detail page and its chapters. */
object WebNovelSeriesKeys {
    fun fromUrl(url: String): String {
        return runCatching {
            val uri = URI(url.trim())
            val path = uri.path.orEmpty()
                .replace(Regex("/(?:chapter|ch)[-_/]?\\d+/?$", RegexOption.IGNORE_CASE), "")
                .trimEnd('/')
            URI(uri.scheme, uri.authority, path.ifBlank { "/" }, null, null).normalize().toString()
        }.getOrDefault(
            url.substringBefore('#').substringBefore('?').trimEnd('/'),
        )
    }
}
