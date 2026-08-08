package com.dongholab.pagetuner.source.api

import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.source.RemoteBookItem

interface EinkAppletContainerApi : BookCatalogApi, BookSearchApi, BookViewerApi {
    suspend fun openInMonochromeViewer(item: RemoteBookItem): ReaderDocument
}
