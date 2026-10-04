package com.dongholab.pagetuner.server.novel

import com.dongholab.pagetuner.server.ServerSecurity
import com.dongholab.pagetuner.server.translation.TranslationErrors
import com.dongholab.pagetuner.source.service.NovelHttpTransport
import com.dongholab.pagetuner.source.service.NovelSourceService
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

@WebMvcTest(controllers = [NovelController::class], properties = [
    "spring.security.user.name=reader", "spring.security.user.password=test-password",
    "spring.mvc.problemdetails.enabled=true",
])
@Import(ServerSecurity::class, TranslationErrors::class, NovelErrors::class, NovelControllerMvcTest.SourceConfig::class)
class NovelControllerMvcTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `all source APIs require authentication`() {
        listOf("/api/v1/novel-sources", "/api/v1/novels/catalog", "/api/v1/novels/detail").forEach {
            mvc.perform(get(it)).andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `source discovery exposes all three existing adapters`() {
        mvc.perform(get("/api/v1/novel-sources").with(httpBasic("reader", "test-password")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].id").value("wtr-lab"))
            .andExpect(jsonPath("$.items[1].id").value("novelbuddy"))
            .andExpect(jsonPath("$.items[2].requiresUrl").value(true))
    }

    @Test
    fun `invalid paging and unsupported source return problem details before fetching`() {
        mvc.perform(get("/api/v1/novels/catalog").param("sourceId", "wtr-lab").param("page", "0")
            .with(httpBasic("reader", "test-password")))
            .andExpect(status().isBadRequest).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        mvc.perform(get("/api/v1/novels/detail").param("url", "https://novelbuddy.me/sample").param("size", "101")
            .with(httpBasic("reader", "test-password")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.status").value(400))
    }

    @Test
    fun `upstream failure is visible and never becomes an empty successful catalog`() {
        mvc.perform(get("/api/v1/novels/catalog").param("sourceId", "wtr-lab")
            .with(httpBasic("reader", "test-password")))
            .andExpect(status().isBadGateway)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("source_unavailable"))
    }

    @TestConfiguration
    class SourceConfig {
        @Bean fun novelSourceService() = NovelSourceService(object : NovelHttpTransport {
            override suspend fun fetchText(url: String): String = throw IOException("Provider unreachable")
            override suspend fun postJson(url: String, body: String, referer: String): String = error("Unexpected POST")
        })
    }
}
