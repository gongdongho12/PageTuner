package com.dongholab.pagetuner.source.scraper

/**
 * Web Novel Scraper Engine Registry.
 * Automatically selects the best scraper adapter (Auto-Detection Strategy)
 * and allows users to dynamically register custom web novel scrapers.
 */
object WebNovelScraperRegistry {

    private val scrapers = mutableListOf<WebNovelScraperEngine>(
        WtrLabScraperAdapter(),
        GenericSemanticHtmlScraperAdapter(),
    )

    fun registerScraper(scraper: WebNovelScraperEngine) {
        if (!scrapers.contains(scraper)) {
            scrapers.add(0, scraper) // Custom scrapers take precedence
        }
    }

    /**
     * Auto-detects and returns the best matching Scraper Engine for the given URL.
     */
    fun findScraper(url: String): WebNovelScraperEngine {
        return scrapers.firstOrNull { it.canHandle(url) }
            ?: scrapers.last() // GenericSemanticHtmlScraperAdapter Fallback
    }

    fun getAllScrapers(): List<WebNovelScraperEngine> = scrapers.toList()
}
