package com.dongholab.pagetuner.reader

/** A viewport position, never a replacement for a document's canonical page/segment identities. */
data class ReaderDisplayPosition(
    val pageIndex: Int,
    val characterOffset: Int = 0,
    val originalDisplayOffset: Int? = null,
    val originalTextKey: String? = null,
    val translatedDisplayOffset: Int = 0,
    val translatedTextKey: String? = null,
    val originalEnd: Int? = null,
    val translatedEnd: Int? = null,
    val viewportKey: String? = null,
    val fromEnd: Boolean = false,
)

data class ReaderDisplayNavigation(
    val previous: ReaderDisplayPosition? = null,
    val next: ReaderDisplayPosition? = null,
    val position: ReaderDisplayPosition? = null,
    val pageChangeRevision: Long = -1,
)
