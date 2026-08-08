package com.dongholab.pagetuner.source

interface RemoteBookSourceFactory {
    val sourceType: RemoteSourceType
    val displayName: String
    val description: String

    fun createSource(account: RemoteSourceAccount): RemoteBookSource
}
