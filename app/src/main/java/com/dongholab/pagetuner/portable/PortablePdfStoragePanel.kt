package com.dongholab.pagetuner.portable

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl

private enum class PdfSection { Upload, Binding, Details }
private data class PdfRow(val key: String, val content: @Composable (Modifier) -> Unit)

@Composable
fun PortablePdfStoragePanel(state: PortablePdfStorageState, accountLabel: String, current: Boolean,
    onBack: () -> Unit, onUpload: () -> Unit, onRecord: (String) -> Unit, onCheck: () -> Unit,
    onBind: () -> Unit, onUnbind: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val latestBack by rememberUpdatedState(onBack)
    DisposableEffect(Unit) { onDispose { latestBack() } }
    var section by remember(state.entry?.key) { mutableStateOf(PdfSection.Upload) }
    val labels = PdfSection.entries.associateWith { stringResource(when (it) {
        PdfSection.Upload -> R.string.pdf_storage_upload_tab
        PdfSection.Binding -> R.string.pdf_storage_binding_tab
        PdfSection.Details -> R.string.pdf_storage_details_tab
    }) }
    val height = 116.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val resources = LocalResources.current
    val rows = mutableListOf<PdfRow>()
    fun field(key: String, label: Int, value: String) { rows += PdfRow(key) { row ->
        OutlinedTextField(value, {}, readOnly = true, singleLine = true, label = { Text(stringResource(label)) }, modifier = row)
    } }
    fun message(key: String, label: Int) {
        // Short paged paragraphs remain visible on narrow e-ink screens with large font scaling.
        val chunks = mutableListOf<String>()
        resources.getString(label).split(Regex("\\s+")).forEach { word ->
            if (chunks.isEmpty() || chunks.last().length + word.length + 1 > 40) chunks += word
            else chunks[chunks.lastIndex] += " $word"
        }
        chunks.forEachIndexed { index, text -> rows += PdfRow("$key:$index") { row ->
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = row)
        } }
    }
    fun action(key: String, label: Int, enabled: Boolean = true, click: () -> Unit) { rows += PdfRow(key) { row ->
        OutlinedButton(click, enabled = current && !state.busy && enabled, modifier = row.heightIn(min = 44.dp)) { Text(stringResource(label)) }
    } }
    if (!current) message("signin", R.string.pdf_storage_sign_in)
    if (state.busy) message("busy", R.string.portable_busy)
    state.error?.let { message("error", it) }
    field("account", R.string.portable_glossary_destination_account, accountLabel)
    field("title", R.string.pdf_storage_title, state.entry?.document?.bookTitle.orEmpty())
    when (section) {
        PdfSection.Upload -> {
            message("scope", R.string.pdf_storage_description)
            message("limit", R.string.pdf_storage_limit)
            field("uploadId", R.string.pdf_storage_upload_id, state.uploadId.orEmpty())
            if (state.receiptId == null) {
                if (state.attempted) message("retry_warning", R.string.pdf_storage_retry_warning)
                action("upload", if (state.attempted) R.string.pdf_storage_retry else R.string.pdf_storage_upload,
                    state.prepared, onUpload)
            } else {
                field("receipt", R.string.pdf_storage_record, state.receiptId)
                message("stored", R.string.pdf_storage_uploaded)
            }
        }
        PdfSection.Binding -> {
            message("separate", R.string.pdf_storage_binding_description)
            rows += PdfRow("record") { row -> OutlinedTextField(state.recordId, onRecord, singleLine = true,
                enabled = current && !state.busy && state.prepared, label = { Text(stringResource(R.string.pdf_storage_record)) }, modifier = row) }
            action("check", R.string.pdf_storage_check, state.prepared && state.recordId.length == 36, onCheck)
            if (state.checked) {
                message("checked", R.string.pdf_storage_checked)
                action("bind", R.string.pdf_storage_bind, click = onBind)
            }
            state.bindingRecordId?.let {
                field("linked", R.string.pdf_storage_linked_record, it)
                action("read", R.string.pdf_storage_open, click = onOpen)
                action("unlink", R.string.pdf_storage_unbind, click = onUnbind)
            }
        }
        PdfSection.Details -> state.proof?.let { proof ->
            field("localKey", R.string.pdf_storage_local_key, state.entry?.key.orEmpty())
            field("language", R.string.pdf_storage_language, proof.language)
            field("hash", R.string.pdf_storage_full_hash, proof.sha256)
            field("paragraphs", R.string.pdf_storage_paragraph_hash, proof.paragraphHash)
            field("original", R.string.pdf_storage_original_hash, proof.originalFileSha256.orEmpty())
            field("bytes", R.string.pdf_storage_bytes, proof.originalFileByteLength.toString())
            proof.assets.forEachIndexed { i, asset ->
                field("$i:path", R.string.pdf_storage_asset, "${i + 1}. ${asset.path}")
                field("$i:role", R.string.pdf_storage_role, asset.role)
                field("$i:paragraph", R.string.pdf_storage_paragraph, asset.paragraphId ?: "null")
                field("$i:alt", R.string.pdf_storage_alt, asset.alt ?: "null")
                field("$i:mime", R.string.pdf_storage_mime, asset.mimeType)
                field("$i:length", R.string.pdf_storage_bytes, asset.byteLength.toString())
                field("$i:hash", R.string.pdf_storage_full_hash, asset.sha256)
            }
        }
    }
    Column(modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onBack, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.portable_identity_back)) }
        EinkSegmentedControl(PdfSection.entries, section, { section = it }, label = { labels.getValue(it) })
        AdaptiveCollection(items = rows, modifier = Modifier.weight(1f).clipToBounds(), estimatedPagedItemHeight = height,
            itemKey = { it.key }) { row -> row.content(Modifier.fillMaxWidth().height(height).clipToBounds().padding(6.dp)) }
    }
}
