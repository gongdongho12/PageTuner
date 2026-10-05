package com.dongholab.pagetuner.portable

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Rejects oversized generated documents before an unbounded backing buffer is allocated. */
internal class BoundedDocumentOutputStream(
    private val maximumBytes: Int,
    private val checkActive: () -> Unit = {},
) : OutputStream() {
    private val output = ByteArrayOutputStream(minOf(maximumBytes, 8192))

    init { require(maximumBytes > 0) }

    override fun write(value: Int) {
        checkCapacity(1)
        output.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        checkCapacity(length)
        output.write(bytes, offset, length)
    }

    private fun checkCapacity(length: Int) {
        checkActive()
        require(length <= maximumBytes - output.size()) { "The generated document exceeds the output byte limit." }
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}
