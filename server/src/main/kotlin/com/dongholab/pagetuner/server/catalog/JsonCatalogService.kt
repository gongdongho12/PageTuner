package com.dongholab.pagetuner.server.catalog

import com.dongholab.pagetuner.server.novel.PublicHttpsContent
import com.dongholab.pagetuner.server.novel.PublicHttpsNovelHttpClient
import com.dongholab.pagetuner.server.novel.PublicNovelTargets
import com.dongholab.pagetuner.source.catalog.JsonCatalogDocument
import com.dongholab.pagetuner.source.catalog.PageTurnerJsonCatalogParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class InvalidJsonCatalog : IOException("The remote catalog does not match pagetuner.catalog.v0.")

class JsonCatalogService(private val fetch: suspend (String, Int) -> PublicHttpsContent) {
    suspend fun catalog(url: String): JsonCatalogDocument {
        PublicNovelTargets.url(url)
        val response = fetch(url, PageTurnerJsonCatalogParser.MaxBytes)
        PublicNovelTargets.url(response.url)
        if (response.bytes.size > PageTurnerJsonCatalogParser.MaxBytes) throw InvalidJsonCatalog()
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.bytes)).toString()
            PageTurnerJsonCatalogParser.parse(text, response.url)
        } catch (error: IllegalArgumentException) { throw InvalidJsonCatalog() }
        catch (error: java.nio.charset.CharacterCodingException) { throw InvalidJsonCatalog() }
    }

    suspend fun file(url: String): ByteArray {
        PublicNovelTargets.url(url)
        val response = fetch(url, PublicHttpsNovelHttpClient.MAX_FILE_BYTES)
        PublicNovelTargets.url(response.url)
        if (response.bytes.isEmpty() || response.bytes.size > PublicHttpsNovelHttpClient.MAX_FILE_BYTES) {
            throw IOException("The catalog file is empty or exceeds 32 MB.")
        }
        return response.bytes
    }
}
