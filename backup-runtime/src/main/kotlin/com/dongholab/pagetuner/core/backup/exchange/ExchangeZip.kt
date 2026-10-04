package com.dongholab.pagetuner.core.backup.exchange

/** Checks central/local agreement before inflation, including encrypted, hidden and overlapping entries. */
internal object ExchangeZip {
    data class Descriptor(val bytes: Int)
    fun inspect(data: ByteArray): Map<String, Descriptor> {
        fun u16(offset: Int): Int {
            require(offset >= 0 && offset + 2 <= data.size) { "Truncated ZIP." }
            return (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
        }
        fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
        fun length(offset: Int): Int {
            val number = u32(offset)
            require(number <= Int.MAX_VALUE) { "ZIP64 and oversized ZIP entries are unsupported." }
            return number.toInt()
        }
        require(data.size >= 22) { "Truncated ZIP." }
        // Package ZIPs intentionally omit comments and multi-volume/ZIP64 extensions.
        val end = data.size - 22
        require(u32(end) == 0x06054b50L && u16(end + 20) == 0) { "Missing ZIP end directory or unsupported ZIP comment." }
        require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10)) { "Multi-volume ZIP is unsupported." }
        val count = u16(end + 10)
        require(count in 2..LibraryExchangeLimits.MAX_ENTRIES) { "Invalid ZIP entry count." }
        val centralBytes = length(end + 12)
        val centralStart = length(end + 16)
        require(centralStart.toLong() + centralBytes == end.toLong()) { "Invalid ZIP directory bounds." }
        var cursor = centralStart
        var expanded = 0L
        val entries = LinkedHashMap<String, Descriptor>()
        val localRegions = mutableListOf<Pair<Int, Int>>()
        repeat(count) {
            require(cursor + 46 <= end && u32(cursor) == 0x02014b50L) { "Invalid central ZIP header." }
            val flags = u16(cursor + 8)
            require(flags and (0x800 or 8 or 6).inv() == 0) { "Encrypted or unsupported ZIP flags." }
            val method = u16(cursor + 10)
            require(method == 0 || method == 8) { "Unsupported ZIP compression." }
            require(method != 0 || flags and 8 == 0) { "Stored ZIP entries require upfront sizes." }
            val crc = u32(cursor + 16)
            val compressed = length(cursor + 20)
            val uncompressed = length(cursor + 24)
            expanded += uncompressed
            require(expanded <= LibraryExchangeLimits.EXPANDED_BYTES) { "Expanded ZIP exceeds 64 MiB." }
            val nameLength = u16(cursor + 28)
            val extraLength = u16(cursor + 30)
            val commentLength = u16(cursor + 32)
            require(u16(cursor + 34) == 0) { "Multi-volume ZIP is unsupported." }
            require((u32(cursor + 38) shr 16).toInt() and 0xf000 != 0xa000) { "Symbolic link ZIP entries are unsupported." }
            val local = length(cursor + 42)
            val next = cursor.toLong() + 46 + nameLength + extraLength + commentLength
            require(next <= end) { "Truncated central ZIP header." }
            val nameBytes = data.copyOfRange(cursor + 46, cursor + 46 + nameLength)
            val name = ExchangeJson.utf8(nameBytes)
            require(name == "manifest.json" || Regex("documents/[0-9a-f]{64}\\.json|assets/[0-9a-f]{64}").matches(name)) { "Unexpected ZIP path." }
            require(entries.put(name, Descriptor(uncompressed)) == null) { "Duplicate ZIP entry." }
            val limit = when {
                name == "manifest.json" -> LibraryExchangeLimits.MANIFEST_BYTES
                name.startsWith("documents/") -> LibraryExchangeLimits.DOCUMENT_BYTES
                else -> LibraryExchangeLimits.ARCHIVE_BYTES
            }
            require(uncompressed <= limit) { "ZIP entry exceeds its byte limit." }
            require(local.toLong() + 30 <= centralStart && u32(local) == 0x04034b50L) { "Invalid local ZIP header." }
            require(u16(local + 6) == flags && u16(local + 8) == method) { "ZIP headers disagree." }
            val localNameLength = u16(local + 26)
            val localExtraLength = u16(local + 28)
            require(localNameLength == nameLength) { "ZIP filenames disagree." }
            val payloadStart = local.toLong() + 30 + localNameLength + localExtraLength
            val payloadEnd = payloadStart + compressed
            require(payloadEnd <= centralStart) { "ZIP entry overlaps the directory." }
            require(nameBytes.contentEquals(data.copyOfRange(local + 30, local + 30 + localNameLength))) { "ZIP filenames disagree." }
            var regionEnd = payloadEnd.toInt()
            if (flags and 8 != 0) {
                if (u32(regionEnd) == 0x08074b50L) regionEnd += 4
                require(regionEnd.toLong() + 12 <= centralStart && u32(regionEnd) == crc &&
                    length(regionEnd + 4) == compressed && length(regionEnd + 8) == uncompressed) { "ZIP data descriptor disagrees." }
                regionEnd += 12
            } else {
                require(u32(local + 14) == crc && length(local + 18) == compressed && length(local + 22) == uncompressed) { "ZIP sizes or checksum disagree." }
            }
            localRegions.add(local to regionEnd)
            cursor = next.toInt()
        }
        require(cursor == end) { "Unexpected ZIP directory data." }
        var localCursor = 0
        localRegions.sortedBy { it.first }.forEach { (start, endOffset) ->
            require(start == localCursor && endOffset > start) { "Overlapping, hidden or prefixed ZIP entries." }
            localCursor = endOffset
        }
        require(localCursor == centralStart) { "Hidden ZIP data before directory." }
        return entries
    }
}
