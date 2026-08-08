package com.dongholab.pagetuner.source.api

import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.source.RemoteBookItem

interface BookViewerApi {
    suspend fun prepareBookViewer(item: RemoteBookItem): ReaderDocument
}
