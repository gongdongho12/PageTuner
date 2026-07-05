package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleDriveRemoteBookSourceTest {
    @Test
    fun parsesGoogleDriveFileListPages() {
        val page = GoogleDriveFileListParser.parse(
            """
            {
              "nextPageToken": "next",
              "files": [
                {
                  "id": "file-1",
                  "name": "Story.epub",
                  "mimeType": "application/epub+zip",
                  "modifiedTime": "2026-07-05T00:00:00Z",
                  "size": "2048",
                  "md5Checksum": "abc",
                  "thumbnailLink": "https://example.com/thumb"
                },
                {
                  "id": "",
                  "name": "skip.pdf",
                  "mimeType": "application/pdf"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("next", page.nextPageToken)
        assertEquals(1, page.files.size)
        assertEquals("file-1", page.files.single().id)
        assertEquals(DocumentFormat.EPUB, page.files.single().format)
        assertEquals(2048L, page.files.single().sizeBytes)
    }

    @Test
    fun listsSearchesAndDownloadsSupportedDriveFiles() = runTest {
        val transport = FakeGoogleDriveTransport(
            pages = listOf(
                GoogleDriveFilePage(
                    files = listOf(
                        GoogleDriveFile(
                            id = "epub-id",
                            name = "Story.epub",
                            mimeType = "application/epub+zip",
                            modifiedTime = "2026-07-05T00:00:00Z",
                            sizeBytes = 2_048L,
                            md5Checksum = "abc",
                        ),
                        GoogleDriveFile(
                            id = "doc-id",
                            name = "Cloud Doc",
                            mimeType = "application/vnd.google-apps.document",
                        ),
                    ),
                    nextPageToken = "page-2",
                ),
                GoogleDriveFilePage(
                    files = listOf(
                        GoogleDriveFile(
                            id = "pdf-id",
                            name = "Manual.pdf",
                            mimeType = "application/pdf",
                        ),
                    ),
                    nextPageToken = null,
                ),
            ),
            downloads = mapOf("epub-id" to byteArrayOf(9, 8, 7)),
        )
        val source = GoogleDriveRemoteBookSource(
            config = GoogleDriveSourceConfig(
                accessToken = "token",
                title = "Drive Library",
                folderId = "folder-id",
            ),
            transport = transport,
        )

        val connection = source.connect()
        val items = source.list()
        val searched = source.search("manual")
        val bytes = source.download(items.first())

        assertEquals("Drive Library", connection.title)
        assertEquals(2, connection.itemCount)
        assertEquals(listOf("epub-id", "pdf-id"), items.map { it.identity.remoteId })
        assertEquals("Story", items.first().title)
        assertEquals(DocumentFormat.EPUB, items.first().format)
        assertEquals("md5:abc", items.first().checksum)
        assertEquals("google-drive://file/epub-id", items.first().downloadUrl)
        assertEquals(listOf("Manual"), searched.map { it.title })
        assertEquals(listOf(null, "page-2", null, "page-2", null, "page-2"), transport.requestedPageTokens)
        assertEquals(listOf<Byte>(9, 8, 7), bytes.toList())
    }
}

private class FakeGoogleDriveTransport(
    private val pages: List<GoogleDriveFilePage>,
    private val downloads: Map<String, ByteArray>,
) : GoogleDriveTransport {
    val requestedPageTokens = mutableListOf<String?>()

    override suspend fun listFiles(
        config: GoogleDriveSourceConfig,
        pageToken: String?,
    ): GoogleDriveFilePage {
        requestedPageTokens += pageToken
        return when (pageToken) {
            null -> pages[0]
            "page-2" -> pages[1]
            else -> error("Unexpected page token: $pageToken")
        }
    }

    override suspend fun downloadFile(
        config: GoogleDriveSourceConfig,
        fileId: String,
    ): ByteArray {
        return requireNotNull(downloads[fileId])
    }
}
