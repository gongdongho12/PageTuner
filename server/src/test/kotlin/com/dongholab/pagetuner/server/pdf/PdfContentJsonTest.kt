package com.dongholab.pagetuner.server.pdf

import com.dongholab.pagetuner.core.backup.exchange.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Pure codec tests: no Spring context, server, database, or account writes. */
class PdfContentJsonTest {
    private val json = jacksonObjectMapper()
    private val codec = PdfContentJson(json)
    private val uploadId = "a2e5b2f6-0204-46c0-9f0a-0b50a8625f46"

    private fun fixture(): PdfContentUpload {
        val vector = json.readTree(requireNotNull(javaClass.getResourceAsStream("/pdf-content-v1.json")).use { it.readBytes() })
        return codec.upload(json.writeValueAsBytes(vector["upload"]))
    }

    @Test fun `oversized numeric array fails before DTO tree allocation`() {
        val raw = "{\"payloads\":" + List(100_000) { "1" }.joinToString(prefix = "[", postfix = "]", separator = ",") + "}"
        val error = assertThrows(IllegalArgumentException::class.java) { codec.upload(raw.toByteArray()) }
        assertEquals("PDF JSON array exceeds the entry limit.", error.message)
    }

    @Test fun `nested legal sized arrays cannot bypass the total value budget`() {
        val child = List(4096) { "1" }.joinToString(prefix = "[", postfix = "]", separator = ",")
        val raw = "{\"payloads\":" + List(9) { child }.joinToString(prefix = "[", postfix = "]", separator = ",") + "}"
        val error = assertThrows(IllegalArgumentException::class.java) { codec.upload(raw.toByteArray()) }
        assertEquals("PDF JSON exceeds the total value limit.", error.message)
        val objectValues = (0 until 32_768).joinToString(prefix = "{", postfix = "}") { "\"key$it\":null" }
        assertEquals("PDF JSON exceeds the total value limit.",
            assertThrows(IllegalArgumentException::class.java) { codec.tree(objectValues.toByteArray()) }.message)
    }

    @Test fun `maximum legal counts bytes and metadata still round trip upload and verification`() {
        val original = ByteArray(PdfContentValidation.MAX_PAYLOAD_BYTES - (PdfContentValidation.MAX_PAYLOADS - 1))
        "%PDF-".toByteArray().copyInto(original)
        val pdf = ExchangeAsset(original, "application/pdf")
        val images = List(PdfContentValidation.MAX_PAYLOADS - 1) { ExchangeAsset(byteArrayOf((it + 1).toByte()), "image/png") }
        val paragraphs = List(PdfContentValidation.MAX_PARAGRAPHS) { ExchangeParagraph("p$it", "") }
        val refs = listOf(ExchangeAssetReference(pdf.path, "pdf")) + List(PdfContentValidation.MAX_REFERENCES - 1) {
            ExchangeAssetReference(images[it % images.size].path, "image", "p0", "\u0001".repeat(1850))
        }
        val metadataSize = 2 + paragraphs.sumOf { it.paragraphId.length } + refs.sumOf { (it.paragraphId?.length ?: 0) + (it.alt?.length ?: 0) }
        val content = PdfContentDocument(language = "en",
            paragraphs = paragraphs.mapIndexed { index, p -> if (index == 0) p.copy(text = "\u0001".repeat(PdfContentValidation.MAX_METADATA_CHARACTERS - metadataSize)) else p },
            assets = refs, payloads = (listOf(pdf) + images).map { PdfContentPayload(it.path, it.mimeType, PdfContentBase64.encode(it.bytes)) })
        val input = PdfContentUpload(uploadId, content)
        val checked = PdfContentValidation.validate(input)
        val wire = codec.bytes(input)
        assertTrue(wire.size <= PdfContentValidation.MAX_REQUEST_BYTES)
        assertEquals(input, codec.upload(wire))
        assertEquals(checked.proof, PdfContentValidation.validate(codec.upload(wire)).proof)
        val verify = codec.bytes(mapOf("proof" to checked.proof))
        assertTrue(verify.size > 128 * 1024 && verify.size <= PdfContentValidation.MAX_VERIFY_BYTES)
        assertEquals(checked.proof, codec.verification(verify))
    }

    @Test fun `shared fixture and strict duplicate trailing numeric unicode checks are preserved`() {
        val input = fixture()
        val original = String(codec.bytes(input), Charsets.UTF_8)
        assertEquals(input, codec.upload(original.toByteArray()))
        val invalid = listOf(
            original + "{}",
            "{\"uploadId\":\"$uploadId\"," + original.drop(1),
            "{\"upload\\u0049d\":\"$uploadId\"," + original.drop(1),
            original.dropLast(1) + ",\"unknown\":true}",
            original.replace("\"version\":1", "\"version\":1.0"),
            original.replace("\"version\":1", "\"version\":1e0"),
            original.replace("\"version\":1", "\"version\":\"1\""),
            original.replace("\"version\":1", "\"version\":9007199254740992"),
            original.replace("\"language\":\"ko-KR\"", "\"language\":\"\\ud800\""),
        )
        invalid.forEach { raw ->
            assertNotEquals(original, raw)
            assertThrows(Exception::class.java) { PdfContentValidation.validate(codec.upload(raw.toByteArray())) }
        }
        val invalidUtf8 = "{\"language\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        assertThrows(Exception::class.java) { codec.tree(invalidUtf8) }
        val escaped = original.replace("😀", "\\ud83d\\ude00")
        assertEquals(input, codec.upload(escaped.toByteArray()))
    }
}
