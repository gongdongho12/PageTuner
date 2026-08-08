package com.dongholab.pagetuner.source.plugin

import org.json.JSONArray
import org.json.JSONObject

data class ScriptableConnectorPlugin(
    val id: String,
    val name: String,
    val version: String = "1.0",
    val baseUrl: String,
    val searchUrlTemplate: String? = null,
    val contentSelector: String? = null,
    val removeSelectors: List<String> = emptyList(),
    val titleSelector: String? = null,
    val customJsScript: String? = null,
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("version", version)
            put("baseUrl", baseUrl)
            put("searchUrlTemplate", searchUrlTemplate ?: "")
            put("contentSelector", contentSelector ?: "")
            put("removeSelectors", JSONArray(removeSelectors))
            put("titleSelector", titleSelector ?: "")
            put("customJsScript", customJsScript ?: "")
        }.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): ScriptableConnectorPlugin {
            val json = JSONObject(jsonStr)
            val removeArray = json.optJSONArray("removeSelectors") ?: JSONArray()
            val removeList = mutableListOf<String>()
            for (i in 0 until removeArray.length()) {
                removeList.add(removeArray.getString(i))
            }
            return ScriptableConnectorPlugin(
                id = json.getString("id"),
                name = json.getString("name"),
                version = json.optString("version", "1.0"),
                baseUrl = json.getString("baseUrl"),
                searchUrlTemplate = json.optString("searchUrlTemplate").takeIf { it.isNotBlank() },
                contentSelector = json.optString("contentSelector").takeIf { it.isNotBlank() },
                removeSelectors = removeList,
                titleSelector = json.optString("titleSelector").takeIf { it.isNotBlank() },
                customJsScript = json.optString("customJsScript").takeIf { it.isNotBlank() },
            )
        }
    }
}
