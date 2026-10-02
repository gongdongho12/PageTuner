package com.dongholab.pagetuner.ui.reader

/** UTF-16 boundaries are retained verbatim, including whitespace and paragraph separators. */
internal fun safeTextBoundary(text: String, offset: Int): Int {
    val bounded = offset.coerceIn(0, text.length)
    return if (bounded in 1 until text.length && text[bounded - 1].isHighSurrogate() && text[bounded].isLowSurrogate()) bounded - 1 else bounded
}

/** Finds a fitting prefix without assuming a font size or a characters-per-page constant. */
internal fun fittingTextEnd(text: String, start: Int, limit: Int = text.length, fits: (Int, Int) -> Boolean): Int {
    val end = safeTextBoundary(text, limit)
    require(start in 0..end && safeTextBoundary(text, start) == start)
    if (start == end) return end
    var low = start
    var high = end
    // Grow a small probe first so a million-character book never enters one text layout operation.
    var probe = safeTextBoundary(text, (start.toLong() + 256).coerceAtMost(end.toLong()).toInt())
    while (probe < end && fits(start, probe)) {
        low = probe
        probe = safeTextBoundary(text, (start.toLong() + (probe - start).toLong() * 2).coerceAtMost(end.toLong()).toInt())
    }
    if (fits(start, probe)) return probe
    high = probe
    while (low < high) {
        var mid = safeTextBoundary(text, low + (high - low + 1) / 2)
        if (mid == low) {
            mid = low + Character.charCount(Character.codePointAt(text, low))
            if (mid > high) break
        }
        if (fits(start, mid)) low = mid else high = safeTextBoundary(text, mid - 1)
    }
    return low
}

/** Previous pages end exactly at the current anchor; no reread/omission at soft-wrap boundaries. */
internal fun fittingTextStart(text: String, end: Int, limit: Int = 0, fits: (Int, Int) -> Boolean): Int {
    val first = safeTextBoundary(text, limit)
    require(end in first..text.length && safeTextBoundary(text, end) == end)
    if (first == end) return first
    var low = first
    var high = end
    var probe = safeTextBoundary(text, (end - 256).coerceAtLeast(first))
    while (probe > first && fits(probe, end)) {
        high = probe
        probe = safeTextBoundary(text, (end.toLong() - (end - probe).toLong() * 2).coerceAtLeast(first.toLong()).toInt())
    }
    if (fits(probe, end)) return probe
    low = probe
    while (low < high) {
        val mid = safeTextBoundary(text, low + (high - low) / 2)
        if (fits(mid, end)) high = mid else low = mid + Character.charCount(Character.codePointAt(text, mid))
    }
    return high
}
