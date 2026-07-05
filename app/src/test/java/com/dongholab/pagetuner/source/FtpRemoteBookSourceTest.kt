package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtpRemoteBookSourceTest {
    @Test
    fun parsesMlsdDirectoryEntries() {
        val entries = FtpDirectoryParser.parseMlsd(
            rawListing = """
                type=dir;modify=20260705010101; Fiction
                type=file;size=2048;modify=20260705010203; Story One.epub
                type=file;size=512;modify=20260705010405; notes.txt
                type=cdir;modify=20260705000000; .
            """.trimIndent(),
            directoryPath = "/books",
        )

        assertEquals(3, entries.size)
        assertEquals("/books/Fiction", entries[0].path)
        assertTrue(entries[0].isDirectory)
        assertEquals("/books/Story One.epub", entries[1].path)
        assertEquals(2048L, entries[1].sizeBytes)
        assertEquals(DocumentFormat.EPUB, entries[1].format)
        assertEquals(DocumentFormat.TEXT, entries[2].format)
    }

    @Test
    fun parsesUnixListDirectoryEntries() {
        val entries = FtpDirectoryParser.parseList(
            rawListing = """
                drwxr-xr-x   2 user group      4096 Jul 05 12:00 Manga
                -rw-r--r--   1 user group   1048576 Jul 05 2026 Manual.pdf
            """.trimIndent(),
            directoryPath = "/books",
        )

        assertEquals(2, entries.size)
        assertEquals("/books/Manga", entries[0].path)
        assertTrue(entries[0].isDirectory)
        assertEquals("/books/Manual.pdf", entries[1].path)
        assertEquals(DocumentFormat.PDF, entries[1].format)
    }

    @Test
    fun listsSearchesAndDownloadsSupportedBooks() = runTest {
        val transport = FakeFtpTransport(
            entries = listOf(
                FtpRemoteEntry(
                    path = "/books/Archive",
                    name = "Archive",
                    isDirectory = true,
                ),
                FtpRemoteEntry(
                    path = "/books/Story One.epub",
                    name = "Story One.epub",
                    isDirectory = false,
                    sizeBytes = 2_048L,
                ),
                FtpRemoteEntry(
                    path = "/books/image.jpg",
                    name = "image.jpg",
                    isDirectory = false,
                ),
            ),
            downloads = mapOf("/books/Story One.epub" to byteArrayOf(1, 2, 3)),
        )
        val source = FtpRemoteBookSource(
            config = FtpRemoteSourceConfig(
                protocol = FtpProtocol.FTPS_EXPLICIT,
                host = "library.example.com",
                username = "reader",
                basePath = "/books",
                title = "Personal FTP",
            ),
            transport = transport,
        )

        val connection = source.connect()
        val items = source.list()
        val searched = source.search("story")
        val bytes = source.download(items.single())

        assertEquals("Personal FTP", connection.title)
        assertEquals(1, connection.itemCount)
        assertEquals("Story One", items.single().title)
        assertEquals(DocumentFormat.EPUB, items.single().format)
        assertEquals("ftps://library.example.com/books/Story%20One.epub", items.single().downloadUrl)
        assertEquals(listOf(items.single()), searched)
        assertEquals(listOf("/books", "/books", "/books"), transport.listedDirectories)
        assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())
    }
}

private class FakeFtpTransport(
    private val entries: List<FtpRemoteEntry>,
    private val downloads: Map<String, ByteArray>,
) : FtpTransport {
    val listedDirectories = mutableListOf<String>()

    override suspend fun list(
        config: FtpRemoteSourceConfig,
        directoryPath: String,
    ): List<FtpRemoteEntry> {
        listedDirectories += directoryPath
        return entries
    }

    override suspend fun download(
        config: FtpRemoteSourceConfig,
        remotePath: String,
    ): ByteArray {
        return requireNotNull(downloads[remotePath])
    }
}
