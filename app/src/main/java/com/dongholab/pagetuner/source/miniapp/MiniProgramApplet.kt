package com.dongholab.pagetuner.source.miniapp

import org.json.JSONObject

data class MiniProgramApplet(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val iconUrl: String? = null,
    val entryUrl: String,
    val author: String? = null,
    val installedAtMillis: Long = System.currentTimeMillis(),
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("version", version)
            put("description", description)
            put("iconUrl", iconUrl ?: "")
            put("entryUrl", entryUrl)
            put("author", author ?: "")
            put("installedAtMillis", installedAtMillis)
        }.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): MiniProgramApplet {
            val json = JSONObject(jsonStr)
            return MiniProgramApplet(
                id = json.getString("id"),
                name = json.getString("name"),
                version = json.optString("version", "1.0.0"),
                description = json.optString("description", ""),
                iconUrl = json.optString("iconUrl").takeIf { it.isNotBlank() },
                entryUrl = json.getString("entryUrl"),
                author = json.optString("author").takeIf { it.isNotBlank() },
                installedAtMillis = json.optLong("installedAtMillis", System.currentTimeMillis()),
            )
        }
    }
}
