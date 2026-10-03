package com.dongholab.pagetuner.sharing

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import com.dongholab.pagetuner.library.LocalLibraryStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Uses the installed app's foreground service, packaged web and read-only library on a real interface. */
class LocalSharingServiceInstrumentedTest {
    @Test fun packagedWebPairsAndReadsWithoutChangingNativeLibrary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val address = sharingAddresses().firstOrNull()?.address
            ?: error("No private IPv4 interface available for sharing")
        val before = LocalLibraryStore(context).listBooks()
        ActivityScenario.launch(ComponentActivity::class.java).use {
            try {
                LocalSharingControl.start(context, address)
                val info = withTimeout(20_000) {
                    while (LocalSharingControl.state.value.session == null) {
                        check(LocalSharingControl.state.value.message == null) { "Sharing failed: ${LocalSharingControl.state.value.message}" }
                        delay(100)
                    }
                    LocalSharingControl.state.value.session!!
                }
                val base = "http://${info.address}:${info.port}"
                fun request(path: String, code: String? = null, token: String? = null): Pair<Int, String> {
                    val connection = URL(base + path).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                        token?.let { connection.setRequestProperty("X-PageTuner-Session", it) }
                        code?.let {
                            connection.requestMethod = "POST"; connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "application/json")
                            connection.outputStream.use { stream -> stream.write(JSONObject().put("code", code).toString().toByteArray()) }
                        }
                        val status = connection.responseCode
                        return status to (if (status < 400) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                    } finally { connection.disconnect() }
                }
                val web = request("/")
                assertEquals(200, web.first); assertTrue(web.second.contains("<script"))
                assertEquals(200, request("/api/share/v1/status").first)
                assertEquals(401, request("/api/share/v1/books").first)
                val wrong = if (info.pairingCode == "00000000") "11111111" else "00000000"
                assertEquals(401, request("/api/share/v1/pair", code = wrong).first)
                val paired = request("/api/share/v1/pair", code = info.pairingCode)
                assertEquals(200, paired.first)
                val token = JSONObject(paired.second).getString("token")
                val books = request("/api/share/v1/books", token = token)
                assertEquals(200, books.first)
                val items = JSONObject(books.second).getJSONArray("items")
                if (items.length() > 0) assertEquals(200, request("/api/share/v1/books/${items.getJSONObject(0).getString("id")}", token = token).first)
                assertEquals(before, LocalLibraryStore(context).listBooks())
            } finally {
                LocalSharingControl.stop(context)
                withTimeout(10_000) { while (LocalSharingControl.state.value.session != null) delay(100) }
            }
            assertNull(LocalSharingControl.state.value.session)
        }
    }
    /** Opt-in browser bridge: no production binding policy is changed. */
    @Test fun browserPreview() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("sharingPreview") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val document = com.dongholab.pagetuner.core.sharing.SharedDocument(
            "sharing-test", "핫스팟 공유 읽기 테스트", "txt", "original", "ko", "fixture-v1",
            (1..30).map { com.dongholab.pagetuner.core.sharing.SharedParagraph("p$it", "공유 테스트 $it. 휴대폰에 저장한 책을 다른 기기에서 페이지별로 읽습니다. 공백과 읽기 위치를 확인합니다. 😀") })
        val fixture = object : SharingLibrary {
            override fun list(offset: Int, limit: Int) = com.dongholab.pagetuner.core.sharing.SharedLibraryPage(
                listOf(com.dongholab.pagetuner.core.sharing.SharedBookSummary(document.id, document.title, "txt", "original")).drop(offset).take(limit), 1, offset, limit)
            override fun document(id: String) = document.takeIf { it.id == id }
            override fun asset(documentId: String, revision: String, assetId: String): SharedBinary? = null
        }
        val runtime = LocalSharingServer("127.0.0.1", 18787, fixture, AndroidSharingWebAssets(context))
        val marker = java.io.File(context.cacheDir, "sharing-browser-preview.json")
        val finished = java.io.File(context.cacheDir, "sharing-browser-preview.done")
        try {
            finished.delete()
            val info = runtime.startSharing()
            marker.writeText(JSONObject().put("code", info.pairingCode).put("port", info.port).toString())
            withTimeout(180_000) { while (!finished.exists()) delay(200) }
        } finally {
            runtime.stopSharing(); marker.delete(); finished.delete()
        }
    }
    /** Manual runner preserves the installed app and opens its LAN URL in the device browser. */
    @Test fun sameDeviceBrowser() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("sameDeviceBrowser") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureFile = java.io.File(context.cacheDir, "공유_검증_책.txt")
        fixtureFile.writeText((1..30).joinToString("\n\n") { "공유 검증 $it. 앱에 실제로 저장한 책을 같은 폰의 브라우저에서 읽습니다. 내장 폰트와 페이지 이동을 확인합니다. 😀" })
        val savedBook = LocalLibraryStore(context).importBook(android.net.Uri.fromFile(fixtureFile))
        fixtureFile.delete()
        val marker = java.io.File(context.cacheDir, "sharing-same-device.json")
        val finished = java.io.File(context.cacheDir, "sharing-same-device.done")
        ActivityScenario.launch(ComponentActivity::class.java).use {
            try {
                finished.delete()
                LocalSharingControl.start(context, sharingAddresses().first().address)
                val info = withTimeout(20_000) {
                    while (LocalSharingControl.state.value.session == null) {
                        check(LocalSharingControl.state.value.message == null)
                        delay(100)
                    }
                    LocalSharingControl.state.value.session!!
                }
                val url = "http://${info.address}:${info.port}/"
                val library = AndroidSharingLibrary(context)
                val books = library.list(0, 50)
                val shared = books.items.single { it.title == savedBook.book.title }
                val sharedDocument = library.document(shared.id)!!
                assertTrue(sharedDocument.paragraphs.any { it.text.contains("앱에 실제로 저장한 책") })
                marker.writeText(JSONObject().put("url", url).put("code", info.pairingCode).put("book", savedBook.book.title).toString())
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .setPackage("com.android.chrome").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                withTimeout(600_000) { while (!finished.exists()) delay(200) }
                assertNotNull("Sharing survives the browser foreground", LocalSharingControl.state.value.session)
            } finally {
                LocalSharingControl.stop(context); marker.delete(); finished.delete()
            }
        }
    }
}
