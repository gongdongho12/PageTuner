package com.dongholab.pagetuner.portable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PortableIdentityState(
    val entry: PortableLibraryEntry? = null,
    val identity: DocumentIdentity? = null,
    val recordId: String = "",
    val session: Long = 0,
    val busy: Boolean = false,
    val verified: Boolean = false,
    val message: Int = R.string.portable_identity_ready,
)

/** Read-only and deliberately ephemeral: a successful check never creates an account binding. */
class PortableIdentityVerification(
    private val scope: CoroutineScope,
    private val validationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val verify: suspend (ServerReadingConnection, String, DocumentIdentity) -> Unit = { connection, record, identity ->
        connection.client.verifyPortableIdentity(record, identity)
    },
) {
    private val mutableState = MutableStateFlow(PortableIdentityState())
    val state = mutableState.asStateFlow()
    private var connection: ServerReadingConnection? = null
    private var operation: Job? = null
    private var generation = 0L

    fun matches(value: ServerReadingConnection?): Boolean = connection?.accountKey == value?.accountKey && connection?.client === value?.client

    fun connect(value: ServerReadingConnection?) {
        if (matches(value)) return
        val selected = state.value.entry
        if (selected != null) { select(selected, value); return }
        connection = value
        invalidate()
        mutableState.value = state.value.copy(session = generation, busy = false, verified = false, message = readyMessage(state.value.identity))
    }

    fun select(entry: PortableLibraryEntry, value: ServerReadingConnection?) {
        connection = value
        invalidate()
        val ticket = generation
        mutableState.value = PortableIdentityState(entry = entry, session = ticket, busy = true, message = R.string.portable_busy)
        operation = scope.launch {
            val (inspected, hint) = withContext(validationDispatcher) { inspect(entry.document) to recordHint(entry.document) }
            if (generation == ticket && matches(value)) mutableState.value = state.value.copy(
                identity = inspected.first, recordId = hint, busy = false,
                verified = false, message = inspected.second ?: readyMessage(inspected.first))
        }
    }

    fun close() {
        invalidate()
        mutableState.value = PortableIdentityState(session = generation)
    }

    fun updateRecord(session: Long, value: String) {
        if (state.value.session != session) return
        invalidate()
        mutableState.value = state.value.copy(recordId = value, session = generation, busy = false,
            verified = false, message = readyMessage(state.value.identity))
    }

    fun check(session: Long, value: ServerReadingConnection?, currentDocument: suspend (PortableLibraryEntry) -> ExchangeDocument) {
        if (!matches(value)) { connect(value); return }
        val snapshot = state.value
        if (snapshot.session != session || snapshot.busy || snapshot.identity == null) return
        val currentConnection = connection ?: return
        val entry = snapshot.entry ?: return
        if (!canonicalUuid(snapshot.recordId)) {
            mutableState.value = snapshot.copy(message = R.string.portable_identity_record_invalid)
            return
        }
        val ticket = generation
        mutableState.value = snapshot.copy(busy = true, message = R.string.portable_identity_checking)
        operation = scope.launch {
            val message = try {
                val identity = withContext(validationDispatcher) {
                    val document = currentDocument(entry)
                    requireNotNull(DocumentIdentityJson.fromDocument(document)).also {
                        require(it == snapshot.identity) { "The selected document changed." }
                    }
                }
                verify(currentConnection, snapshot.recordId, identity)
                R.string.portable_identity_verified
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: PortableIdentityVerificationException) {
                when (error.reason) {
                    PortableIdentityFailure.Mismatch -> R.string.portable_identity_mismatch
                    PortableIdentityFailure.Unavailable -> R.string.portable_identity_unavailable
                    PortableIdentityFailure.NotFound -> R.string.portable_identity_not_found
                }
            } catch (error: TranslationStoreException) {
                when (error.failure) {
                    TranslationStoreFailure.AUTHENTICATION, TranslationStoreFailure.FORBIDDEN -> R.string.portable_identity_sign_in
                    TranslationStoreFailure.NOT_FOUND -> R.string.portable_identity_not_found
                    else -> R.string.portable_identity_network
                }
            } catch (_: Exception) { R.string.portable_identity_invalid }
            if (generation == ticket && matches(currentConnection)) {
                mutableState.value = state.value.copy(busy = false, verified = message == R.string.portable_identity_verified, message = message)
            }
        }
    }

    fun bind(session: Long, value: ServerReadingConnection?, currentDocument: suspend (PortableLibraryEntry) -> ExchangeDocument,
        save: suspend (PortableServerBinding) -> Unit) {
        if (!matches(value)) { connect(value); return }
        val snapshot = state.value
        val currentConnection = connection ?: return
        val entry = snapshot.entry ?: return
        val identity = snapshot.identity ?: return
        if (snapshot.session != session || snapshot.busy || !snapshot.verified) return
        val ticket = generation
        mutableState.value = snapshot.copy(busy = true, verified = false, message = R.string.portable_identity_checking)
        operation = scope.launch {
            val message = try {
                val document = currentDocument(entry)
                require(document.assets.isEmpty() && DocumentIdentityJson.fromDocument(document) == identity)
                verify(currentConnection, snapshot.recordId, identity)
                if (generation != ticket || !matches(currentConnection)) return@launch
                save(PortableServerBinding(currentConnection.accountKey, entry.key, snapshot.recordId, identity))
                R.string.portable_identity_bound
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { R.string.portable_identity_binding_failed }
            if (generation == ticket && matches(currentConnection)) mutableState.value = state.value.copy(busy = false, message = message)
        }
    }

    fun unbind(session: Long, value: ServerReadingConnection?, remove: (String, PortableLibraryEntry) -> Unit) {
        if (!matches(value)) { connect(value); return }
        val snapshot = state.value
        val current = connection ?: return
        val entry = snapshot.entry ?: return
        if (snapshot.session != session || snapshot.busy) return
        try {
            remove(current.accountKey, entry)
            invalidate()
            mutableState.value = snapshot.copy(session = generation, verified = false, message = R.string.portable_identity_unbound)
        } catch (_: Exception) { mutableState.value = snapshot.copy(message = R.string.portable_identity_binding_failed) }
    }

    private fun invalidate() { generation++; operation?.cancel(); operation = null }
    private fun readyMessage(identity: DocumentIdentity?) = when {
        identity == null -> state.value.message
        connection == null -> R.string.portable_identity_sign_in
        else -> R.string.portable_identity_ready
    }

    companion object {
        internal fun canonicalUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        internal fun recordHint(document: ExchangeDocument): String = runCatching {
            val extensions = JSONObject(document.extensionsJson ?: "{}")
            val hint = if (extensions.has("serverRecordId")) extensions.opt("serverRecordId") as? String
                else extensions.optJSONObject("server")?.opt("recordId") as? String
            hint?.takeIf(::canonicalUuid).orEmpty()
        }.getOrDefault("")
        internal fun inspect(document: ExchangeDocument): Pair<DocumentIdentity?, Int?> {
            if (document.assets.isNotEmpty()) return null to R.string.portable_identity_assets
            return try {
                val identity = DocumentIdentityJson.fromDocument(document)
                identity to if (identity == null) R.string.portable_identity_legacy else null
            } catch (_: Exception) { null to R.string.portable_identity_invalid }
        }
    }
}

class PortableIdentityViewModel : ViewModel() {
    val verification = PortableIdentityVerification(viewModelScope)
}
