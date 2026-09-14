package com.dongholab.pagetuner.document

import java.security.MessageDigest

data class TextSegment(
    val id: String,
    val pageIndex: Int,
    val indexInPage: Int,
    val text: String,
) {
    val wordCount: Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

object DocumentIds {
    fun stableId(title: String, body: String): String = sha256("$title\n$body")

    fun segmentId(documentId: String, pageIndex: Int, indexInPage: Int, text: String): String {
        return sha256("$documentId:$pageIndex:$indexInPage:$text").take(24)
    }

    fun sha256(value: String): String {
        return sha256(value.toByteArray(Charsets.UTF_8))
    }

    fun sha256(value: ByteArray): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
