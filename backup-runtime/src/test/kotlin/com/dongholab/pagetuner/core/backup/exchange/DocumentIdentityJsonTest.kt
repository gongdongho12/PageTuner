package com.dongholab.pagetuner.core.backup.exchange

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DocumentIdentityJsonTest {
    private val workspace = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "contracts/fixtures/library-identity-v1/original.json").isFile }
    private fun document(name: String) = ExchangeJson.decode(File(workspace, "contracts/fixtures/library-identity-v1/$name.json").readBytes())

    @Test fun sharedUnicodeFixturesRoundTripWithAllIdentityFieldsAndNoOriginalNullKeys() {
        for (name in listOf("original", "translation")) {
            val doc = document(name); val identity = requireNotNull(DocumentIdentityJson.fromDocument(doc))
            assertEquals(identity, DocumentIdentityJson.decode(DocumentIdentityJson.encode(identity)))
            val encoded = DocumentIdentityJson.encode(identity)
            assertEquals(name == "translation", encoded.has("targetLanguage"))
            assertEquals(identity, DocumentIdentityJson.fromDocument(DocumentIdentityJson.withIdentity(doc, identity)))
            val archive = LibraryExchangeCodec.write(LibraryExchangePackage("2026-10-03T00:00:00Z", listOf(doc)))
            assertEquals(identity, DocumentIdentityJson.fromDocument(LibraryExchangeCodec.read(archive).documents.single()))
        }
    }

    @Test fun unknownOrMalformedProvenanceRemainsPassiveReadableButCannotVerify() {
        val doc = document("original")
        val changed = doc.copy(extensionsJson = JSONObject(doc.extensionsJson!!).put("documentIdentity", JSONObject().put("version", 2)).toString())
        val archive = LibraryExchangeCodec.write(LibraryExchangePackage("2026-10-03T00:00:00Z", listOf(changed)))
        val restored = LibraryExchangeCodec.read(archive).documents.single()
        assertEquals(changed.paragraphs, restored.paragraphs)
        assertThrows(Exception::class.java) { DocumentIdentityJson.fromDocument(restored) }
        assertNull(DocumentIdentityJson.fromDocument(doc.copy(extensionsJson = null)))
        val raw = DocumentIdentityJson.encode(requireNotNull(DocumentIdentityJson.fromDocument(doc)))
        assertEquals(DocumentIdentityJson.decode(raw), DocumentIdentityJson.decode(JSONObject(raw.toString()).put("version", 1.0)))
        for (modified in listOf(JSONObject(raw.toString()).put("version", "1"), JSONObject(raw.toString()).put("version", 2.0), JSONObject(raw.toString()).put("unknown", true),
            JSONObject(raw.toString()).put("modelId", JSONObject.NULL))) {
            assertThrows(Exception::class.java) { DocumentIdentityJson.decode(modified) }
        }
    }
}
