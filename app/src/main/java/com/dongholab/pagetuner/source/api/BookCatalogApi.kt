package com.dongholab.pagetuner.source.api

import com.dongholab.pagetuner.source.RemoteBookItem

interface BookCatalogApi {
    suspend fun getBookList(): List<RemoteBookItem>
}
