package com.dongholab.pagetuner.ui.text

import android.content.Context
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.document.UnsupportedReaderDocumentException
import com.dongholab.pagetuner.source.WebCatalogStatus
import com.dongholab.pagetuner.translation.ProviderHealthCheck
import com.dongholab.pagetuner.translation.ProviderHealthState
import com.dongholab.pagetuner.translation.TranslationProviderErrorKind
import com.dongholab.pagetuner.translation.TranslationProviderFailure
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationQueueState
import com.dongholab.pagetuner.translation.TranslationStatus
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun TranslationStatus.localizedMessage(context: Context): String {
    return when (this) {
        TranslationStatus.Ready -> context.getString(R.string.status_ready)
        TranslationStatus.LoadedCached -> context.getString(R.string.status_loaded_cached)
        TranslationStatus.NoCached -> context.getString(R.string.status_no_cached)
        TranslationStatus.ServedFromCache -> context.getString(R.string.status_served_from_cache)
        TranslationStatus.PreparingOfflineCache -> context.getString(R.string.status_preparing_offline_cache)
        TranslationStatus.OfflineCacheReady -> context.getString(R.string.status_offline_cache_ready)
        TranslationStatus.PrefetchPaused -> context.getString(R.string.status_prefetch_paused)
        TranslationStatus.PrefetchCancelled -> context.getString(R.string.status_prefetch_cancelled)
        is TranslationStatus.Starting -> context.getString(
            R.string.status_starting_translation,
            paceMode.localizedLabel(context).lowercase(),
        )
        is TranslationStatus.CachedSegments -> context.getString(
            R.string.status_cached_segments,
            cachedSegments,
            totalSegments,
        )
        is TranslationStatus.TranslatedSegments -> context.getString(
            R.string.status_translated_segments,
            completedSegments,
            totalSegments,
        )
        is TranslationStatus.TranslatedSavedPage -> context.getString(
            R.string.status_translated_saved_page,
            pageNumber,
        )
        is TranslationStatus.PrefetchPreparingPage -> context.getString(
            R.string.status_prefetch_preparing_page,
            activePageNumber,
            totalPages,
        )
        is TranslationStatus.PrefetchSavedPage -> context.getString(
            R.string.status_prefetch_saved_page,
            activePageNumber,
            totalPages,
        )
        is TranslationStatus.PrefetchFailedPage -> context.getString(
            R.string.status_prefetch_failed_page,
            pageNumber,
            providerFailure?.localizedMessage(context)
                ?: detail?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.status_generic_error),
        )
        is TranslationStatus.PrefetchCompletedWithFailures -> context.getString(
            R.string.status_prefetch_completed_with_failures,
            failedPages,
            totalPages,
        )
        is TranslationStatus.RetryingPage -> context.getString(
            R.string.status_retrying_translation_page,
            pageNumber,
            attemptNumber,
        )
        is TranslationStatus.ClearedCache -> context.getString(
            R.string.status_cleared_translation_cache,
            deletedSegments,
        )
        is TranslationStatus.Error -> context.getString(
            R.string.status_translation_error,
            providerFailure?.localizedMessage(context)
                ?: detail?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.status_generic_error),
        )
    }
}

fun TranslationProviderFailure.localizedMessage(context: Context): String {
    val detailText = detail?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.status_generic_error)
    val messageRes = when (kind) {
        TranslationProviderErrorKind.Authentication -> R.string.provider_error_authentication
        TranslationProviderErrorKind.RateLimited -> R.string.provider_error_rate_limited
        TranslationProviderErrorKind.Quota -> R.string.provider_error_quota
        TranslationProviderErrorKind.BadRequest -> R.string.provider_error_bad_request
        TranslationProviderErrorKind.Server -> R.string.provider_error_server
        TranslationProviderErrorKind.Network -> R.string.provider_error_network
        TranslationProviderErrorKind.ResponseFormat -> R.string.provider_error_response_format
        TranslationProviderErrorKind.Configuration -> R.string.provider_error_configuration
        TranslationProviderErrorKind.Unknown -> R.string.provider_error_unknown
    }
    return context.getString(messageRes, providerName, detailText)
}

fun ProviderHealthCheck.localizedMessage(context: Context): String {
    return when (state) {
        ProviderHealthState.NotChecked -> context.getString(R.string.provider_health_not_checked)
        ProviderHealthState.Ready -> context.getString(R.string.provider_health_ready)
        ProviderHealthState.MissingConfiguration -> when (providerKind) {
            TranslationProviderKind.GOOGLE_CLOUD ->
                context.getString(R.string.provider_health_missing_google_key)
            TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML ->
                context.getString(R.string.provider_health_google_web_no_key_required)
            TranslationProviderKind.OPENAI_COMPATIBLE_LLM ->
                context.getString(R.string.provider_health_missing_llm_settings)
            null -> context.getString(R.string.provider_health_missing_settings)
        }
        ProviderHealthState.InvalidConfiguration ->
            context.getString(R.string.provider_health_invalid_llm_endpoint)
    }
}

fun WebCatalogStatus.localizedMessage(context: Context): String {
    return when (this) {
        WebCatalogStatus.Idle -> context.getString(R.string.status_web_catalog_idle)
        WebCatalogStatus.Loading -> context.getString(R.string.status_web_catalog_loading)
        WebCatalogStatus.MissingCatalogUrl ->
            context.getString(R.string.status_web_catalog_missing_url)
        is WebCatalogStatus.LoadedRemote -> if (currentPage != null && totalPages != null && totalItems == null) {
            context.getString(
                R.string.status_web_catalog_loaded_search_page,
                title,
                currentPage,
                totalPages,
                itemCount,
            )
        } else if (currentPage != null && totalPages != null) {
            context.getString(
                R.string.status_web_catalog_loaded_remote_page,
                title,
                currentPage,
                totalPages,
                itemCount,
                totalItems ?: itemCount,
            )
        } else {
            context.getString(
                R.string.status_web_catalog_loaded_remote,
                title,
                itemCount,
            )
        }
        is WebCatalogStatus.LoadedCached -> context.getString(
            R.string.status_web_catalog_loaded_cached,
            title,
            itemCount,
        )
        is WebCatalogStatus.Importing -> context.getString(
            R.string.status_web_catalog_importing,
            title,
        )
        is WebCatalogStatus.Downloaded -> context.getString(
            R.string.status_web_catalog_downloaded,
            title,
        )
        is WebCatalogStatus.SavedAccount -> context.getString(
            R.string.status_remote_source_account_saved,
            title,
        )
        is WebCatalogStatus.DeletedAccount -> context.getString(
            R.string.status_remote_source_account_deleted,
            title,
        )
        is WebCatalogStatus.Error -> context.getString(
            R.string.status_web_catalog_error,
            detail?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.status_generic_error),
        )
        is WebCatalogStatus.NetworkUnavailable -> context.getString(
            R.string.status_network_unavailable,
            detail?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.status_generic_error),
        )
    }
}

fun TranslationQueueState.localizedMessage(context: Context): String {
    return when {
        running && paused -> context.getString(
            R.string.translation_queue_paused,
            completedPages,
            totalPages,
            activePageNumber ?: 0,
        )
        running -> context.getString(
            R.string.translation_queue_running,
            completedPages,
            totalPages,
            activePageNumber ?: 0,
        )
        cancelled -> context.getString(R.string.translation_queue_cancelled)
        failedPages > 0 -> context.getString(
            R.string.translation_queue_failed,
            failedPages,
            totalPages,
        )
        totalPages > 0 && completedPages == totalPages -> context.getString(
            R.string.translation_queue_complete,
            completedPages,
        )
        else -> context.getString(R.string.translation_queue_idle)
    }
}

fun Throwable.readableMessage(context: Context): String {
    return context.readableMessage(this)
}

fun Context.readableMessage(detail: String?): String {
    val safeDetail = detail?.takeIf { it.isNotBlank() }
        ?: getString(R.string.status_generic_error)
    return getString(R.string.status_generic_operation_error, safeDetail)
}

fun Context.readableMessage(error: Throwable): String {
    val safeDetail = error.message?.takeIf { it.isNotBlank() }
        ?: getString(R.string.status_generic_error)
    return when (error) {
        is UnsupportedReaderDocumentException ->
            getString(R.string.status_unsupported_format, safeDetail)
        is UnknownHostException,
        is SocketTimeoutException,
        is SocketException ->
            getString(R.string.status_network_unavailable, safeDetail)
        is IOException ->
            getString(R.string.status_import_failed, safeDetail)
        else -> getString(R.string.status_generic_operation_error, safeDetail)
    }
}

fun settingsProviderConfigured(
    providerKind: TranslationProviderKind,
    apiKey: String,
    llmEndpoint: String,
    llmModel: String,
): Boolean {
    return when (providerKind) {
        TranslationProviderKind.GOOGLE_CLOUD -> apiKey.isNotBlank()
        TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML -> true
        TranslationProviderKind.OPENAI_COMPATIBLE_LLM ->
            apiKey.isNotBlank() && llmEndpoint.isNotBlank() && llmModel.isNotBlank()
    }
}
