package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.source.webnovel.WebNovelProviderPlugins
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSourceProviderAccountsTest {
    @Test
    fun defaultAccountsAreDerivedFromDiscoverableProviderManifests() {
        val accounts = defaultWebNovelAccounts(nowMillis = 42L)

        assertEquals(
            WebNovelProviderPlugins.discoverable.map { it.manifest.accountId },
            accounts.map(RemoteSourceAccount::id),
        )
        assertEquals(
            WebNovelProviderPlugins.discoverable.map { it.manifest.defaultCatalogUrl },
            accounts.map(RemoteSourceAccount::endpoint),
        )
        accounts.forEach { account ->
            assertEquals(RemoteSourceType.WebNovel, account.sourceType)
            assertEquals(42L, account.createdAtMillis)
            assertEquals(42L, account.updatedAtMillis)
        }
    }
}
