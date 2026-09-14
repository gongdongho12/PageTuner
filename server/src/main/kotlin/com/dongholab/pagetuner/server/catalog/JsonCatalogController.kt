package com.dongholab.pagetuner.server.catalog

import com.dongholab.pagetuner.server.novel.PublicHttpsNovelHttpClient
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Configuration
class JsonCatalogConfiguration {
    @Bean fun jsonCatalogService(client: PublicHttpsNovelHttpClient) = JsonCatalogService(client::fetchContent)
}

@RestController
class JsonCatalogController(private val service: JsonCatalogService) {
    @GetMapping("/api/v1/catalogs/json")
    fun catalog(@RequestParam url: String) = runBlocking {
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.catalog(url))
    }

    @GetMapping("/api/v1/catalog-files", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun file(@RequestParam url: String) = runBlocking {
        val bytes = service.file(url)
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(bytes.size.toLong()).header("Content-Disposition", "attachment; filename=\"catalog-file\"")
            .header("X-Content-Type-Options", "nosniff").body(bytes)
    }
}
