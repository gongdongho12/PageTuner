package com.dongholab.pagetuner.source.scraper

/**
 * Data Model for User-Defined Custom Web Novel Source Parsing Rules.
 */
data class CustomWebSourceRule(
    val id: String,
    val name: String,
    val domainUrl: String,
    val titleSelector: String = ".title, h1",
    val synopsisSelector: String = ".description, .synopsis, .summary",
    val chapterLinkSelector: String = "a[href*='/chapter/'], a[href*='/ch-']",
    val paragraphSelector: String = ".chapter-content p, .reading-content p, article p",
)
