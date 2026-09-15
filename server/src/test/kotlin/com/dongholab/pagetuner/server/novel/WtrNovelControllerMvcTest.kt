package com.dongholab.pagetuner.server.novel

import com.dongholab.pagetuner.server.ServerSecurity
import com.dongholab.pagetuner.source.service.NovelHttpTransport
import com.dongholab.pagetuner.source.service.NovelSourceService
import java.net.URI
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(controllers = [NovelController::class], properties = [
    "spring.security.user.name=reader", "spring.security.user.password=test-password",
])
@Import(ServerSecurity::class, WtrNovelControllerMvcTest.SourceConfig::class)
class WtrNovelControllerMvcTest {
    @Autowired lateinit var mvc: MockMvc

    @Test fun `real WTR service and adapter normalize metadata before API serialization`() {
        mvc.perform(get("/api/v1/novels/catalog").param("sourceId", "wtr-lab")
            .with(httpBasic("reader", "test-password")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sourceId").value("wtr-lab"))
            .andExpect(jsonPath("$.items[0].title").value("Soul Land Adventures"))
            .andExpect(jsonPath("$.items[0].description").value("A story in Soul Land."))
            .andExpect(jsonPath("$.items[0].url").value(NOVEL))
            .andExpect(jsonPath("$.items[0].bookId").value(NOVEL))
            .andExpect(jsonPath("$.items[0].authors[0]").value("Fixture Author"))

        mvc.perform(get("/api/v1/novels/detail").param("url", NOVEL)
            .with(httpBasic("reader", "test-password")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Soul Land Adventures"))
            .andExpect(jsonPath("$.summary").value("A story in Soul Land."))
            .andExpect(jsonPath("$.url").value(NOVEL))
            .andExpect(jsonPath("$.bookId").value(NOVEL))
            .andExpect(jsonPath("$.chapters[0].title").value("Chapter One"))
            .andExpect(jsonPath("$.chapters[0].url").value("$NOVEL/chapter-1"))
            .andExpect(jsonPath("$.totalItems").value(1))
    }

    @TestConfiguration class SourceConfig {
        @Bean fun novelSourceService() = NovelSourceService(object : NovelHttpTransport {
            override suspend fun fetchText(url: String): String = when (URI(url).path) {
                "/en/novel-list" -> nextData("""{"series":[$SERIES]}""")
                "/en/novel/42/provider-original-slug" -> nextData("""{"serie":{"serie_data":$SERIES,"chapters":[{"order":1,"title":"Chapter One"}]}}""")
                else -> error("Unexpected fixture request: $url")
            }
            override suspend fun postJson(url: String, body: String, referer: String): String = error("Catalog and detail never request chapter text")
        })
    }

    private companion object {
        const val NOVEL = "https://wtr-lab.com/en/novel/42/provider-original-slug"
        const val SERIES = """{"raw_id":42,"slug":"provider-original-slug","chapter_count":1,"data":{"title":"%{Soul Land|RG91bHVvIERhbHU} Adventures","description":"A story in %{Soul Land|RG91bHVvIERhbHU}.","author":"Fixture Author"}}"""
        fun nextData(pageProps: String) = """<html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":$pageProps}}</script></body></html>"""
    }
}
