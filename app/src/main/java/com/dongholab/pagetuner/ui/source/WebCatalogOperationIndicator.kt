package com.dongholab.pagetuner.ui.source

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.source.CatalogTranslationProgress
import com.dongholab.pagetuner.source.WebCatalogLoadPhase
import com.dongholab.pagetuner.source.WebCatalogLoading
import com.dongholab.pagetuner.ui.common.EinkOperationIndicator

@Composable
fun WebCatalogOperationIndicator(
    loading: WebCatalogLoading?,
    translationProgress: CatalogTranslationProgress?,
    busy: Boolean,
    statusText: String?,
) {
    val loadingDetail = loading?.let { progress ->
        when (progress.phase) {
            WebCatalogLoadPhase.CheckingCache -> stringResource(R.string.web_catalog_loading_cache)
            WebCatalogLoadPhase.FetchingPage -> stringResource(R.string.web_catalog_loading_fetch, progress.page)
            WebCatalogLoadPhase.ParsingDom -> stringResource(R.string.web_catalog_loading_parse, progress.page)
            WebCatalogLoadPhase.ApplyingResults -> stringResource(R.string.web_catalog_loading_apply, progress.page)
        }
    }
    EinkOperationIndicator(
        visible = loading != null || busy,
        title = when {
            translationProgress != null -> stringResource(R.string.web_catalog_translating_titles)
            loading != null -> stringResource(R.string.web_catalog_loading_title)
            else -> stringResource(R.string.web_catalog_working_title)
        },
        detail = translationProgress?.let {
            "${it.completedItems} / ${it.totalItems} · failed ${it.failedItems} · ${it.currentTitle}"
        } ?: loadingDetail ?: statusText,
        progress = translationProgress?.fraction,
    )
}
