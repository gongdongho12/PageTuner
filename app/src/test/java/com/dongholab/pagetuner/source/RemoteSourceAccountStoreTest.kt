package com.dongholab.pagetuner.source

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteSourceAccountStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun accountJsonRoundTripPreservesRemoteSourceAccounts() {
        val account = RemoteSourceAccount(
            id = "account-id",
            sourceType = RemoteSourceType.FtpServer,
            title = "NAS",
            endpoint = "ftp://nas.local",
            username = "reader",
            basePath = "/books",
            createdAtMillis = 10L,
            updatedAtMillis = 20L,
        )

        val decoded = RemoteSourceAccountJson.decode(RemoteSourceAccountJson.encode(listOf(account)))

        assertEquals(listOf(account), decoded)
    }

    @Test
    fun storeUpsertsByIdOrEndpointAndDeletesAccounts() = runTest {
        val store = RemoteSourceAccountStore(
            temporaryFolder.newFolder("remote-sources").resolve("accounts.json"),
        )
        val first = pageTurnerWebCatalogAccount(
            catalogUrl = "https://example.com/catalog.json",
            title = "Example",
            nowMillis = 100L,
        )
        val updated = first.copy(title = "Updated", updatedAtMillis = 200L)
        val other = pageTurnerWebCatalogAccount(
            catalogUrl = "https://example.com/other.json",
            title = "Other",
            nowMillis = 150L,
        )

        store.upsert(first)
        store.upsert(other)
        val afterUpdate = store.upsert(updated)

        assertEquals(listOf("Updated", "Other"), afterUpdate.map { it.title })
        assertEquals(2, store.list().size)

        val afterDelete = store.delete(updated.id)

        assertEquals(listOf(other), afterDelete)
        assertFalse(store.list().any { it.id == updated.id })
    }
}
