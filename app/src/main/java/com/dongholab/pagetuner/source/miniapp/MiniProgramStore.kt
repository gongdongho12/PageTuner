package com.dongholab.pagetuner.source.miniapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MiniProgramStore(context: Context) {
    private val storeFile = File(context.applicationContext.filesDir, "mini_programs/installed_applets.json")

    suspend fun listApplets(): List<MiniProgramApplet> = withContext(Dispatchers.IO) {
        readAll().sortedByDescending { it.installedAtMillis }
    }

    suspend fun installApplet(applet: MiniProgramApplet): List<MiniProgramApplet> = withContext(Dispatchers.IO) {
        val current = readAll().filterNot { it.id == applet.id }
        val updated = current + applet
        writeAll(updated)
        updated.sortedByDescending { it.installedAtMillis }
    }

    suspend fun uninstallApplet(appletId: String): List<MiniProgramApplet> = withContext(Dispatchers.IO) {
        val updated = readAll().filterNot { it.id == appletId }
        writeAll(updated)
        updated.sortedByDescending { it.installedAtMillis }
    }

    private fun readAll(): List<MiniProgramApplet> {
        if (!storeFile.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(storeFile.readText(Charsets.UTF_8))
            val array = root.optJSONArray("applets") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val itemStr = array.getJSONObject(i).toString()
                    add(MiniProgramApplet.fromJson(itemStr))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(applets: List<MiniProgramApplet>) {
        storeFile.parentFile?.mkdirs()
        val array = JSONArray()
        applets.forEach { applet ->
            array.put(JSONObject(applet.toJson()))
        }
        val root = JSONObject().put("version", 1).put("applets", array)
        storeFile.writeText(root.toString(2), Charsets.UTF_8)
    }
}
