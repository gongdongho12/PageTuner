package com.dongholab.pagetuner.source.novelbuddy

data class NovelBuddyBookSummary(
    val id: String,
    val name: String,
    val url: String,
    val authors: List<String>,
    val coverUrl: String?,
    val summary: String?,
    val status: String,
    val chapterCount: Int,
    val genres: List<String>,
    val tags: List<String>,
    val views: String,
    val rating: Float,
    val contentVersion: Long?,
)

data class NovelBuddyCatalogResponse(
    val items: List<NovelBuddyBookSummary>,
    val currentPage: Int,
    val totalPages: Int?,
    val totalItems: Int?,
    val hasPreviousPage: Boolean,
    val hasNextPage: Boolean,
)

data class NovelBuddyChapterSummary(
    val id: String,
    val number: Int,
    val name: String,
    val url: String,
)

data class NovelBuddyChapterContent(
    val number: Int,
    val title: String,
    val paragraphs: List<String>,
)
