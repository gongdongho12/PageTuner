package com.dongholab.pagetuner.source

data class RenderedChapter(val title: String, val paragraphs: List<String>)

/** Optional host capability. Only the Android app currently provides an implementation. */
fun interface RenderedChapterLoader {
    suspend fun loadChapter(url: String, chapterNumber: Int): RenderedChapter
}
