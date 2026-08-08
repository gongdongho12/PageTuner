package com.dongholab.pagetuner.source.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MiniProgramStoreTest {
    @Test
    fun miniProgramApplet_jsonSerialization() {
        val applet = MiniProgramApplet(
            id = "wtr_lab_applet",
            name = "WTR-Lab Novel Applet",
            version = "1.2.0",
            description = "Web novel store applet for PageTurner",
            entryUrl = "https://wtr-lab.com/en",
            author = "WTR Team",
        )

        val jsonStr = applet.toJson()
        val restored = MiniProgramApplet.fromJson(jsonStr)

        assertEquals("wtr_lab_applet", restored.id)
        assertEquals("WTR-Lab Novel Applet", restored.name)
        assertEquals("1.2.0", restored.version)
        assertEquals("https://wtr-lab.com/en", restored.entryUrl)
    }

    @Test
    fun pageTurnerJsBridge_returnsVersion() {
        var importedTitle = ""
        val bridge = PageTurnerJsBridge(
            onImportBookRequest = { title, _, _ -> importedTitle = title },
        )

        val version = bridge.getAppVersion()
        assertNotNull(version)

        val success = bridge.importBook("Overlord Vol 1", "Kugane Maruyama", "Chapter 1...")
        assertEquals(true, success)
        assertEquals("Overlord Vol 1", importedTitle)
    }
}
