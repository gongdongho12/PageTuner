package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.backup.exchange.*
import java.io.File
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PdfContentSnapshotTest {
    private val pdf = ExchangeAsset("%PDF-1.4\nopaque storage sample\n".toByteArray(), "application/pdf")
    private val image = ExchangeAsset(byteArrayOf(1, 2, -1), "image/png")
    private fun payload(asset: ExchangeAsset) = PdfContentPayload(asset.path, asset.mimeType, Base64.getEncoder().encodeToString(asset.bytes))
    private fun document() = PdfContentDocument(language = "ko-KR", paragraphs = listOf(ExchangeParagraph(" p:😀 ", " 안녕\n😀 "), ExchangeParagraph("empty", "")),
        assets = listOf(ExchangeAssetReference(pdf.path, "pdf"), ExchangeAssetReference(image.path, "image", " p:😀 ", " 그림 "),
            ExchangeAssetReference(image.path, "image", "empty", "")), payloads = listOf(payload(pdf), payload(image)))
    private fun validate(content: PdfContentDocument = document()) = PdfContentValidation.validateContent(content)
    private fun reject(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun exactContentPreservesNullEmptyWhitespaceLanguageAndRepeatedReferenceOrder() {
        val content = document()
        val result = validate(content)
        assertEquals(content, result.content)
        assertEquals(3, result.proof.assets.size)
        assertEquals(null, result.proof.assets[0].alt)
        assertEquals("", result.proof.assets[2].alt)
        assertEquals(" p:😀 ", result.proof.assets[1].paragraphId)
        assertEquals(pdf.sha256, result.proof.originalFileSha256)
        assertEquals(pdf.bytes.size.toLong(), result.proof.originalFileByteLength)
        assertNotEquals(result.proof, validate(content.copy(language = "KO-KR")).proof)
        assertNotEquals(result.proof, validate(content.copy(assets = content.assets.reversed())).proof)
        assertNotEquals(result.requestFingerprint, validate(content.copy(assets = content.assets.reversed())).requestFingerprint)
        val reorderedPayloads = validate(content.copy(payloads = content.payloads.reversed()))
        assertEquals(result.proof, reorderedPayloads.proof)
        assertNotEquals(result.requestFingerprint, reorderedPayloads.requestFingerprint)
    }

    @Test fun uploadUuidIsCanonicalAndExcludedFromOrderedContentFingerprint() {
        val content = document()
        val a = PdfContentValidation.validate(PdfContentUpload("a2e5b2f6-0204-46c0-9f0a-0b50a8625f46", content))
        val b = PdfContentValidation.validate(PdfContentUpload("a2e5b2f6-0204-46c0-9f0a-0b50a8625f47", content))
        assertEquals(a.requestFingerprint, b.requestFingerprint)
        listOf("A2E5B2F6-0204-46C0-9F0A-0B50A8625F46", "1-1-1-1-1", "no", " a2e5b2f6-0204-46c0-9f0a-0b50a8625f46").forEach {
            reject { PdfContentValidation.validate(PdfContentUpload(it, content)) }
        }
    }

    @Test fun pureKotlinBase64MatchesRfc4648AndJdkIncludingAllByteValuesAndMaximumSize() {
        listOf("f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v", "foob" to "Zm9vYg==", "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy").forEach { (raw, encoded) ->
            assertEquals(encoded, PdfContentBase64.encode(raw.toByteArray()))
            assertArrayEquals(raw.toByteArray(), PdfContentBase64.decode(encoded))
        }
        listOf(1, 2, 3, 255, 256, 257, PdfContentValidation.MAX_PAYLOAD_BYTES).forEach { size ->
            val bytes = ByteArray(size) { it.toByte() }
            val encoded = PdfContentBase64.encode(bytes)
            assertEquals(Base64.getEncoder().encodeToString(bytes), encoded)
            assertEquals(size, PdfContentBase64.decodedLength(encoded))
            assertArrayEquals(bytes, PdfContentBase64.decode(encoded))
        }
        reject { PdfContentBase64.encode(ByteArray(0)) }
        reject { PdfContentBase64.encode(ByteArray(PdfContentValidation.MAX_PAYLOAD_BYTES + 1)) }
    }

    @Test fun noncanonicalBase64IsRejectedBeforePayloadAllocation() {
        listOf("", "Zg", "Zg=", "Zg===", "Zh==", "Zm9=", "Zg==\n", " Zg==", "Zg==AAAA", "====", "=AAA", "AA=A", "_w==", "-w==", "Z\uFF47==").forEach { value ->
            reject { PdfContentBase64.decodedLength(value) }
            reject { PdfContentBase64.decode(value) }
        }
        val over = Base64.getEncoder().encodeToString(ByteArray(PdfContentValidation.MAX_PAYLOAD_BYTES + 1))
        reject { PdfContentBase64.decodedLength(over) }
    }

    @Test fun fourMiBOriginalIsCountedOnceButEveryUniquePayloadCountsTowardDecodedLimit() {
        val bytes = ByteArray(PdfContentValidation.MAX_PAYLOAD_BYTES)
        "%PDF-".toByteArray().copyInto(bytes)
        val large = ExchangeAsset(bytes, "application/pdf")
        val content = PdfContentDocument(language = "en", paragraphs = emptyList(),
            assets = listOf(ExchangeAssetReference(large.path, "pdf")), payloads = listOf(payload(large)))
        val proof = validate(content).proof
        assertEquals(PdfContentValidation.MAX_PAYLOAD_BYTES.toLong(), proof.originalFileByteLength)
        reject { validate(content.copy(assets = content.assets + ExchangeAssetReference(image.path, "image"), payloads = content.payloads + payload(image))) }
    }

    @Test fun wrongBytesMimeSignatureOrMissingDuplicateAndOrphanPayloadsAreRejected() {
        val d = document()
        reject { validate(d.copy(payloads = d.payloads + payload(image))) }
        reject { validate(d.copy(payloads = listOf(payload(pdf)))) }
        reject { validate(d.copy(assets = listOf(d.assets[0]))) }
        reject { validate(d.copy(assets = d.assets + d.assets[0])) }
        reject { validate(d.copy(assets = d.assets.drop(1))) }
        reject { validate(d.copy(assets = listOf(d.assets[0].copy(paragraphId = "empty")) + d.assets.drop(1))) }
        reject { validate(d.copy(assets = listOf(d.assets[0], d.assets[1].copy(paragraphId = "orphan"), d.assets[2]))) }
        reject { validate(d.copy(payloads = listOf(d.payloads[0].copy(base64 = d.payloads[1].base64), d.payloads[1]))) }
        reject { validate(d.copy(payloads = listOf(d.payloads[0].copy(mimeType = "APPLICATION/PDF"), d.payloads[1]))) }
        val notPdf = ExchangeAsset("not PDF".toByteArray(), "application/pdf")
        reject { validate(d.copy(assets = listOf(ExchangeAssetReference(notPdf.path, "pdf")), payloads = listOf(payload(notPdf)))) }
    }

    @Test fun metadataAndCollectionLimitsAreExactAndInvalidUnicodeNeverNormalizes() {
        val d = document()
        val minimal = d.copy(language = "en", paragraphs = listOf(ExchangeParagraph("p", "x".repeat(PdfContentValidation.MAX_METADATA_CHARACTERS - 3))),
            assets = listOf(d.assets[0]), payloads = listOf(d.payloads[0]))
        validate(minimal)
        reject { validate(minimal.copy(paragraphs = listOf(minimal.paragraphs[0].copy(text = minimal.paragraphs[0].text + "x")))) }
        reject { validate(d.copy(version = 2)) }
        reject { validate(d.copy(paragraphs = List(PdfContentValidation.MAX_PARAGRAPHS + 1) { ExchangeParagraph("p$it", "") })) }
        reject { validate(d.copy(assets = listOf(d.assets[0]) + List(PdfContentValidation.MAX_REFERENCES) { d.assets[1] })) }
        reject { validate(d.copy(payloads = List(PdfContentValidation.MAX_PAYLOADS + 1) { d.payloads[0] })) }
        reject { validate(d.copy(paragraphs = listOf(d.paragraphs[0], d.paragraphs[0]))) }
        reject { validate(d.copy(paragraphs = listOf(ExchangeParagraph("\uD800", "")))) }
        reject { validate(d.copy(paragraphs = listOf(ExchangeParagraph("p", "\uDC00")))) }
        reject { validate(d.copy(assets = listOf(d.assets[0], d.assets[1].copy(alt = "\uD800"), d.assets[2]))) }
        reject { validate(d.copy(assets = listOf(d.assets[0], d.assets[1].copy(alt = "x".repeat(2001)), d.assets[2]))) }
    }

    @Test fun escapedProofAtMetadataLimitFitsTwoMiBVerifyProfileNotOld128KiB() {
        val d = document()
        val boundId = "p".repeat(63)
        val refs = listOf(d.assets[0]) + List(127) { d.assets[1].copy(paragraphId = boundId, alt = "\u0000".repeat(2000)) }
        val refCharacters = 127 * (boundId.length + 2000)
        val textSize = PdfContentValidation.MAX_METADATA_CHARACTERS - 2 - boundId.length - refCharacters
        val content = d.copy(language = "en", paragraphs = listOf(ExchangeParagraph(boundId, "x".repeat(textSize))), assets = refs)
        val proof = validate(content).proof
        PdfContentValidation.validateProof(proof)
        val escapedAltBytes = 127 * 2000 * 6
        assertTrue(escapedAltBytes > 128 * 1024)
        // 512 per reference safely covers fixed JSON keys/hash/path/mime/length, plus UTF-8 paragraph IDs.
        val conservativeProofWireBound = escapedAltBytes + 128 * (512 + boundId.length) + 1024
        assertTrue(conservativeProofWireBound < PdfContentValidation.MAX_VERIFY_BYTES)
        assertEquals(2 * 1024 * 1024, PdfContentValidation.MAX_VERIFY_BYTES)
        // GET repeats exact references in both content and proof; a legal response can exceed upload's 8 MiB.
        val conservativeFullResponseBound = PdfContentValidation.MAX_BASE64_CHARACTERS +
            PdfContentValidation.MAX_METADATA_CHARACTERS * 6 + conservativeProofWireBound + 512 * 1024
        assertTrue(conservativeFullResponseBound > PdfContentValidation.MAX_REQUEST_BYTES)
        assertTrue(conservativeFullResponseBound < PdfContentValidation.MAX_RESPONSE_BYTES)
    }

    @Test fun proofMustBePdfAndMatchCompleteDigestAndSnapshotCopiesCallerLists() {
        val d = document()
        val refs = d.assets.toMutableList(); val payloads = d.payloads.toMutableList(); val paragraphs = d.paragraphs.toMutableList()
        val validated = validate(d.copy(assets = refs, payloads = payloads, paragraphs = paragraphs))
        refs.clear(); payloads.clear(); paragraphs.clear()
        assertEquals(d, validated.content)
        reject { PdfContentValidation.validateProof(validated.proof.copy(language = "en")) }
        val text = PortableContentProofs.compute(PortableRepresentation.TEXT,
            ExchangeDocument("local", "", "", "ko", "local", d.paragraphs))
        reject { PdfContentValidation.validateProof(text) }
    }

    @Test fun sharedIndependentPythonFixtureMatchesJvmProofAndFullContentFingerprint() {
        val fixtures = listOf(File("contracts/fixtures"), File("../contracts/fixtures")).first { it.isDirectory }
        val original = ExchangeAsset(File(fixtures, "portable-content-proof-v1/source.pdf").readBytes(), "application/pdf")
        val picture = ExchangeAsset(File(fixtures, "portable-content-proof-v1/image.png").readBytes(), "image/png")
        val content = PdfContentDocument(language = "ko-KR", paragraphs = listOf(ExchangeParagraph("p:😀", "안녕 😀:|\n"), ExchangeParagraph("empty", "")),
            assets = listOf(ExchangeAssetReference(original.path, "pdf"), ExchangeAssetReference(picture.path, "image", "p:😀", " 삽화 😀 "),
                ExchangeAssetReference(picture.path, "image", "empty", "")), payloads = listOf(payload(original), payload(picture)))
        val result = validate(content)
        val fixture = File(fixtures, "pdf-content-v1.json").readText()
        // Only read fixed expected digest fields; production JSON parsing belongs to server/web adapters.
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([a-f0-9]{64})\"").findAll(fixture).last().groupValues[1]
        assertEquals(field("requestFingerprint"), result.requestFingerprint)
        assertEquals(field("paragraphHash"), result.proof.paragraphHash)
        assertEquals(field("sha256"), result.proof.sha256)
    }
}
