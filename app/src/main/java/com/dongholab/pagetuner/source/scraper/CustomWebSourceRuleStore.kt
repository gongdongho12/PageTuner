package com.dongholab.pagetuner.source.scraper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreference & In-Memory Store for User-Defined Custom Web Source Rules.
 */
class CustomWebSourceRuleStore(context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("custom_web_source_rules", Context.MODE_PRIVATE)
    private val inMemoryRules = mutableListOf<CustomWebSourceRule>()

    init {
        loadFromPrefs()
    }

    fun getAllRules(): List<CustomWebSourceRule> {
        return inMemoryRules.toList()
    }

    fun addRule(rule: CustomWebSourceRule) {
        inMemoryRules.removeAll { it.id == rule.id || it.domainUrl == rule.domainUrl }
        inMemoryRules.add(0, rule)
        saveToPrefs()
    }

    fun deleteRule(ruleId: String) {
        inMemoryRules.removeAll { it.id == ruleId }
        saveToPrefs()
    }

    private fun saveToPrefs() {
        val prefs = prefs ?: return
        runCatching {
            val jsonArray = JSONArray()
            inMemoryRules.forEach { rule ->
                val obj = JSONObject()
                obj.put("id", rule.id)
                obj.put("name", rule.name)
                obj.put("domainUrl", rule.domainUrl)
                obj.put("titleSelector", rule.titleSelector)
                obj.put("synopsisSelector", rule.synopsisSelector)
                obj.put("chapterLinkSelector", rule.chapterLinkSelector)
                obj.put("paragraphSelector", rule.paragraphSelector)
                jsonArray.put(obj)
            }
            prefs.edit().putString("rules_json", jsonArray.toString()).apply()
        }
    }

    private fun loadFromPrefs() {
        val prefs = prefs ?: return
        runCatching {
            val jsonStr = prefs.getString("rules_json", null) ?: return
            val jsonArray = JSONArray(jsonStr)
            inMemoryRules.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                inMemoryRules.add(
                    CustomWebSourceRule(
                        id = obj.optString("id", "rule_$i"),
                        name = obj.optString("name", "Custom Source"),
                        domainUrl = obj.optString("domainUrl", ""),
                        titleSelector = obj.optString("titleSelector", ".title, h1"),
                        synopsisSelector = obj.optString("synopsisSelector", ".description, .synopsis"),
                        chapterLinkSelector = obj.optString("chapterLinkSelector", "a[href*='/chapter/']"),
                        paragraphSelector = obj.optString("paragraphSelector", ".chapter-content p, article p"),
                    )
                )
            }
        }
    }

    companion object {
        val globalStore = CustomWebSourceRuleStore(null)
    }
}
