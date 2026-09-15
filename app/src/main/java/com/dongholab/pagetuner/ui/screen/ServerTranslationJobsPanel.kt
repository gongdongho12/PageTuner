package com.dongholab.pagetuner.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.translation.sync.*
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.common.EinkChoiceStepper
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkPaper

@Composable
fun ServerTranslationJobsPanel(
    state: ServerLibraryState,
    busy: Boolean,
    onDraft: (ServerJobDraft) -> Unit,
    onProvider: (String) -> Unit,
    onSubmit: () -> Unit,
    onPage: (Int) -> Unit,
    onCancel: (ServerTranslationJob) -> Unit,
    onRetry: (ServerTranslationJob) -> Unit,
    onRead: (ServerTranslationJob) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalResources.current
    var showForm by remember { mutableStateOf(state.jobSource != null) }
    LaunchedEffect(state.jobDraft.idempotencyKey, state.jobSource) {
        if (state.jobSource != null && state.latestJob?.chapterRecordId != state.jobDraft.chapterRecordId) showForm = true
    }
    LaunchedEffect(state.jobDraft.retryOf) { if (state.jobDraft.retryOf != null) showForm = true }
    Column(modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EinkSegmentedControl(listOf(true, false), showForm, { showForm = it },
            label = { strings.getString(if (it) R.string.server_job_form else R.string.server_job_list) })
        if (showForm) {
            val draft = state.jobDraft
            val retry = draft.retryOf != null
            val provider = state.providers.firstOrNull { it.id == draft.providerKind }
            Text(state.jobSource?.entry?.title ?: strings.getString(R.string.server_job_choose_original),
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            EinkChoiceStepper(state.providers.map { it.id }, draft.providerKind, onProvider,
                enabled = !busy && !retry, label = { id -> strings.getString(jobProviderLabel(id)) })
            val fields = buildList {
                add("source"); add("target")
                if (draft.providerKind in setOf("DEEPSEEK", "OPENAI_COMPATIBLE_LLM")) { add("endpoint"); add("model") }
                if (provider?.requiresKey == true) add("key")
                add("glossary")
            }
            AdaptiveCollection(items = fields, estimatedPagedItemHeight = 76.dp,
                modifier = Modifier.weight(1f), busy = busy) { field ->
                val label = when (field) {
                    "source" -> R.string.server_job_source_language
                    "target" -> R.string.server_target
                    "endpoint" -> R.string.server_job_provider_endpoint
                    "model" -> R.string.server_job_model
                    "key" -> R.string.server_job_api_key
                    else -> R.string.server_job_glossary
                }
                val value = when (field) {
                    "source" -> draft.sourceLanguage; "target" -> draft.targetLanguage; "endpoint" -> draft.endpoint
                    "model" -> draft.model; "key" -> draft.apiKey; else -> draft.glossary
                }
                OutlinedTextField(value, { changed -> onDraft(when (field) {
                    "target" -> draft.copy(targetLanguage = changed)
                    "endpoint" -> draft.copy(endpoint = changed)
                    "model" -> draft.copy(model = changed)
                    "key" -> draft.copy(apiKey = changed)
                    else -> draft.copy(glossary = changed)
                }) }, readOnly = field == "source" || (retry && field != "key"), enabled = !busy && state.jobSource != null,
                    singleLine = field != "glossary", maxLines = if (field == "glossary") 2 else 1,
                    label = { Text(strings.getString(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    visualTransformation = if (field == "key") PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().height(76.dp))
            }
            Text(strings.getString(when {
                retry -> R.string.server_job_retry_hint
                provider?.requiresKey == true -> R.string.server_job_key_hint
                else -> R.string.server_job_free_hint
            }), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = { onSubmit(); showForm = false }, enabled = !busy && state.connected && state.jobSource != null && provider != null &&
                (!provider.requiresKey || provider.configured || draft.apiKey.isNotBlank()), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(strings.getString(if (retry) R.string.server_job_retry_submit else R.string.server_job_start))
            }
        } else {
            val jobs = (listOfNotNull(state.latestJob) + state.jobs?.items.orEmpty()).distinctBy { it.jobId }
            AdaptiveCollection(items = jobs, estimatedPagedItemHeight = 176.dp,
                modifier = Modifier.weight(1f), busy = busy, itemKey = { it.jobId },
                emptyContent = { Text(strings.getString(R.string.server_job_empty)) }) { job ->
                Surface(color = EinkPaper, border = BorderStroke(1.dp, EinkLine), modifier = Modifier.fillMaxWidth().height(176.dp)) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(strings.getString(R.string.server_job_progress, strings.getString(jobStatusLabel(job.status)), job.completedParagraphs, job.totalParagraphs),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(if (job.canRetry) strings.getString(serverJobFailureLabel(job.errorCode))
                            else "${job.settings.sourceLanguage} → ${job.targetLanguage} · ${strings.getString(jobProviderLabel(job.providerKind))}",
                            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (job.active) OutlinedButton(onClick = { onCancel(job) }, enabled = !busy, modifier = Modifier.weight(1f).height(44.dp)) {
                                Text(strings.getString(R.string.server_job_cancel))
                            }
                            if (job.canRetry) OutlinedButton(onClick = { onRetry(job); showForm = true }, enabled = !busy, modifier = Modifier.weight(1f).height(44.dp)) {
                                Text(strings.getString(R.string.server_job_retry))
                            }
                            if (job.status == "COMPLETED") OutlinedButton(onClick = { onRead(job) }, enabled = !busy, modifier = Modifier.weight(1f).height(44.dp)) {
                                Text(strings.getString(R.string.server_read))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val page = state.jobs
                OutlinedButton(onClick = { onPage((page?.page ?: 0) - 1) }, enabled = !busy && (page?.page ?: 0) > 0,
                    modifier = Modifier.weight(1f).height(48.dp)) { Text(strings.getString(R.string.action_previous)) }
                OutlinedButton(onClick = { onPage(page?.page ?: 0) }, enabled = !busy && state.connected,
                    modifier = Modifier.weight(1.4f).height(48.dp)) { Text(strings.getString(R.string.server_refresh_page, (page?.page ?: 0) + 1, page?.totalPages?.coerceAtLeast(1) ?: 1)) }
                OutlinedButton(onClick = { onPage((page?.page ?: 0) + 1) }, enabled = !busy && page?.hasNext == true,
                    modifier = Modifier.weight(1f).height(48.dp)) { Text(strings.getString(R.string.action_next)) }
            }
        }
    }
}

/** Never display provider exception text or an unrecognized server error code. */
internal fun serverJobFailureLabel(code: String?) = when (code) {
    "PROVIDER_CREDENTIALS", "AUTHENTICATION", "PROVIDER_NOT_CONFIGURED" -> R.string.server_job_error_credentials
    "RATE_LIMIT", "QUOTA_EXCEEDED" -> R.string.server_job_error_limit
    "WORKER_LEASE_EXPIRED", "INTERRUPTED" -> R.string.server_job_error_interrupted
    else -> R.string.server_job_error_provider
}

private fun jobStatusLabel(status: String) = when (status) {
    "QUEUED" -> R.string.server_job_queued
    "RUNNING" -> R.string.server_job_running
    "COMPLETED" -> R.string.server_job_completed
    "FAILED" -> R.string.server_job_failed
    "CANCELLED" -> R.string.server_job_cancelled_status
    else -> R.string.server_job_interrupted
}

private fun jobProviderLabel(provider: String) = when (provider) {
    "GOOGLE_WEB_TRANSLATE_HTML" -> R.string.provider_google_web_translate_html
    "GOOGLE_CLOUD" -> R.string.provider_google_cloud
    "DEEPSEEK" -> R.string.provider_deepseek
    else -> R.string.provider_openai_compatible_llm
}
