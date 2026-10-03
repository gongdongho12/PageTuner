package com.dongholab.pagetuner.core.model.library

import org.junit.Test
import org.junit.Assert.assertThrows

class SourceBookFavoriteTest {
    private val book = SourceBookFavoriteMetadata("책 제목", listOf("Author"), "en", "https://example.com/novel/a%20b?x=1#chapter")

    @Test fun `original IDs and metadata remain exact including case slashes commas and astral characters`() {
        SourceBookFavoriteValidation.validateIdentity("provider/A", "書籍/Ab,c😀")
        SourceBookFavoriteValidation.validateMetadata(book)
        SourceBookFavoriteValidation.validateMetadata(book.copy(authors = emptyList(), url = "http://127.0.0.1:8080/book"))
    }

    @Test fun `reject malformed unicode controls and boundary ECMAScript whitespace`() {
        listOf("", "\u00a0book", "book\ufeff", "bad\u0000id", "bad\ud800", "b".repeat(501)).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { SourceBookFavoriteValidation.validateIdentity("site", id) }
        }
        listOf(book.copy(title = " title"), book.copy(authors = List(21) { "A" }), book.copy(authors = listOf("")),
            book.copy(language = "en\n")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { SourceBookFavoriteValidation.validateMetadata(invalid) }
        }
    }

    @Test fun `reject executable ambiguous and credential bearing URLs`() {
        listOf("javascript:alert(1)", "//example.com/a", "https://user:pass@example.com/a", "https://example.com\\@evil.test",
            "https://example.com/ space", "https://例え.test/a", "https://example.com:99999/a", "https://example.com/\n",
            "https://[fe80::1%25eth0]/a", "https://%65xample.com/a").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { SourceBookFavoriteValidation.validateMetadata(book.copy(url = url)) }
        }
    }

    @Test fun `accept only hosts that both URI and browser URL parse without numeric repairs`() {
        listOf("http://localhost:8080/a", "https://EXAMPLE.COM./a", "https://127.0.0.1/a", "https://255.255.255.255/a",
            "https://[::1]/a", "https://[2001:db8::1]:443/a",
            "https://${List(3) { "a".repeat(63) }.joinToString(".")}.${"a".repeat(61)}/a").forEach { url ->
            SourceBookFavoriteValidation.validateMetadata(book.copy(url = url))
        }
        listOf("https://192.168.001.008/a", "https://192.168.001.007/a", "https://4294967296/a", "https://127/a",
            "https://0x100000000/a", "https://0x7f000001/a", "https://example.0x7f/a", "https://1.2.3.256/a",
            "https://127.0.0.1./a", "https://[::ffff:192.168.1.1]/a", "https://${"a".repeat(64)}.test/a",
            "https://${List(4) { "a".repeat(63) }.joinToString(".")}/a").forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { SourceBookFavoriteValidation.validateMetadata(book.copy(url = url)) }
        }
    }
}
