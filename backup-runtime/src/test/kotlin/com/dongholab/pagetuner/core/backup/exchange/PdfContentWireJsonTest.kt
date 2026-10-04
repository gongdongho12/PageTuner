package com.dongholab.pagetuner.core.backup.exchange

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PdfContentWireJsonTest {
    private val recordId = "a2e5b2f6-0204-46c0-9f0a-0b50a8625f46"
    private val otherId = "a2e5b2f6-0204-46c0-9f0a-0b50a8625f47"
    private val createdAt = "2026-10-05T01:02:03.123456789Z"
    private fun fixture(): JSONObject {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "contracts/fixtures/pdf-content-v1.json").isFile }
        return JSONObject(File(root, "contracts/fixtures/pdf-content-v1.json").readText())
    }
    private fun content(): PdfContentDocument {
        val v = fixture().getJSONObject("upload").getJSONObject("content")
        val paragraphs = v.getJSONArray("paragraphs"); val refs = v.getJSONArray("assets"); val payloads = v.getJSONArray("payloads")
        return PdfContentDocument(language = v.getString("language"), paragraphs = (0 until paragraphs.length()).map { i ->
            val p = paragraphs.getJSONObject(i); ExchangeParagraph(p.getString("paragraphId"), p.getString("text"))
        }, assets = (0 until refs.length()).map { i ->
            val a = refs.getJSONObject(i); ExchangeAssetReference(a.getString("path"), a.getString("role"),
                a.get("paragraphId").takeUnless { it === JSONObject.NULL } as String?, a.get("alt").takeUnless { it === JSONObject.NULL } as String?)
        }, payloads = (0 until payloads.length()).map { i ->
            val p = payloads.getJSONObject(i); PdfContentPayload(p.getString("path"), p.getString("mimeType"), p.getString("base64"))
        })
    }
    private fun bytes(value: JSONObject) = value.toString().toByteArray(Charsets.UTF_8)
    private fun proofJson(proof: PortableContentProof) = JSONObject(String(PdfContentWireJson.encodeVerification(proof), Charsets.UTF_8)).getJSONObject("proof")
    private fun receipt(proof: PortableContentProof = PdfContentValidation.validateContent(content()).proof) =
        JSONObject().put("recordId", recordId).put("createdAt", createdAt).put("proof", proofJson(proof))
    private fun record(content: PdfContentDocument = content()): JSONObject {
        val upload = JSONObject(String(PdfContentWireJson.encodeUpload(PdfContentUpload(recordId, content)), Charsets.UTF_8))
        return receipt(PdfContentValidation.validateContent(content).proof).put("content", upload.getJSONObject("content"))
    }
    private fun reject(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun sharedFixtureRoundTripsAllRequiredNullsAndExactContentProof() {
        val content = content(); val actual = PdfContentValidation.validateContent(content)
        assertEquals(fixture().getJSONObject("expected").getString("requestFingerprint"), actual.requestFingerprint)
        val upload = JSONObject(String(PdfContentWireJson.encodeUpload(PdfContentUpload(recordId, content)), Charsets.UTF_8))
        assertEquals(recordId, upload.getString("uploadId"))
        val originalRef = upload.getJSONObject("content").getJSONArray("assets").getJSONObject(0)
        assertTrue(originalRef.has("paragraphId") && originalRef.isNull("paragraphId"))
        assertTrue(originalRef.has("alt") && originalRef.isNull("alt"))
        val json = receipt().put("proof", fixture().getJSONObject("expected").getJSONObject("proof"))
        assertEquals(actual.proof, PdfContentWireJson.decodeReceipt(bytes(json), actual.proof).proof)
        val response = json.put("content", upload.getJSONObject("content"))
        assertEquals(content, PdfContentWireJson.decodeRecord(bytes(response), recordId).content)
        val verified = JSONObject().put("recordId", recordId).put("verified", true).put("proof", proofJson(actual.proof))
        assertEquals(PdfContentVerification(recordId, true, actual.proof), PdfContentWireJson.decodeVerification(bytes(verified), recordId, actual.proof))
    }

    @Test fun missingUnknownNullAndWrongTypeFieldsNeverUseDefaultsOrCoercion() {
        val proof = PdfContentValidation.validateContent(content()).proof
        listOf<(JSONObject) -> Unit>({ it.remove("createdAt") }, { it.put("extra", true) }, { it.put("recordId", JSONObject.NULL) },
            { it.put("createdAt", 2026) }, { it.getJSONObject("proof").put("ignored", true) },
            { it.getJSONObject("proof").getJSONArray("assets").getJSONObject(0).remove("alt") },
            { it.getJSONObject("proof").getJSONArray("assets").getJSONObject(0).put("paragraphId", false) },
            { it.getJSONObject("proof").put("originalFileSha256", JSONObject.NULL) }).forEach { change ->
            val value = receipt(); change(value); reject { PdfContentWireJson.decodeReceipt(bytes(value), proof) }
        }
        listOf<(JSONObject) -> Unit>({ it.getJSONObject("content").put("other", 1) },
            { it.getJSONObject("content").getJSONArray("paragraphs").getJSONObject(0).put("text", 1) },
            { it.getJSONObject("content").getJSONArray("assets").getJSONObject(0).remove("paragraphId") },
            { it.getJSONObject("content").put("paragraphs", JSONObject.NULL) }).forEach { change ->
            val value = record(); change(value); reject { PdfContentWireJson.decodeRecord(bytes(value), recordId) }
        }
    }

    @Test fun strictRawJsonRejectsDuplicateEscapedKeysTrailingTokensLenientSyntaxAndNumericSpellings() {
        val proof = PdfContentValidation.validateContent(content()).proof
        val good = receipt().toString()
        listOf(good + "{}", good + " trailing", good.dropLast(1) + ",}", good.replace("\"recordId\"", "recordId"),
            "{\"recordId\":\"$recordId\"," + good.drop(1), "{\"record\\u0049d\":\"$recordId\"," + good.drop(1),
            good.replace("\"version\":1", "\"version\":1.0"), good.replace("\"version\":1", "\"version\":1e0"),
            good.replace("\"version\":1", "\"version\":\"1\""), good.replace("\"version\":1", "\"version\":9007199254740992"),
            good.replace("\"version\":1", "\"version\":1.00000000000000000001"),
            good.replace("\"version\":1", "\"version\":+1")).forEach { raw ->
            assertNotEquals(good, raw)
            reject { PdfContentWireJson.decodeReceipt(raw.toByteArray(Charsets.UTF_8), proof) }
        }
        // Existing ZIP semantics remain unchanged; only the PDF HTTP profile rejects these spellings.
        StrictExchangeJson.validate("{\"version\":1.0}")
        assertEquals("{\"version\":1.0}", StrictExchangeJson.normalize("{\"version\":1e0}"))
    }

    @Test fun malformedUtf8AndUnpairedEscapedSurrogatesFailButValidPairsRemainExact() {
        val proof = PdfContentValidation.validateContent(content()).proof
        reject { PdfContentWireJson.decodeReceipt(byteArrayOf(0xc3.toByte(), 0x28), proof) }
        val good = record().toString()
        assertEquals(content(), PdfContentWireJson.decodeRecord(good.replace("😀", "\\ud83d\\ude00").toByteArray(), recordId).content)
        listOf(good.replace("😀", "\\ud800"), good.replace("😀", "\\udc00")).forEach {
            reject { PdfContentWireJson.decodeRecord(it.toByteArray(), recordId) }
        }
        reject { PdfContentWireJson.decodeCsrf("{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"\\ud800\"}".toByteArray()) }
    }

    @Test fun timestampsPreserveOffsetsAndFractionsButRejectInvalidCalendarDaysAndRanges() {
        val proof = PdfContentValidation.validateContent(content()).proof
        listOf(createdAt, "2024-02-29T23:59:59+09:00", "2026-10-05T01:02:03.1-18:00").forEach { timestamp ->
            assertEquals(timestamp, PdfContentWireJson.decodeReceipt(bytes(receipt().put("createdAt", timestamp)), proof).createdAt)
        }
        listOf("2026-02-29T01:02:03Z", "2026-13-01T01:02:03Z", "2026-10-05T24:02:03Z", "0000-01-01T00:00:00Z", "2026-10-05T01:02:03+18:01").forEach {
            reject { PdfContentWireJson.decodeReceipt(bytes(receipt().put("createdAt", it)), proof) }
        }
    }

    @Test fun returnedRecordAndFullProofMustMatchExpectedIdentityAndActualPayloadBytes() {
        val c = content(); val proof = PdfContentValidation.validateContent(c).proof
        reject { PdfContentWireJson.decodeRecord(bytes(record()), otherId) }
        reject { PdfContentWireJson.decodeRecord(bytes(record().put("recordId", recordId.uppercase())), recordId) }
        val different = PdfContentValidation.validateContent(c.copy(language = "en")).proof
        reject { PdfContentWireJson.decodeReceipt(bytes(receipt(different)), proof) }
        val wrongProof = record().put("proof", proofJson(different))
        reject { PdfContentWireJson.decodeRecord(bytes(wrongProof), recordId) }
        val corrupt = record()
        val payload = corrupt.getJSONObject("content").getJSONArray("payloads").getJSONObject(0)
        payload.put("base64", "bm90IFBERg==")
        reject { PdfContentWireJson.decodeRecord(bytes(corrupt), recordId) }
        val badDigest = record(); badDigest.getJSONObject("proof").put("sha256", "0".repeat(64))
        reject { PdfContentWireJson.decodeRecord(bytes(badDigest), recordId) }
        for (verified in listOf<Any>(false, "true", 1, JSONObject.NULL)) {
            reject { PdfContentWireJson.decodeVerification(bytes(JSONObject().put("recordId", recordId).put("verified", verified).put("proof", proofJson(proof))), recordId, proof) }
        }
        reject { PdfContentWireJson.decodeVerification(bytes(JSONObject().put("recordId", otherId).put("verified", true).put("proof", proofJson(proof))), recordId, proof) }
    }

    @Test fun base64PayloadAndAggregateLimitsAreEnforcedOnBothEncodeAndGet() {
        val c = content()
        reject { PdfContentWireJson.encodeUpload(PdfContentUpload(recordId.uppercase(), c)) }
        reject { PdfContentWireJson.encodeUpload(PdfContentUpload(recordId, c.copy(payloads = c.payloads + c.payloads[0]))) }
        val bad = record(); val values = bad.getJSONObject("content").getJSONArray("payloads")
        values.getJSONObject(0).put("base64", values.getJSONObject(0).getString("base64").dropLast(1))
        reject { PdfContentWireJson.decodeRecord(bytes(bad), recordId) }
        val hugeMetadata = c.copy(paragraphs = listOf(ExchangeParagraph("p", "x".repeat(PdfContentValidation.MAX_METADATA_CHARACTERS))))
        reject { PdfContentWireJson.encodeUpload(PdfContentUpload(recordId, hugeMetadata)) }
    }

    @Test fun largestEscapedReferencesAndFourMiBPayloadRoundTripAboveEightMiBGet() {
        val original = ByteArray(PdfContentValidation.MAX_PAYLOAD_BYTES - 1)
        "%PDF-".toByteArray().copyInto(original)
        val pdf = ExchangeAsset(original, "application/pdf"); val image = ExchangeAsset(byteArrayOf(1), "image/png")
        val c = PdfContentDocument(language = "en", paragraphs = emptyList(),
            assets = listOf(ExchangeAssetReference(pdf.path, "pdf")) + List(127) { ExchangeAssetReference(image.path, "image", null, "\u0001".repeat(2000)) },
            payloads = listOf(pdf, image).map { PdfContentPayload(it.path, it.mimeType, PdfContentBase64.encode(it.bytes)) })
        val checked = PdfContentValidation.validateContent(c)
        assertTrue(PdfContentWireJson.encodeUpload(PdfContentUpload(recordId, c)).size < PdfContentValidation.MAX_REQUEST_BYTES)
        val verify = PdfContentWireJson.encodeVerification(checked.proof)
        assertTrue(verify.size > 128 * 1024 && verify.size < PdfContentValidation.MAX_VERIFY_BYTES)
        val raw = bytes(record(c))
        assertTrue(raw.size > PdfContentValidation.MAX_REQUEST_BYTES && raw.size < PdfContentValidation.MAX_RESPONSE_BYTES)
        assertEquals(checked.proof, PdfContentWireJson.decodeRecord(raw, recordId).proof)
        assertEquals(c, PdfContentWireJson.decodeRecord(raw, recordId).content)
    }

    @Test fun wireLimitsAreCheckedBeforeParsingOrAllocatingUnboundedDtoCollections() {
        val proof = PdfContentValidation.validateContent(content()).proof
        val smallLimitOverflow = ByteArray(PdfContentValidation.MAX_VERIFY_BYTES + 1) { ' '.code.toByte() }
        reject { PdfContentWireJson.decodeReceipt(smallLimitOverflow, proof) }
        reject { PdfContentWireJson.decodeVerification(smallLimitOverflow, recordId, proof) }
        reject { PdfContentWireJson.decodeRecord(ByteArray(PdfContentValidation.MAX_RESPONSE_BYTES + 1), recordId) }
        val oversizedProof = proof.copy(assets = object : AbstractList<PortableAssetProof>() {
            override val size = PdfContentValidation.MAX_REFERENCES + 1
            override fun get(index: Int): PortableAssetProof = error("An oversized proof must fail before copying elements.")
        })
        reject { PdfContentWireJson.encodeVerification(oversizedProof) }
        val tooMany = record(); val paragraphs = JSONArray()
        repeat(4097) { paragraphs.put(JSONObject().put("paragraphId", "p$it").put("text", "")) }
        tooMany.getJSONObject("content").put("paragraphs", paragraphs)
        reject { PdfContentWireJson.decodeRecord(bytes(tooMany), recordId) }
    }

    @Test fun csrfAndProblemBoundariesRejectHeaderInjectionCoercionAndDuplicateFields() {
        assertEquals("X-CSRF-TOKEN" to "token", PdfContentWireJson.decodeCsrf("{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"token\"}".toByteArray()))
        listOf("{\"headerName\":\"Authorization\",\"token\":\"token\"}", "{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"bad\\r\\nvalue\"}",
            "{\"headerName\":\"X-CSRF-TOKEN\",\"token\":1}", "{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"x\",\"token\":\"y\"}").forEach {
            reject { PdfContentWireJson.decodeCsrf(it.toByteArray()) }
        }
        assertEquals("PDF_CONTENT_MISMATCH", PdfContentWireJson.decodeProblemCode("{\"status\":409,\"code\":\"PDF_CONTENT_MISMATCH\",\"detail\":\"Mismatch\"}".toByteArray()))
        assertNull(PdfContentWireJson.decodeProblemCode("{\"status\":500,\"title\":\"Server error\"}".toByteArray()))
        listOf("{\"status\":409.0}", "{\"code\":1}", "{\"code\":\"A\",\"code\":\"B\"}", "{\"extra\":true}", "{\"detail\":\"\\ud800\"}").forEach {
            reject { PdfContentWireJson.decodeProblemCode(it.toByteArray()) }
        }
        reject { PdfContentWireJson.decodeCsrf(ByteArray(64 * 1024 + 1)) }
        reject { PdfContentWireJson.decodeProblemCode(ByteArray(64 * 1024 + 1)) }
    }
}
