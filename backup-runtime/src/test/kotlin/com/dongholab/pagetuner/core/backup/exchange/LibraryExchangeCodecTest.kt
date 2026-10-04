package com.dongholab.pagetuner.core.backup.exchange

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

class LibraryExchangeCodecTest {
    private val workspace = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "contracts/fixtures/library-exchange-v1/portable-v1.zip").isFile }
    private val fixture = File(workspace, "contracts/fixtures/library-exchange-v1/portable-v1.zip")

    @Test fun sharedFixturePreservesOriginalTranslationAssetsAndReadingMetadata() {
        val library = LibraryExchangeCodec.read(fixture.readBytes())
        assertEquals(listOf("fixture:original", "fixture:translation", "fixture:pdf"), library.documents.map { it.id })
        val original = library.documents.first()
        assertEquals("Hello 🌏.\nSecond line.", original.paragraphs.first().text)
        assertEquals(ExchangeAnchor("p-1", 8), original.position)
        assertEquals("highlight", original.notes.single().kind)
        assertEquals(5, original.notes.single().range?.end?.characterOffset)
        assertTrue(original.organization.favorite)
        assertEquals(false, original.glossary.single().caseSensitive)
        assertEquals("TERM", original.glossary.single().kind)
        assertEquals(setOf("image/png", "application/pdf"), library.assets.map { it.mimeType }.toSet())
        val restored = LibraryExchangeCodec.read(LibraryExchangeCodec.write(library))
        assertEquals(library.documents, restored.documents)
        library.assets.zip(restored.assets).forEach { (before, after) -> assertArrayEquals(before.bytes, after.bytes) }
    }

    @Test fun kotlinExportAndOptionalWebReexportUseSamePortableContract() {
        val library = LibraryExchangeCodec.read(fixture.readBytes())
        val output = File(workspace, ".gradle-home/kotlin-portable-roundtrip.zip")
        output.parentFile.mkdirs(); output.writeBytes(LibraryExchangeCodec.write(library))
        val webPath = System.getenv("PAGETURNER_WEB_EXCHANGE_FIXTURE") ?: return
        val restored = LibraryExchangeCodec.read(File(webPath).readBytes())
        assertEquals(library.documents.map { it.id }, restored.documents.map { it.id })
        assertEquals(library.documents.map { it.paragraphs }, restored.documents.map { it.paragraphs })
        assertEquals(library.documents.map { it.notes }, restored.documents.map { it.notes })
        assertEquals(library.documents.map { it.glossary }, restored.documents.map { it.glossary })
        assertEquals(library.documents.map { it.position }, restored.documents.map { it.position })
        assertEquals(library.documents.map { it.organization }, restored.documents.map { it.organization })
        assertEquals(library.documents.map { it.outline }, restored.documents.map { it.outline })
        assertEquals(library.documents.map { it.assets }, restored.documents.map { it.assets })
        library.documents.zip(restored.documents).forEach { (before, after) ->
            assertTrue(JSONObject(before.extensionsJson ?: "{}").similar(JSONObject(after.extensionsJson ?: "{}")))
        }
        assertEquals(library.assets.map { it.sha256 }.toSet(), restored.assets.map { it.sha256 }.toSet())
        assertEquals(library.assets.associate { it.sha256 to it.mimeType }, restored.assets.associate { it.sha256 to it.mimeType })
    }

    @Test fun imageOnlyDocumentsPreserveAssetsWithoutInventingParagraphs() {
        val library = LibraryExchangeCodec.read(fixture.readBytes())
        val pdf = library.documents.last().copy(language = "und", paragraphs = emptyList(), outline = emptyList())
        val asset = library.assets.single { it.mimeType == "application/pdf" }
        val restored = LibraryExchangeCodec.read(LibraryExchangeCodec.write(LibraryExchangePackage(library.createdAt, listOf(pdf), listOf(asset))))
        assertTrue(restored.documents.single().paragraphs.isEmpty())
        assertArrayEquals(asset.bytes, restored.assets.single().bytes)
    }

    @Test fun storedZipEntriesWithUpfrontSizesAreInteroperable() {
        val entries = unzip(fixture.readBytes())
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply {
                method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
            })
            zip.write(bytes); zip.closeEntry()
        } }
        assertEquals(3, LibraryExchangeCodec.read(output.toByteArray()).documents.size)
    }

    @Test fun unknownVersionsAndUndeclaredEntriesAreRejected() {
        val entries = unzip(fixture.readBytes())
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        manifest.put("version", 2); entries["manifest.json"] = manifest.toString().toByteArray()
        reject { LibraryExchangeCodec.read(zip(entries)) }
        val extra = unzip(fixture.readBytes())
        extra["assets/${"0".repeat(64)}"] = byteArrayOf(1)
        reject { LibraryExchangeCodec.read(zip(extra)) }
    }

    @Test fun alteredPayloadAndForgedManifestSizeAreRejected() {
        val entries = unzip(fixture.readBytes())
        val path = entries.keys.first { it.startsWith("documents/") }
        entries[path] = entries.getValue(path) + byteArrayOf(32)
        reject { LibraryExchangeCodec.read(zip(entries)) }
        val forged = unzip(fixture.readBytes())
        val manifest = JSONObject(forged.getValue("manifest.json").toString(Charsets.UTF_8))
        manifest.getJSONArray("documents").getJSONObject(0).put("bytes", 1)
        forged["manifest.json"] = manifest.toString().toByteArray()
        reject { LibraryExchangeCodec.read(zip(forged)) }
    }

    @Test fun traversalBackslashesHiddenDataAndEncryptedFlagsAreRejected() {
        for (name in listOf("../secret", "documents\\secret", "/manifest.json", "documents/")) {
            val entries = unzip(fixture.readBytes()); entries[name] = byteArrayOf(1)
            reject { LibraryExchangeCodec.read(zip(entries)) }
        }
        reject { LibraryExchangeCodec.read(byteArrayOf(1) + fixture.readBytes()) }
        reject { LibraryExchangeCodec.read(fixture.readBytes() + byteArrayOf(1)) }
        val encrypted = fixture.readBytes(); encrypted[6] = (encrypted[6].toInt() or 1).toByte()
        reject { LibraryExchangeCodec.read(encrypted) }
    }

    @Test fun duplicateZipNamesAndManifestReferencesAreRejected() {
        val entries = unzip(fixture.readBytes())
        val duplicate = zip(entries).clone()
        // Rename the second local+central path to the third same-length document path.
        val paths = entries.keys.filter { it.startsWith("documents/") }
        val from = paths[0].toByteArray(); val to = paths[1].toByteArray()
        for (index in 0..duplicate.size - from.size) {
            if (from.indices.all { duplicate[index + it] == from[it] }) to.copyInto(duplicate, index)
        }
        reject { LibraryExchangeCodec.read(duplicate) }
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        manifest.getJSONArray("documents").put(manifest.getJSONArray("documents").getJSONObject(0))
        entries["manifest.json"] = manifest.toString().toByteArray()
        reject { LibraryExchangeCodec.read(zip(entries)) }
    }

    @Test fun expansionBombAndOversizeArchiveAreRejected() {
        val entries = unzip(fixture.readBytes())
        entries["documents/${"0".repeat(64)}.json"] = ByteArray(LibraryExchangeLimits.DOCUMENT_BYTES + 1) { 32 }
        reject { LibraryExchangeCodec.read(zip(entries)) }
        reject { LibraryExchangeCodec.read(ByteArray(LibraryExchangeLimits.ARCHIVE_BYTES + 1)) }
    }

    @Test fun invalidAnchorsIdsUnicodeAndAssetReferencesAreRejected() {
        val library = LibraryExchangeCodec.read(fixture.readBytes())
        val original = library.documents.first()
        val invalid = listOf(
            original.copy(position = ExchangeAnchor("p-1", 7)),
            original.copy(position = ExchangeAnchor("missing", 0)),
            original.copy(position = ExchangeAnchor("p-1", 900)),
            original.copy(paragraphs = original.paragraphs + original.paragraphs.first()),
            original.copy(paragraphs = listOf(ExchangeParagraph("p-1", "\ud800"))),
            original.copy(assets = listOf(ExchangeAssetReference("assets/${"0".repeat(64)}", "image"))),
        )
        invalid.forEach { document -> reject { LibraryExchangeCodec.write(library.copy(documents = listOf(document))) } }
        reject { LibraryExchangeCodec.write(library.copy(documents = library.documents + original)) }
    }

    @Test fun unsafeMetadataSvgAndUnreferencedAssetsAreRejected() {
        val library = LibraryExchangeCodec.read(fixture.readBytes())
        for (key in listOf("apiKey", "access_token", "Authorization", "password", "credentials")) {
            val document = library.documents.first().copy(extensionsJson = "{\"nested\":{\"$key\":\"not-a-real-secret\"}}")
            reject { LibraryExchangeCodec.write(library.copy(documents = listOf(document))) }
        }
        reject { LibraryExchangeCodec.write(library.copy(assets = library.assets + ExchangeAsset("<svg/>".toByteArray(), "image/svg+xml"))) }
        reject { LibraryExchangeCodec.write(library.copy(assets = library.assets + ExchangeAsset(byteArrayOf(1), "image/png"))) }
        val deep = "{\"a\":".repeat(40) + "1" + "}".repeat(40)
        reject { ExchangeJson.parseObject(deep.toByteArray()) }
    }

    @Test fun strictJsonRejectsMalformedDocumentsAndDuplicateKeysAcrossRuntimes() {
        for (text in listOf("{'a':1}", "{a:1}", "{\"a\":1,}", "{\"a\":[1,]}", "{} garbage", "{\"a\":01}", "{\"a\":NaN}", "{\"a\":1,\"\\u0061\":2}")) {
            reject { ExchangeJson.parseObject(text.toByteArray()) }
        }
        ExchangeJson.parseObject("{\"number\":-1.2e+3,\"values\":[true,false,null,\"\\uD83C\\uDF0F\"]}".toByteArray())
        reject { ExchangeJson.parseObject(byteArrayOf(123, 34, 0xc0.toByte(), 0x80.toByte(), 34, 58, 49, 125)) }
    }

    @Test fun timestampsAreStrictAndAvailableOnAndroidApi23() {
        listOf("2026-09-15T00:00:00Z", "2026-09-15T09:00:00.123456789+09:00", "1582-10-10T00:00:00Z").forEach(ExchangeJson::timestamp)
        listOf("2026-02-30T00:00:00Z", "1500-02-29T00:00:00Z", "2026-09-15T24:00:00Z", "2026-09-15", "2026-09-15T00:00:00+18:01").forEach { reject { ExchangeJson.timestamp(it) } }
    }

    @Test fun extensionNumbersUsePortableBinary64WithoutUnsafeIntegersOrUnderflow() {
        val library = LibraryExchangeCodec.read(fixture.readBytes())
        for (number in listOf("9007199254740993", "-9007199254740993", "9.007199254740993e15", "1e309", "1e-400")) {
            reject { ExchangeJson.parseObject("{\"value\":$number}".toByteArray()) }
            val document = library.documents.first().copy(extensionsJson = "{\"value\":$number}")
            reject { LibraryExchangeCodec.write(library.copy(documents = listOf(document) + library.documents.drop(1))) }
        }
        val metadata = "{\"numbers\":[9007199254740991,-9007199254740991,0.125,1.5e-20,0.12345678901234567890123456789],\"exact\":\"9007199254740993\"}"
        val document = library.documents.first().copy(extensionsJson = metadata)
        val restored = LibraryExchangeCodec.read(LibraryExchangeCodec.write(library.copy(documents = listOf(document) + library.documents.drop(1))))
        val parsed = JSONObject(restored.documents.first().extensionsJson!!)
        val numbers = parsed.getJSONArray("numbers")
        val expected = listOf(9_007_199_254_740_991.0, -9_007_199_254_740_991.0, 0.125, 1.5e-20, 0.12345678901234568)
        expected.forEachIndexed { index, value -> assertEquals(value, numbers.getDouble(index), 0.0) }
        assertEquals("9007199254740993", parsed.getString("exact"))
        assertTrue(!restored.documents.first().extensionsJson!!.contains("0.12345678901234567890123456789"))
    }

    private fun reject(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Expected invalid package to be rejected", rejected)
    }
    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            put(entry.name, zip.readBytes()); zip.closeEntry()
        } }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        return output.toByteArray()
    }
}
