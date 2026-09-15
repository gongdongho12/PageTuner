package com.dongholab.pagetuner.source.wtr

import com.dongholab.pagetuner.source.webnovel.WtrLabSiteAdapter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WtrLabCatalogDisplayTest {
    @Test fun catalogAndAdapterExposeDisplayLabelsWithoutChangingRoutingMetadata() {
        val title = "%{Soul Land|RG91bHVvIERhbHU}: %{哪吒 🌏|opaque-payload}"
        val description = "Visit %{Soul Land|RG91bHVvIERhbHU}.\nKeep 50% and ordinary {braces}."
        val item = item(title, description)
        val html = html(JSONObject().put("series", JSONArray().put(item)))
        val parsed = WtrLabDomScraper.parseNovelListResponse(html, BASE).novels.single()
        assertEquals("Soul Land: 哪吒 🌏", parsed.title)
        assertEquals("Visit Soul Land.\nKeep 50% and ordinary {braces}.", parsed.description)
        assertEquals(42L, parsed.novelId)
        assertEquals("provider-original-slug", parsed.slug)
        assertEquals("Unchanged Author", parsed.author)
        assertEquals("https://img.wtr-lab.com/cover.webp", parsed.coverUrl)

        val adapted = WtrLabSiteAdapter().parseCatalogPage(html, BASE).items.single()
        assertEquals(parsed.title, adapted.title)
        assertEquals(parsed.description, adapted.description)
        assertEquals(NOVEL, adapted.url)
        assertEquals("novel_42", adapted.id)
    }

    @Test fun detailUsesTheSameDisplayLabelsAndDoesNotRewriteChapterTitlesOrReaderBody() {
        val marker = "%{Soul Land|RG91bHVvIERhbHU}"
        val page = JSONObject().put("serie", JSONObject()
            .put("serie_data", item("$marker Adventures", "A story in $marker."))
            .put("chapters", JSONArray().put(JSONObject().put("order", 1).put("title", marker))))
        val detail = WtrLabDomScraper.parseNovelDetailResponse(42, html(page), NOVEL)
        assertEquals("Soul Land Adventures", detail.title)
        assertEquals("A story in Soul Land.", detail.summary)
        assertEquals("provider-original-slug", detail.slug)
        val chapters = WtrLabDomScraper.parseChapterListResponse(42, html(page), NOVEL)
        assertEquals(marker, chapters.chapters.single().title)
        assertEquals("/en/novel/42/provider-original-slug/chapter-1", chapters.chapters.single().urlPath)

        val reader = JSONObject().put("success", true)
            .put("chapter", JSONObject().put("raw_id", 42).put("order", 1).put("title", marker))
            .put("data", JSONObject().put("title", marker).put("body", JSONArray().put("Original $marker text.")))
        val content = WtrLabDomScraper.parseReaderChapterResponse(42, 1, reader.toString())
        assertEquals(marker, content.titleOriginal)
        assertEquals(marker, content.titleTranslated)
        assertEquals(listOf("Original $marker text."), content.paragraphs)
    }

    @Test fun incompleteAmbiguousAndNestedMarkersRemainLiteral() {
        val unchanged = listOf(
            "Plain title with 50% {braces}", "%{unfinished|payload", "%{|payload}", "%{label|}",
            "%{ |payload}", "%{label|payload|extra}", "%{label\nline|payload}",
            "%{outer %{inner|payload}|other}", "%{outer {braces}|payload}",
        )
        unchanged.forEach { text ->
            val parsed = WtrLabDomScraper.parseNovelListResponse(
                html(JSONObject().put("series", JSONArray().put(item(text, text)))), BASE,
            ).novels.single()
            assertEquals(text, parsed.title)
            assertEquals(text, parsed.description)
        }
    }

    @Test fun labelsRemainPlainTextAndOpaquePayloadsAreNeverDecoded() {
        val title = "%{<b>Visible</b>|not-base64} &amp; %{Second|https://example.invalid/private}"
        val parsed = WtrLabDomScraper.parseNovelListResponse(
            html(JSONObject().put("series", JSONArray().put(item(title, title)))), BASE,
        ).novels.single()
        assertEquals("<b>Visible</b> &amp; Second", parsed.title)
        assertEquals(parsed.title, parsed.description)
    }

    private fun item(title: String, description: String) = JSONObject()
        .put("raw_id", 42).put("slug", "provider-original-slug").put("chapter_count", 1)
        .put("data", JSONObject().put("title", title).put("description", description)
            .put("author", "Unchanged Author").put("image", "https://img.wtr-lab.com/cover.webp"))

    private fun html(pageProps: JSONObject): String =
        """<html><body><script id="__NEXT_DATA__" type="application/json">${JSONObject().put("props", JSONObject().put("pageProps", pageProps))}</script></body></html>"""

    private companion object {
        const val BASE = "https://wtr-lab.com/en/novel-list"
        const val NOVEL = "https://wtr-lab.com/en/novel/42/provider-original-slug"
    }
}
