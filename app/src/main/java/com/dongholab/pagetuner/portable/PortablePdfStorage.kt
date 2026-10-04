package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PortablePdfStorageState(val entry: PortableLibraryEntry? = null, val session: Long = 0, val busy: Boolean = false,
    val prepared: Boolean = false, val uploadId: String? = null, val attempted: Boolean = false,
    val receiptId: String? = null, val recordId: String = "", val checked: Boolean = false,
    val bindingRecordId: String? = null, val proof: PortableContentProof? = null, val error: Int? = null)

/** Explicit storage and device association only. Preview never opens a text sync actor or sends a mutation. */
class PortablePdfStorage(private val scope: CoroutineScope,
    private val currentContent: suspend (PortableLibraryEntry) -> ValidatedPdfContent,
    private val bindings: PortablePdfBindingStore,
    private val openLocal: suspend (PortableLibraryEntry, LibraryExchangePackage, () -> Unit) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    private data class Intent(val request: PdfContentUpload, val proof: PortableContentProof, val binding: PortablePdfBindingSnapshot)
    private val gate = Any()
    private val mutableState = MutableStateFlow(PortablePdfStorageState())
    val state = mutableState.asStateFlow()
    private var connection: PdfStorageConnection? = null
    private var generation = 0L
    private var job: Job? = null
    private var intent: Intent? = null
    private var checkedRecord: String? = null

    fun connect(value: PdfStorageConnection?) = synchronized(gate) {
        if (connection !== value) { connection = value; close() }
    }
    fun close() = synchronized(gate) {
        generation++; job?.cancel(); job = null; intent = null; checkedRecord = null
        mutableState.value = PortablePdfStorageState()
    }
    fun close(expectedSession: Long) = synchronized(gate) { if (state.value.session == expectedSession) close() }
    fun matches(value: PdfStorageConnection?) = synchronized(gate) { value != null && connection === value }

    fun select(entry: PortableLibraryEntry, value: PdfStorageConnection?, current: () -> PdfStorageConnection?) {
        connect(value); close()
        mutableState.value = PortablePdfStorageState(entry = entry, session = generation)
        if (value == null) { mutableState.value = mutableState.value.copy(error = R.string.pdf_storage_sign_in); return }
        run(state.value.session, current) { selected, account, guard ->
            val prepared = currentContent(selected); guard()
            val previous = withContext(io) { bindings.inspect(account.accountKey, account.origin, selected, prepared.proof) }; guard()
            val request = PdfContentUpload(UUID.randomUUID().toString(), prepared.content)
            synchronized(gate) {
                guard(); intent = Intent(request, prepared.proof, previous)
                mutableState.value = mutableState.value.copy(prepared = true, uploadId = request.uploadId,
                    recordId = previous.binding?.recordId.orEmpty(), bindingRecordId = previous.binding?.recordId,
                    proof = prepared.proof.copy(assets = prepared.proof.assets.map { it.copy() }))
            }
        }
    }

    fun updateRecord(value: String, expectedSession: Long = state.value.session) = synchronized(gate) {
        if (expectedSession == state.value.session && !state.value.busy && value.length <= 36) {
            checkedRecord = null; mutableState.value = state.value.copy(recordId = value, checked = false, error = null)
        }
    }

    fun upload(expectedSession: Long = state.value.session, current: () -> PdfStorageConnection?) {
        val captured = synchronized(gate) { intent.takeIf { state.value.receiptId == null } } ?: return
        run(expectedSession, current) { entry, account, guard ->
            require(currentContent(entry).proof == captured.proof); guard()
            withContext(io) { require(bindings.inspect(account.accountKey, account.origin, entry, captured.proof) == captured.binding) }; guard()
            mutableState.value = state.value.copy(attempted = true)
            val receipt = account.client.upload(captured.request); guard()
            require(receipt.proof == captured.proof)
            require(currentContent(entry).proof == captured.proof); guard()
            withContext(io) { require(bindings.inspect(account.accountKey, account.origin, entry, captured.proof) == captured.binding) }; guard()
            checkedRecord = null
            mutableState.value = state.value.copy(receiptId = receipt.recordId, recordId = receipt.recordId, checked = false)
        }
    }

    fun check(expectedSession: Long = state.value.session, current: () -> PdfStorageConnection?) {
        val captured = synchronized(gate) { intent } ?: return
        val recordId = state.value.recordId
        run(expectedSession, current) { entry, account, guard ->
            checkedRecord = null; mutableState.value = state.value.copy(checked = false)
            verify(account, recordId, captured.proof); guard()
            require(currentContent(entry).proof == captured.proof); guard()
            withContext(io) { require(bindings.inspect(account.accountKey, account.origin, entry, captured.proof) == captured.binding) }; guard()
            checkedRecord = recordId; mutableState.value = state.value.copy(checked = true)
        }
    }

    fun bind(expectedSession: Long = state.value.session, current: () -> PdfStorageConnection?) {
        val captured = synchronized(gate) { intent } ?: return
        val recordId = synchronized(gate) { checkedRecord } ?: return
        run(expectedSession, current) { entry, account, guard ->
            checkedRecord = null; mutableState.value = state.value.copy(checked = false)
            verify(account, recordId, captured.proof); guard()
            require(currentContent(entry).proof == captured.proof); guard()
            val saved = withContext(io) { synchronized(gate) { account.current {
                guard(); bindings.commit(account.accountKey, account.origin, entry, captured.proof, captured.binding, recordId, guard)
            } } }; guard()
            intent = captured.copy(binding = saved)
            mutableState.value = state.value.copy(bindingRecordId = recordId)
        }
    }

    fun unbind(expectedSession: Long = state.value.session, current: () -> PdfStorageConnection?) {
        val captured = synchronized(gate) { intent?.takeIf { it.binding.binding != null } } ?: return
        run(expectedSession, current) { entry, account, guard ->
            require(currentContent(entry).proof == captured.proof); guard()
            val saved = withContext(io) { synchronized(gate) { account.current {
                guard(); bindings.commit(account.accountKey, account.origin, entry, captured.proof, captured.binding, null, guard)
            } } }; guard()
            intent = captured.copy(binding = saved); checkedRecord = null
            mutableState.value = state.value.copy(bindingRecordId = null, checked = false)
        }
    }

    fun open(expectedSession: Long = state.value.session, current: () -> PdfStorageConnection?) {
        val captured = synchronized(gate) { intent?.takeIf { it.binding.binding != null } } ?: return
        run(expectedSession, current) { entry, account, guard ->
            val linked = requireNotNull(captured.binding.binding)
            require(linked.proof == captured.proof)
            verify(account, linked.recordId, captured.proof); guard()
            require(currentContent(entry).proof == captured.proof); guard()
            val archive = withContext(io) { synchronized(gate) { account.current {
                guard(); bindings.verified(account.accountKey, account.origin, entry, captured.proof, captured.binding, guard) { it }
            } } }; guard()
            // Decoder consumes only the local original file. Check again after decoding and immediately before emitting.
            openLocal(entry, archive) { synchronized(gate) { account.current {
                guard(); bindings.verified(account.accountKey, account.origin, entry, captured.proof, captured.binding, guard) { }
            } } }
            guard()
        }
    }

    private suspend fun verify(account: PdfStorageConnection, recordId: String, proof: PortableContentProof) {
        PdfContentValidation.validateUuid(recordId)
        val remote = account.client.get(recordId)
        // Defend this boundary even when an injected client does not use the strict HTTP codec.
        require(remote.recordId == recordId && remote.proof == proof && PdfContentValidation.validateContent(remote.content).proof == proof)
    }

    private fun run(expectedSession: Long, current: () -> PdfStorageConnection?, work: suspend (PortableLibraryEntry, PdfStorageConnection, () -> Unit) -> Unit) {
        synchronized(gate) {
            if (expectedSession != state.value.session || state.value.busy) return
            val entry = state.value.entry ?: return
            val account = connection ?: return
            val ticket = generation
            val guard = { synchronized(gate) {
                if (generation != ticket || connection !== account || current() !== account || state.value.entry?.key != entry.key)
                    throw CancellationException("PDF selection expired.")
                account.current { }
            }; Unit }
            mutableState.value = state.value.copy(busy = true, error = null)
            job = scope.launch {
                try { guard(); work(entry, account, guard) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { synchronized(gate) {
                    if (ticket == generation) {
                        checkedRecord = null
                        mutableState.value = state.value.copy(checked = false, error = when (error) {
                            is PdfContentClientException -> when (error.failure) {
                                PdfContentClientFailure.AUTHENTICATION, PdfContentClientFailure.FORBIDDEN -> R.string.pdf_storage_sign_in
                                PdfContentClientFailure.NETWORK, PdfContentClientFailure.TIMEOUT, PdfContentClientFailure.SERVER -> R.string.pdf_storage_network
                                else -> R.string.pdf_storage_rejected
                            }
                            else -> R.string.pdf_storage_changed
                        })
                    }
                } }
                finally { synchronized(gate) { if (ticket == generation) mutableState.value = state.value.copy(busy = false) } }
            }
        }
    }
}
