package com.dongholab.pagetuner.source.api

import com.dongholab.pagetuner.source.RemoteBookItem

interface BookSearchApi {
    suspend fun searchBooks(query: String): List<RemoteBookItem>
}
