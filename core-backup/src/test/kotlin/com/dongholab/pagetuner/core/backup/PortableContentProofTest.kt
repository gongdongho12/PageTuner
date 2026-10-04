package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.backup.exchange.*
import org.junit.Assert.*
import org.junit.Test

class PortableContentProofTest {
    private val paragraphs = listOf(ExchangeParagraph("p:原🌏", " A🌏\nB|C "), ExchangeParagraph("empty", ""))
    private val original = byteArrayOf(0, 1, 2, 127, -1)
    private val image = ExchangeAsset(byteArrayOf(10, 20, 30), "image/png")
    private val pdf = ExchangeAsset(original, "application/pdf")
    private fun document(refs: List<ExchangeAssetReference> = emptyList()) = ExchangeDocument(
        "local-id", "Book", "Chapter", "ko-KR", "local", paragraphs, assets = refs)
    private fun ref(asset: ExchangeAsset, role: String = "image", paragraph: String? = null, alt: String? = null) =
        ExchangeAssetReference(asset.path, role, paragraph, alt)
    private fun text() = PortableContentProofs.compute(PortableRepresentation.TEXT, document())
    private fun pdfDocument() = document(listOf(ref(pdf, "pdf"), ref(image, paragraph = "p:原🌏", alt = "삽화")))
    private fun pdfProof() = PortableContentProofs.compute(PortableRepresentation.PDF, pdfDocument(), listOf(pdf, image), original)
    private fun reject(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun metadataAndOpaqueLocalIdsAreExcludedButExactContentLanguageOrderAndReferenceBindingsMatter() {
        val originalProof = text()
        val unrelated = document().copy(id = "server-uuid", bookTitle = "Renamed", chapterTitle = "Other", kind = "translation",
            organization = ExchangeOrganization("Folder", listOf("tag"), true), extensionsJson = "{\"claim\":\"provider\"}",
            position = ExchangeAnchor("empty", 0))
        assertEquals(originalProof, PortableContentProofs.compute(PortableRepresentation.TEXT, unrelated))
        listOf(document().copy(language = "KO-KR"), document().copy(paragraphs = paragraphs.reversed()),
            document().copy(paragraphs = paragraphs.mapIndexed { i, p -> if (i == 0) p.copy(text = p.text.trim()) else p }),
            document().copy(paragraphs = paragraphs.mapIndexed { i, p -> if (i == 0) p.copy(paragraphId = "different") else p })).forEach {
            assertNotEquals(originalProof.sha256, PortableContentProofs.compute(PortableRepresentation.TEXT, it).sha256)
        }
        val proof = pdfProof()
        val reference = pdfDocument().assets[1]
        listOf(reference.copy(alt = "different"), reference.copy(alt = ""), reference.copy(alt = null),
            reference.copy(paragraphId = "empty"), reference.copy(paragraphId = null)).forEach {
            assertNotEquals(proof.sha256, PortableContentProofs.compute(PortableRepresentation.PDF,
                document(listOf(ref(pdf, "pdf"), it)), listOf(pdf, image), original).sha256)
        }
        assertNotEquals(proof.sha256, PortableContentProofs.compute(PortableRepresentation.PDF,
            document(pdfDocument().assets.reversed()), listOf(image, pdf), original).sha256)
    }

    @Test fun fullOriginalBytesAndAssetMimeAreComputedNotTakenFromClaimsAndRepeatedImageBindingsStayOrdered() {
        val doc = document(listOf(ref(image, paragraph = "p:原🌏"), ref(image, paragraph = "empty", alt = "")))
        val proof = PortableContentProofs.compute(PortableRepresentation.EPUB, doc, listOf(image), original)
        assertEquals(exchangeSha256(original), proof.originalFileSha256)
        assertEquals(original.size.toLong(), proof.originalFileByteLength)
        assertEquals(2, proof.assets.size)
        assertEquals(image.sha256, proof.assets[0].sha256)
        assertNotEquals(proof.sha256, PortableContentProofs.compute(PortableRepresentation.EPUB, doc, listOf(image), original + 0).sha256)
        assertNotEquals(proof.sha256, PortableContentProofs.compute(PortableRepresentation.EPUB, doc,
            listOf(image.copy(mimeType = "image/jpeg")), original).sha256)
        val expected = proof.sha256
        original[0] = 99; image.bytes[0] = 99
        assertEquals(expected, proof.sha256) // No mutable byte payload retained in the proof.
        assertEquals(5L, proof.originalFileByteLength)
    }

    @Test fun missingDuplicateOrphanAndUnsupportedAssetsOrRepresentationsCannotProduceProof() {
        reject { PortableContentProofs.compute(PortableRepresentation.PDF, pdfDocument(), listOf(image), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.PDF, pdfDocument(), listOf(pdf, image, image), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.PDF, document(listOf(ref(pdf, "pdf"))), listOf(pdf, image), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.PDF, pdfDocument(), listOf(pdf, image), original + 0) }
        reject { PortableContentProofs.compute(PortableRepresentation.PDF, document(), emptyList(), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.PDF, document(listOf(ref(pdf, "pdf"), ref(pdf, "pdf"))), listOf(pdf), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.PDF, document(listOf(ref(pdf, "pdf", "empty"))), listOf(pdf), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document(listOf(ref(pdf, "pdf"))), listOf(pdf), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.TEXT, document(listOf(ref(image))), listOf(image)) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document(listOf(ref(image))), listOf(image)) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document(listOf(ref(image, paragraph = "missing"))), listOf(image), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document(listOf(ref(image, role = "video"))), listOf(image), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document(listOf(ref(image))), listOf(image.copy(mimeType = "IMAGE/PNG")), original) }
    }

    @Test fun invalidUnicodeAndLimitsAreRejectedWithoutNormalizingContent() {
        listOf("", "ko_KR", "ko\uD800", "a".repeat(36)).forEach { language ->
            reject { PortableContentProofs.compute(PortableRepresentation.TEXT, document().copy(language = language)) }
        }
        listOf(ExchangeParagraph("\uD800", "valid"), ExchangeParagraph("id", "\uDC00"), ExchangeParagraph("a".repeat(501), "x")).forEach {
            reject { PortableContentProofs.compute(PortableRepresentation.TEXT, document().copy(paragraphs = listOf(it))) }
        }
        reject { PortableContentProofs.compute(PortableRepresentation.TEXT, document().copy(paragraphs = listOf(paragraphs[0], paragraphs[0]))) }
        reject { PortableContentProofs.compute(PortableRepresentation.TEXT, document().copy(paragraphs = listOf(ExchangeParagraph("id", "x".repeat(5_000_001))))) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document(listOf(ref(image, alt = "\uD800"))), listOf(image), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document(listOf(ref(image, alt = "x".repeat(2001)))), listOf(image), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document(List(513) { ref(image) }), listOf(image), original) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document(), emptyList(), byteArrayOf()) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document(), emptyList(), ByteArray(LibraryExchangeLimits.ARCHIVE_BYTES + 1)) }
    }

    @Test fun exactTextAndPhysicalPdfAnchorsCannotBeConvertedOrBorrowedFromAnotherSnapshot() {
        val proof = text()
        listOf(PortableProofAnchor.Text("p:原🌏", 0), PortableProofAnchor.Text("p:原🌏", paragraphs[0].text.length),
            PortableProofAnchor.Text("empty", 0)).forEach { PortableContentProofs.validateTextAnchor(proof, paragraphs, it) }
        reject { PortableContentProofs.validateTextAnchor(proof, paragraphs, PortableProofAnchor.Text("p:原🌏", 3)) }
        reject { PortableContentProofs.validateTextAnchor(proof, paragraphs, PortableProofAnchor.Text("empty", 1)) }
        reject { PortableContentProofs.validateTextAnchor(proof, listOf(paragraphs[0].copy(text = "other"), paragraphs[1]), PortableProofAnchor.Text("empty", 0)) }
        val pdfProof = pdfProof()
        val context = VerifiedPdfContext(pdf.sha256, 2)
        PortableContentProofs.validatePdfAnchor(pdfProof, PortableProofAnchor.Pdf(pdf.sha256, 0), context)
        PortableContentProofs.validatePdfAnchor(pdfProof, PortableProofAnchor.Pdf(pdf.sha256, 1), context)
        PortableContentProofs.validateTextAnchor(pdfProof, paragraphs, PortableProofAnchor.Text("empty", 0))
        reject { PortableContentProofs.validatePdfAnchor(pdfProof, PortableProofAnchor.Pdf(pdf.sha256, 2), context) }
        reject { PortableContentProofs.validatePdfAnchor(pdfProof, PortableProofAnchor.Pdf(pdf.sha256, -1), context) }
        reject { PortableContentProofs.validatePdfAnchor(pdfProof, PortableProofAnchor.Pdf("0".repeat(64), 0), context) }
        reject { PortableContentProofs.validatePdfAnchor(pdfProof, PortableProofAnchor.Pdf(pdf.sha256, 0), context.copy(originalFileSha256 = "0".repeat(64))) }
        reject { PortableContentProofs.validatePdfAnchor(pdfProof, PortableProofAnchor.Pdf(pdf.sha256, 0), context.copy(pageCount = 0)) }
        reject { PortableContentProofs.validatePdfAnchor(proof, PortableProofAnchor.Pdf(pdf.sha256, 0), context) }
    }

    @Test fun imageOnlyPdfUsesEmptyParagraphDigestAndHasNoInventedTextAnchor() {
        val document = document(listOf(ref(pdf, "pdf"))).copy(paragraphs = emptyList())
        val proof = PortableContentProofs.compute(PortableRepresentation.PDF, document, listOf(pdf), original)
        assertEquals("d0c870bacd7b165350e7643383d14f52a6d5e53d0350b378041b9949cd653259", proof.paragraphHash)
        reject { PortableContentProofs.validateTextAnchor(proof, emptyList(), PortableProofAnchor.Text("empty", 0)) }
        reject { PortableContentProofs.compute(PortableRepresentation.TEXT, document.copy(assets = emptyList())) }
        reject { PortableContentProofs.compute(PortableRepresentation.EPUB, document.copy(assets = emptyList()), emptyList(), original) }
    }
    @Test fun anchorBoundaryRejectsCorruptOrTamperedProofMetadataIncludingSelfDigest() {
        val proof = pdfProof()
        val asset = proof.assets[1]
        val mutations = listOf(proof.copy(version = 2), proof.copy(language = "ko_KR"), proof.copy(language = "EN"),
            proof.copy(sha256 = "bad"), proof.copy(sha256 = "0".repeat(64)), proof.copy(paragraphHash = "0".repeat(64)),
            proof.copy(originalFileSha256 = null), proof.copy(originalFileByteLength = null),
            proof.copy(originalFileByteLength = -1), proof.copy(originalFileSha256 = "F".repeat(64)),
            proof.copy(assets = emptyList()), proof.copy(assets = proof.assets.reversed()),
            proof.copy(assets = listOf(proof.assets[0], asset.copy(alt = "other"))),
            proof.copy(assets = listOf(proof.assets[0], asset.copy(path = "assets/" + "0".repeat(64)))),
            proof.copy(assets = listOf(proof.assets[0], asset.copy(mimeType = "image/svg+xml"))),
            proof.copy(assets = listOf(proof.assets[0], asset.copy(byteLength = 0))),
            proof.copy(assets = listOf(proof.assets[0], asset.copy(paragraphId = "\uD800"))),
            proof.copy(assets = listOf(proof.assets[0], asset.copy(alt = "\uDC00"))),
            proof.copy(assets = proof.assets + asset.copy(byteLength = asset.byteLength + 1)),
        )
        mutations.forEach { mutated ->
            reject { PortableContentProofs.validate(mutated) }
            reject { PortableContentProofs.validateTextAnchor(mutated, paragraphs, PortableProofAnchor.Text("empty", 0)) }
            reject { PortableContentProofs.validatePdfAnchor(mutated, PortableProofAnchor.Pdf(pdf.sha256, 0), VerifiedPdfContext(pdf.sha256, 2)) }
        }
        PortableContentProofs.validate(proof)
    }

    @Test fun sharedIndependentPythonVectorsMatchExactJvmFrames() {
        val fixtures = listOf(java.io.File("contracts/fixtures/portable-content-proof-v1"),
            java.io.File("../contracts/fixtures/portable-content-proof-v1")).first { it.isDirectory }
        val vectors = java.io.File(fixtures, "vectors.tsv").readLines().associate { line ->
            line.split('\t').let { it[0] to it }
        }
        val paragraphs = listOf(ExchangeParagraph("p:😀", "안녕 😀:|\n"), ExchangeParagraph("empty", ""))
        val document = ExchangeDocument("excluded", "Excluded", "Excluded", "ko", "local", paragraphs)
        val pdf = ExchangeAsset(java.io.File(fixtures, "source.pdf").readBytes(), "application/pdf")
        val image = ExchangeAsset(java.io.File(fixtures, "image.png").readBytes(), "image/png")
        val cases = mapOf(
            "text" to PortableContentProofs.compute(PortableRepresentation.TEXT, document),
            "pdf-empty" to PortableContentProofs.compute(PortableRepresentation.PDF,
                document.copy(paragraphs = emptyList(), assets = listOf(ref(pdf, "pdf"))), listOf(pdf), pdf.bytes),
            "epub-repeated-image" to PortableContentProofs.compute(PortableRepresentation.EPUB,
                document.copy(assets = listOf(ref(image, paragraph = "p:😀"), ref(image, paragraph = "empty", alt = ""))),
                listOf(image), java.io.File(fixtures, "source.epub").readBytes()),
        )
        cases.forEach { (name, actual) ->
            val expected = vectors.getValue(name)
            assertEquals(name, expected[1], actual.sha256)
            assertEquals(name, expected[2], actual.paragraphHash)
            assertEquals(name, expected[3].takeUnless { it == "-" }, actual.originalFileSha256)
            assertEquals(name, expected[4].takeUnless { it == "-" }?.toLong(), actual.originalFileByteLength)
        }
    }

}
