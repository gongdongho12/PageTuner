package com.dongholab.pagetuner.source

import android.content.Context
import com.dongholab.pagetuner.document.DocumentIds
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RemoteSourceAccount(
    val id: String,
    val sourceType: RemoteSourceType,
    val title: String,
    val endpoint: String,
    val username: String? = null,
    val basePath: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val displayEndpoint: String
        get() = when {
            basePath.isNullOrBlank() -> endpoint
            else -> "$endpoint ${basePath}"
        }
}

class RemoteSourceAccountStore internal constructor(
    private val accountsFile: File,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "remote_sources/accounts.json"),
    )

    suspend fun list(): List<RemoteSourceAccount> = withContext(Dispatchers.IO) {
        readAccounts().sortedByDescending { it.updatedAtMillis }
    }

    suspend fun upsert(account: RemoteSourceAccount): List<RemoteSourceAccount> = withContext(Dispatchers.IO) {
        val accounts = readAccounts()
            .filterNot { it.id == account.id || it.sourceType == account.sourceType && it.endpoint == account.endpoint }
            .plus(account)
            .sortedByDescending { it.updatedAtMillis }
        writeAccounts(accounts)
        accounts
    }

    suspend fun delete(accountId: String): List<RemoteSourceAccount> = withContext(Dispatchers.IO) {
        val accounts = readAccounts().filterNot { it.id == accountId }
        writeAccounts(accounts)
        accounts.sortedByDescending { it.updatedAtMillis }
    }

    private fun readAccounts(): List<RemoteSourceAccount> {
        if (!accountsFile.exists()) return emptyList()
        return runCatching {
            RemoteSourceAccountJson.decode(accountsFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun writeAccounts(accounts: List<RemoteSourceAccount>) {
        accountsFile.parentFile?.mkdirs()
        val tmpFile = File(requireNotNull(accountsFile.parentFile), "${accountsFile.name}.tmp")
        tmpFile.writeText(RemoteSourceAccountJson.encode(accounts), Charsets.UTF_8)
        if (!tmpFile.renameTo(accountsFile)) {
            accountsFile.writeText(tmpFile.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmpFile.delete()
        }
    }
}

object RemoteSourceAccountJson {
    fun encode(accounts: List<RemoteSourceAccount>): String {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(
                JSONObject()
                    .put("id", account.id)
                    .put("sourceType", account.sourceType.name)
                    .put("title", account.title)
                    .put("endpoint", account.endpoint)
                    .put("username", account.username)
                    .put("basePath", account.basePath)
                    .put("createdAtMillis", account.createdAtMillis)
                    .put("updatedAtMillis", account.updatedAtMillis),
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("accounts", array)
            .toString(2)
    }

    fun decode(rawJson: String): List<RemoteSourceAccount> {
        if (rawJson.isBlank()) return emptyList()
        val root = JSONObject(rawJson)
        val accounts = root.optJSONArray("accounts") ?: return emptyList()
        return buildList {
            for (index in 0 until accounts.length()) {
                accounts.optJSONObject(index)?.toRemoteSourceAccount()?.let { add(it) }
            }
        }
    }

    private fun JSONObject.toRemoteSourceAccount(): RemoteSourceAccount? {
        val id = optString("id")
        val title = optString("title")
        val endpoint = optString("endpoint")
        if (id.isBlank() || title.isBlank() || endpoint.isBlank()) return null
        val sourceType = runCatching {
            RemoteSourceType.valueOf(optString("sourceType"))
        }.getOrNull() ?: return null

        return RemoteSourceAccount(
            id = id,
            sourceType = sourceType,
            title = title,
            endpoint = endpoint,
            username = optString("username").takeIf { it.isNotBlank() },
            basePath = optString("basePath").takeIf { it.isNotBlank() },
            createdAtMillis = optLong("createdAtMillis", 0L),
            updatedAtMillis = optLong("updatedAtMillis", 0L),
        )
    }
}

fun pageTurnerWebCatalogAccount(
    catalogUrl: String,
    title: String,
    nowMillis: Long = System.currentTimeMillis(),
): RemoteSourceAccount {
    val normalizedUrl = catalogUrl.trim()
    val safeTitle = title.trim().ifBlank { normalizedUrl }
    return RemoteSourceAccount(
        id = DocumentIds.sha256("${RemoteSourceType.PageTurnerWebCatalog.name}|$normalizedUrl").take(16),
        sourceType = RemoteSourceType.PageTurnerWebCatalog,
        title = safeTitle,
        endpoint = normalizedUrl,
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis,
    )
}
