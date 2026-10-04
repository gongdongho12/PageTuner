package com.dongholab.pagetuner.server.catalog

import com.dongholab.pagetuner.server.ServerSecurity
import com.dongholab.pagetuner.server.novel.NovelErrors
import com.dongholab.pagetuner.server.novel.PublicHttpsContent
import com.dongholab.pagetuner.server.translation.TranslationErrors
import java.io.IOException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(controllers = [JsonCatalogController::class], properties = [
    "spring.security.user.name=reader", "spring.security.user.password=test-password", "spring.mvc.problemdetails.enabled=true",
])
@Import(ServerSecurity::class, TranslationErrors::class, NovelErrors::class, JsonCatalogControllerMvcTest.Config::class)
class JsonCatalogControllerMvcTest {
    @Autowired lateinit var mvc: MockMvc
    private fun authenticated(path: String, url: String) = get(path).param("url", url).with(httpBasic("reader", "test-password"))

    @Test fun `both remote read APIs require authentication`() {
        listOf("/api/v1/catalogs/json", "/api/v1/catalog-files").forEach { path ->
            mvc.perform(get(path).param("url", "https://example.com/catalog.json")).andExpect(status().isUnauthorized)
        }
    }

    @Test fun `catalog read needs no CSRF and resolves relative entries from final response URL`() {
        mvc.perform(authenticated("/api/v1/catalogs/json", "https://example.com/catalog.json"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.catalogUrl").value("https://cdn.example.com/books/catalog.json"))
            .andExpect(jsonPath("$.items[0].href").value("https://cdn.example.com/books/text.txt"))
            .andExpect(jsonPath("$.items[0].format").value("txt"))
    }

    @Test fun `file read is a download with bounded bytes and no cache`() {
        mvc.perform(authenticated("/api/v1/catalog-files", "https://example.com/file.txt"))
            .andExpect(status().isOk).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(content().bytes("Original text".toByteArray()))
    }

    @Test fun `unsafe URL and invalid upstream catalog are visible errors`() {
        mvc.perform(authenticated("/api/v1/catalog-files", "https://127.0.0.1/secret"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.status").value(400))
        mvc.perform(authenticated("/api/v1/catalogs/json", "https://example.com/invalid"))
            .andExpect(status().isBadGateway).andExpect(jsonPath("$.code").value("catalog_invalid"))
        mvc.perform(authenticated("/api/v1/catalogs/json", "https://example.com/unavailable"))
            .andExpect(status().isBadGateway).andExpect(jsonPath("$.code").value("source_unavailable"))
    }

    @TestConfiguration class Config {
        @Bean fun jsonCatalogService() = JsonCatalogService { url, _ ->
            when {
                url.endsWith("invalid") -> PublicHttpsContent(url, "bad JSON".toByteArray(), "application/json")
                url.endsWith("unavailable") -> throw IOException("Unavailable test upstream")
                url.endsWith("file.txt") -> PublicHttpsContent(url, "Original text".toByteArray(), "text/plain")
                else -> PublicHttpsContent("https://cdn.example.com/books/catalog.json",
                    """{"id":"test","items":[{"id":"text","title":"Text","format":"txt","href":"text.txt"}]}""".toByteArray(), "application/json")
            }
        }
    }
}
