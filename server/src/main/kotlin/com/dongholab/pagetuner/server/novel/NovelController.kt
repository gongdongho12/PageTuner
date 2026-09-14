package com.dongholab.pagetuner.server.novel

import com.dongholab.pagetuner.source.service.NovelSourceService
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Configuration
class NovelSourceConfiguration {
    @Bean
    fun novelSourceService(): NovelSourceService = NovelSourceService(PublicHttpsNovelHttpClient())
}

@RestController
class NovelController(private val service: NovelSourceService) {
    @GetMapping("/api/v1/novel-sources")
    fun sources() = service.sources()

    @GetMapping("/api/v1/novels/catalog")
    fun catalog(
        @RequestParam sourceId: String,
        @RequestParam(required = false) url: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(required = false) genre: String?,
        @RequestParam(required = false) orderBy: String?,
        @RequestParam(required = false) order: String?,
        @RequestParam(required = false) status: String?,
    ) = runBlocking { service.catalog(sourceId, url, query, page, genre, orderBy, order, status) }

    @GetMapping("/api/v1/novels/detail")
    fun detail(
        @RequestParam url: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = runBlocking { service.detail(url, page, size) }
}
