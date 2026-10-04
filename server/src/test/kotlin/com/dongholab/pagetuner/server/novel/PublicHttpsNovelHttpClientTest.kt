package com.dongholab.pagetuner.server.novel

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicHttpsNovelHttpClientTest {
    @Test
    fun `rejects unsafe schemes ports credentials numeric hosts and parser ambiguity`() {
        listOf("http://wtr-lab.com/en", "https://wtr-lab.com:8443/en", "https://user:secret@wtr-lab.com/",
            "https://127.0.0.1/", "https://2130706433/", "https://[::1]/", "https://localhost/",
            "https://host.local/", "https://wtr-lab.com/#fragment", "https://wtr-lab.com\\@localhost/")
            .forEach { value -> assertThrows(UnsafeNovelUrl::class.java, { PublicNovelTargets.url(value) }, value) }
        assertEquals("wtr-lab.com", PublicNovelTargets.url("https://wtr-lab.com/en/novel-list?page=1").host)
    }

    @Test
    fun `rejects every nonpublic DNS answer including mixed rebinding responses`() {
        val public = InetAddress.getByName("8.8.8.8")
        listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1",
            "::1", "fe80::1", "fc00::1", "2001:db8::1", "2002:7f00:1::")
            .forEach { value -> assertThrows(UnknownHostException::class.java) {
                PublicNovelTargets.addresses(listOf(public, InetAddress.getByName(value)))
            } }
        assertEquals(listOf(public), PublicNovelTargets.addresses(listOf(public)))
        assertEquals(1, PublicNovelTargets.addresses(listOf(InetAddress.getByName("2606:4700:4700::1111"))).size)
    }

    @Test
    fun `reader POST rejects unsafe destination and cross origin referer before any request`() {
        val client = PublicHttpsNovelHttpClient()
        assertThrows(UnsafeNovelUrl::class.java) { runBlocking {
            client.postJson("http://127.0.0.1/reader", "{}", "https://wtr-lab.com/en")
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            client.postJson("https://wtr-lab.com/api/reader/get", "{}", "https://novelbuddy.me/sample")
        } }
    }
}
