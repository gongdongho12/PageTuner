package com.dongholab.pagetuner.server.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class StoredJob(val view: TranslationJobView, val configuration: JobConfiguration, val requestHash: String, val user: String)

@Repository
class TranslationJobStore(private val jdbc: JdbcTemplate, private val json: ObjectMapper) {
    fun countActive(user: String): Long = jdbc.queryForObject("select count(*) from translation_job where user_id=? and status in ('QUEUED','RUNNING')", Long::class.java, user)!!
    fun lock(user: String, requestHash: String) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", { row -> while (row.next()) {} }, "workflow:$user:$requestHash")
    }
    fun byIdempotency(user: String, id: UUID): StoredJob? = query("j.user_id=? and (j.idempotency_key=? or exists(select 1 from translation_job_submission s where s.user_id=j.user_id and s.job_id=j.id and s.idempotency_key=?))", user, id, id).singleOrNull()
    fun bindIdempotency(user: String, id: UUID, jobId: UUID) {
        jdbc.update("insert into translation_job_submission(user_id,idempotency_key,job_id) values(?,?,?)", user, id, jobId)
    }
    fun reusable(user: String, hash: String): StoredJob? = query("j.user_id=? and j.request_hash=? and j.status in ('QUEUED','RUNNING','COMPLETED') order by j.created_at desc limit 1", user, hash).singleOrNull()
    fun get(user: String, id: UUID): StoredJob = query("j.user_id=? and j.id=?", user, id).singleOrNull()
        ?: throw WorkflowFailure("JOB_NOT_FOUND", 404, "번역 작업을 찾을 수 없습니다.")
    fun create(user: String, idempotency: UUID, hash: String, chapter: StoredChapter, config: JobConfiguration, workerId: UUID): StoredJob {
        val id = UUID.randomUUID()
        jdbc.update("""
            insert into translation_job(id,user_id,idempotency_key,request_hash,chapter_record_id,provider_kind,target_language,
                settings_json,worker_id,lease_until,status,total_paragraphs) values(?,?,?,?,?,?,?,?,?,now()+interval '2 minutes','QUEUED',?)
        """.trimIndent(), id, user, idempotency, hash, chapter.recordId, config.providerKind, config.targetLanguage,
            json.writeValueAsString(config), workerId, chapter.paragraphs.size)
        return get(user, id)
    }
    fun copyCheckpoints(previousId: UUID, nextId: UUID) {
        jdbc.update("insert into translation_job_paragraph(job_id,paragraph_id,translated_text) select ?,paragraph_id,translated_text from translation_job_paragraph where job_id=?", nextId, previousId)
        jdbc.update("update translation_job set completed_paragraphs=(select count(*) from translation_job_paragraph where job_id=?) where id=?", nextId, nextId)
    }
    fun checkpoints(id: UUID): Map<String, String> = jdbc.query("select paragraph_id,translated_text from translation_job_paragraph where job_id=?",
        RowMapper { row, _ -> row.getString(1) to row.getString(2) }, id).toMap()
    fun start(id: UUID, workerId: UUID): Boolean = jdbc.update("update translation_job set status='RUNNING',updated_at=now(),lease_until=now()+interval '2 minutes' where id=? and worker_id=? and status='QUEUED'", id, workerId) == 1
    fun heartbeat(id: UUID, workerId: UUID): Boolean = jdbc.update("update translation_job set lease_until=now()+interval '2 minutes' where id=? and worker_id=? and status in ('QUEUED','RUNNING')", id, workerId) == 1
    @Transactional
    fun checkpoint(id: UUID, workerId: UUID, paragraphId: String, text: String): Boolean {
        val active = jdbc.queryForObject("select count(*) from (select id from translation_job where id=? and worker_id=? and status='RUNNING' for update) active", Int::class.java, id, workerId) == 1
        if (!active) return false
        jdbc.update("insert into translation_job_paragraph(job_id,paragraph_id,translated_text) values(?,?,?) on conflict(job_id,paragraph_id) do update set translated_text=excluded.translated_text", id, paragraphId, text)
        jdbc.update("update translation_job set completed_paragraphs=(select count(*) from translation_job_paragraph where job_id=?),updated_at=now() where id=?", id, id)
        return true
    }
    fun lockActive(user: String, id: UUID, workerId: UUID): Boolean = jdbc.queryForObject("select count(*) from (select id from translation_job where user_id=? and id=? and worker_id=? and status='RUNNING' for update) active", Int::class.java, user, id, workerId) == 1
    fun complete(id: UUID, recordId: UUID) {
        jdbc.update("""update translation_artifact a set book_title=c.book_title,chapter_title=c.chapter_title
            from source_chapter c join translation_job j on j.chapter_record_id=c.id
            where j.id=? and a.id=? and a.user_id=j.user_id""", id, recordId)
        jdbc.update("update translation_job set status='COMPLETED',translation_record_id=?,completed_paragraphs=total_paragraphs,updated_at=now() where id=? and status='RUNNING'", recordId, id)
    }
    fun fail(id: UUID, workerId: UUID, code: String, message: String) {
        jdbc.update("update translation_job set status='FAILED',error_code=?,error_message=?,updated_at=now() where id=? and worker_id=? and status in ('QUEUED','RUNNING')", code, message, id, workerId)
    }
    fun cancel(user: String, id: UUID): TranslationJobView {
        get(user, id)
        jdbc.update("update translation_job set status='CANCELLED',updated_at=now() where user_id=? and id=? and status in ('QUEUED','RUNNING')", user, id)
        return get(user, id).view
    }
    fun interruptOwned(workerId: UUID) {
        jdbc.update("update translation_job set status='INTERRUPTED',error_code='SERVER_STOPPED',error_message='서버가 종료되었습니다. 번역을 다시 시도해 주세요.',updated_at=now() where worker_id=? and status in ('QUEUED','RUNNING')", workerId)
    }
    fun recoverExpired() {
        jdbc.update("update translation_job set status='INTERRUPTED',error_code='WORKER_INTERRUPTED',error_message='번역 작업이 중단되었습니다. 완료된 문단부터 다시 시도할 수 있습니다.',updated_at=now() where status in ('QUEUED','RUNNING') and lease_until<now()")
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(user: String, page: Int, size: Int): WorkflowPage<TranslationJobView> {
        validateWorkflowPage(page, size)
        val count = jdbc.queryForObject("select count(*) from translation_job where user_id=?", Long::class.java, user)!!
        return WorkflowPage(query("j.user_id=? order by j.created_at desc,j.id desc limit ? offset ?", user, size, page.toLong() * size).map { it.view }, page, size, count)
    }
    private fun query(where: String, vararg args: Any): List<StoredJob> = jdbc.query("select j.*,c.book_title,c.chapter_title from translation_job j join source_chapter c on c.id=j.chapter_record_id where $where", mapper, *args)
    private val mapper = RowMapper { row: ResultSet, _: Int -> StoredJob(
        TranslationJobView(row.getObject("id", UUID::class.java), row.getString("status"), row.getObject("chapter_record_id", UUID::class.java),
            row.getString("book_title"), row.getString("chapter_title"), row.getString("provider_kind"), row.getString("target_language"),
            row.getInt("completed_paragraphs"), row.getInt("total_paragraphs"), row.getObject("translation_record_id", UUID::class.java),
            row.getString("error_code"), row.getString("error_message"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
            json.readValue(row.getString("settings_json"), JobConfiguration::class.java).let { TranslationRetrySettings(it.sourceLanguage, it.targetLanguage, it.endpoint, it.model, it.glossary) }),
        json.readValue(row.getString("settings_json"), JobConfiguration::class.java), row.getString("request_hash"), row.getString("user_id")) }
}
