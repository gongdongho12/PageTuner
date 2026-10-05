package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.LibraryExchangeLimits
import com.dongholab.pagetuner.core.content.EpubDocumentExports
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object PortableEpubDocument {
    fun create(bookTitle: String, chapterTitle: String, language: String?, paragraphs: List<String>,
        checkActive: () -> Unit = {}, maximumBytes: Int = LibraryExchangeLimits.ARCHIVE_BYTES): PreparedDocumentFile {
        require(maximumBytes in 1..LibraryExchangeLimits.ARCHIVE_BYTES)
        checkActive()
        val document = EpubDocumentExports.create(bookTitle, chapterTitle, language, paragraphs)
        val output = BoundedDocumentOutputStream(maximumBytes, checkActive)
        ZipOutputStream(output).use { zip ->
            document.entries.forEachIndexed { index, source ->
                checkActive()
                val bytes = source.text.toByteArray(Charsets.UTF_8)
                val entry = ZipEntry(source.path).apply {
                    // Reproducible local ZIP metadata, without an extended timestamp extra field.
                    // Stay away from the 1980 DOS boundary in every timezone (mimetype forbids extras).
                    time = 946684800000L
                    if (index == 0) {
                        require(source.path == "mimetype" && source.text == "application/epub+zip")
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    } else method = ZipEntry.DEFLATED
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        checkActive()
        return PreparedDocumentFile(document.filename, document.mimeType, output.toByteArray())
    }
}
