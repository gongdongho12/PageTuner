package com.dongholab.pagetuner.source.catalog

import org.junit.Assert.*
import org.junit.Test

class PageTurnerJsonCatalogTest {
    @Test fun `normalizes original app formats and resolves all URLs from final catalog location`() {
        val catalog = PageTurnerJsonCatalogParser.parse("""{
          "id":"library","links":[{"rel":"next","href":"?page=2"}],
          "items":[{"id":"a","title":"Book","authors":["Author"],"format":"text","href":"books/a.txt",
          "cover":"/a.png","size":3,"translationHints":{"sourceLanguage":"en","targetLanguages":["ko"]}}]
        }""", "https://example.com/library/catalog.json")
        assertEquals(PageTurnerJsonCatalogParser.Version, catalog.version)
        assertEquals("library", catalog.title)
        assertEquals("https://example.com/library/catalog.json?page=2", catalog.links.single().href)
        assertEquals("txt", catalog.items.single().format)
        assertEquals("https://example.com/library/books/a.txt", catalog.items.single().href)
        assertEquals("https://example.com/a.png", catalog.items.single().cover)
        assertEquals(3L, catalog.items.single().size)
        assertEquals(listOf("ko"), catalog.items.single().translationHints.targetLanguages)
    }

    @Test fun `rejects malformed rows duplicate IDs unsafe size and unsupported format rather than dropping them`() {
        val item = """{"id":"a","title":"A","href":"a.txt","format":"txt"}"""
        listOf("null", "42", "{}", "$item,$item", item.replace("\"txt\"", "\"exe\""),
            item.dropLast(1) + ",\"size\":-1}", item.dropLast(1) + ",\"size\":1.5}",
            item.dropLast(1) + ",\"authors\":[1]}").forEach { rows ->
            assertThrows(IllegalArgumentException::class.java) {
                PageTurnerJsonCatalogParser.parse("""{"id":"test","items":[$rows]}""", "https://example.com/catalog.json")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { PageTurnerJsonCatalogParser.parse("""{"id":"a","items":"wrong"}""", "https://example.com/") }
    }
}
