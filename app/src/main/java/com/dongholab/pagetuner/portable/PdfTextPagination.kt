package com.dongholab.pagetuner.portable

/** Platform-independent page coverage and resource bounds; Android supplies measured line ends. */
internal object PdfTextPagination {
    const val MAX_PAGES = 2_000
    const val MAX_LAYOUT_CODE_UNITS = 32_768

    /** Final-line font padding can differ from a line inside a longer measured window. */
    fun finalizedEnd(start: Int, lineEnds: List<Int>, fitsFinalLayout: (Int) -> Boolean): Int {
        for (end in lineEnds.asReversed()) {
            if (end > start && fitsFinalLayout(end)) return end
        }
        return start
    }

    fun forEachPage(text: String, maximumPages: Int = MAX_PAGES, maximumWindow: Int = MAX_LAYOUT_CODE_UNITS,
        checkActive: () -> Unit = {}, measuredEnd: (start: Int, windowEnd: Int) -> Int,
        render: (start: Int, end: Int, pageNumber: Int) -> Unit): Int {
        require(maximumPages in 1..MAX_PAGES && maximumWindow in 2..MAX_LAYOUT_CODE_UNITS)
        require(text.isNotEmpty()) { "The generated PDF has no text." }
        var start = 0
        var count = 0
        while (start < text.length) {
            checkActive()
            require(count < maximumPages) { "The generated PDF exceeds the page limit." }
            val windowEnd = boundaryBefore(text, minOf(text.length, start + maximumWindow))
            val end = measuredEnd(start, windowEnd)
            require(end in (start + 1)..windowEnd && boundaryBefore(text, end) == end) {
                "The PDF layout could not fit a complete text character on the page."
            }
            checkActive()
            render(start, end, ++count)
            start = end
        }
        checkActive()
        return count
    }

    private fun boundaryBefore(text: String, offset: Int): Int =
        if (offset in 1 until text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()) offset - 1 else offset
}
