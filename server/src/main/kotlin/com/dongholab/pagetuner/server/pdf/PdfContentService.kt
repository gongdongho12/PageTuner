package com.dongholab.pagetuner.server.pdf

import com.dongholab.pagetuner.core.backup.exchange.*
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class PdfContentReceipt(val recordId: UUID, val createdAt: Instant, val proof: PortableContentProof)
data class PdfContentView(val recordId: UUID, val createdAt: Instant, val content: PdfContentDocument, val proof: PortableContentProof)
data class PdfContentVerification(val recordId: UUID, val verified: Boolean, val proof: PortableContentProof)
class PdfContentFailure(val code: String, val status: Int, message: String) : RuntimeException(message)

@Service
class PdfContentService(private val jdbc: JdbcTemplate, json: ObjectMapper) {
    private val codec = PdfContentJson(json)

    @Transactional
    fun upload(user: String, request: PdfContentUpload): PdfContentReceipt {
        val validated = invalid { PdfContentValidation.validate(request) }
        val fingerprint = validated.requestFingerprint
        // Serialize only this account/request ID; hash collisions merely serialize unrelated uploads.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { row -> while (row.next()) { } },
            String(codec.bytes(listOf("pdf-content", user, request.uploadId)), Charsets.UTF_8))
        val existing = jdbc.query("select id from pdf_content_snapshot where user_id=? and upload_id=?", RowMapper { row, _ ->
            row.getObject("id", UUID::class.java)
        }, user, UUID.fromString(request.uploadId)).singleOrNull()
        if (existing != null) {
            val stored = load(user, existing)
            if (stored.requestHash != fingerprint) throw PdfContentFailure("PDF_CONTENT_UPLOAD_REUSED", 409,
                "This upload ID already identifies different content. Use a new upload ID for a separate snapshot.")
            return stored.view.receipt()
        }
        val recordId = UUID.randomUUID()
        val createdAt = Instant.now()
        val metadata = validated.content.copy(payloads = validated.content.payloads.map { it.copy(base64 = "") })
        jdbc.update("""insert into pdf_content_snapshot(id,user_id,upload_id,request_hash,metadata_json,proof_json,created_at)
            values(?,?,?,?,?,?,?)""", recordId, user, UUID.fromString(request.uploadId), fingerprint,
            String(codec.bytes(metadata), Charsets.UTF_8), String(codec.bytes(validated.proof), Charsets.UTF_8), Timestamp.from(createdAt))
        validated.content.payloads.forEachIndexed { index, payload ->
            val asset = validated.assets.single { it.path == payload.path }
            jdbc.update("insert into pdf_content_payload(snapshot_id,ordinal,path,mime_type,payload) values(?,?,?,?,?)",
                recordId, index, payload.path, payload.mimeType, asset.bytes)
        }
        // Return the database timestamp precision and verify the transaction's persisted bytes as well.
        return load(user, recordId).view.receipt()
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(user: String, recordId: UUID): PdfContentView = load(user, recordId).view

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun verify(user: String, recordId: UUID, proof: PortableContentProof): PdfContentVerification {
        invalid { PdfContentValidation.validateProof(proof) }
        val actual = load(user, recordId).view.proof
        if (proof != actual) throw PdfContentFailure("PDF_CONTENT_MISMATCH", 409, "The PDF content does not match this owned snapshot.")
        return PdfContentVerification(recordId, true, actual)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun original(user: String, recordId: UUID): ByteArray {
        val stored = load(user, recordId)
        val path = stored.view.content.assets.single { it.role == "pdf" }.path
        return stored.assets.single { it.path == path }.bytes
    }

    private data class Stored(val view: PdfContentView, val requestHash: String, val assets: List<ExchangeAsset>)
    private data class Record(val uploadId: UUID, val hash: String, val metadata: String?, val proof: String?, val createdAt: Instant)
    private data class Payload(val ordinal: Int, val path: String, val mime: String, val bytes: ByteArray?)

    private fun load(user: String, recordId: UUID): Stored {
        // SQL never transfers unexpectedly large metadata/payloads, even if a row was corrupted externally.
        val row = jdbc.query("""select upload_id,request_hash,created_at,
            case when octet_length(metadata_json)<=2097152 then metadata_json end as metadata_json,
            case when octet_length(proof_json)<=2097152 then proof_json end as proof_json
            from pdf_content_snapshot where user_id=? and id=?""", RowMapper { result, _ ->
            Record(result.getObject("upload_id", UUID::class.java), result.getString("request_hash"), result.getString("metadata_json"),
                result.getString("proof_json"), result.getTimestamp("created_at").toInstant())
        }, user, recordId).singleOrNull() ?: throw PdfContentFailure("PDF_CONTENT_NOT_FOUND", 404, "The PDF snapshot was not found.")
        val payloads = jdbc.query("""select ordinal,path,mime_type,
            case when count(*) over () <= 64 and sum(octet_length(payload)) over () <= 4194304
                and octet_length(payload) between 1 and 4194304 then payload end as payload
            from pdf_content_payload where snapshot_id=? order by ordinal limit 65""", RowMapper { result, _ ->
            Payload(result.getInt("ordinal"), result.getString("path"), result.getString("mime_type"), result.getBytes("payload"))
        }, recordId)
        // Catch only malformed persisted data. DB/SQL errors remain visible rather than disguised as content corruption.
        return try {
            val metadata = codec.content(codec.tree(requireNotNull(row.metadata).toByteArray(Charsets.UTF_8)))
            require(metadata.payloads.size == payloads.size && payloads.size in 1..64)
            val content = metadata.copy(payloads = metadata.payloads.mapIndexed { index, entry ->
                val bytes = payloads[index]
                require(entry.base64.isEmpty() && bytes.ordinal == index && bytes.path == entry.path && bytes.mime == entry.mimeType)
                entry.copy(base64 = Base64.getEncoder().encodeToString(requireNotNull(bytes.bytes)))
            })
            val validated = PdfContentValidation.validate(PdfContentUpload(row.uploadId.toString(), content))
            val savedProof = codec.proof(codec.tree(requireNotNull(row.proof).toByteArray(Charsets.UTF_8)))
            require(validated.proof == savedProof)
            require(validated.requestFingerprint == row.hash)
            Stored(PdfContentView(recordId, row.createdAt, validated.content, validated.proof), row.hash, validated.assets)
        } catch (_: IllegalArgumentException) { unavailable() }
        catch (_: com.fasterxml.jackson.core.JsonProcessingException) { unavailable() }
    }
    private fun PdfContentView.receipt() = PdfContentReceipt(recordId, createdAt, proof)
    private fun unavailable(): Nothing = throw PdfContentFailure("PDF_CONTENT_UNAVAILABLE", 409,
        "The stored PDF snapshot is incomplete or corrupt and cannot be verified.")
    private fun <T> invalid(action: () -> T): T = try { action() }
        catch (_: IllegalArgumentException) { throw PdfContentFailure("PDF_CONTENT_INVALID", 400, "Invalid PDF content snapshot.") }
}
