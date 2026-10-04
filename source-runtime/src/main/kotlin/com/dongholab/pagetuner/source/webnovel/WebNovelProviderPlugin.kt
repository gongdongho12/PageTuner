package com.dongholab.pagetuner.source.webnovel

/**
 * Stable metadata needed to expose a web-novel provider without adding host checks to UI or
 * source-account code. A null [defaultCatalogUrl] marks a fallback-only provider.
 */
data class WebNovelProviderManifest(
    val id: String,
    val displayName: String,
    val accountId: String,
    val defaultCatalogUrl: String? = null,
)

/**
 * Installable web-novel provider boundary. A provider packages its manifest and adapter factory,
 * while catalog, reader, translation, and offline storage continue to use common orchestration.
 */
interface WebNovelProviderPlugin {
    val manifest: WebNovelProviderManifest

    fun createAdapter(): WebNovelSiteAdapter
}

class FactoryWebNovelProviderPlugin(
    override val manifest: WebNovelProviderManifest,
    private val adapterFactory: () -> WebNovelSiteAdapter,
) : WebNovelProviderPlugin {
    override fun createAdapter(): WebNovelSiteAdapter {
        val adapter = adapterFactory()
        require(adapter.id == manifest.id) {
            "Provider manifest id '${manifest.id}' does not match adapter id '${adapter.id}'."
        }
        return adapter
    }
}

object WebNovelProviderPlugins {
    val wtrLab: WebNovelProviderPlugin = FactoryWebNovelProviderPlugin(
        manifest = WebNovelProviderManifest(
            id = "wtr-lab",
            displayName = "WTR-LAB",
            accountId = "default_wtr_lab",
            defaultCatalogUrl = "https://wtr-lab.com/en/novel-list",
        ),
        adapterFactory = ::WtrLabSiteAdapter,
    )

    val novelBuddy: WebNovelProviderPlugin = FactoryWebNovelProviderPlugin(
        manifest = WebNovelProviderManifest(
            id = "novelbuddy",
            displayName = "NovelBuddy",
            accountId = "default_novelbuddy",
            defaultCatalogUrl = "https://novelbuddy.me/search",
        ),
        adapterFactory = ::NovelBuddySiteAdapter,
    )

    val genericHtml: WebNovelProviderPlugin = FactoryWebNovelProviderPlugin(
        manifest = WebNovelProviderManifest(
            id = "generic-semantic-html",
            displayName = "Generic semantic HTML",
            accountId = "web_novel",
        ),
        adapterFactory = ::GenericWebNovelSiteAdapter,
    )

    /** Registration order is significant: the generic fallback must remain last. */
    val builtIn: List<WebNovelProviderPlugin> = listOf(wtrLab, novelBuddy, genericHtml)

    val discoverable: List<WebNovelProviderPlugin>
        get() = builtIn.filter { it.manifest.defaultCatalogUrl != null }

    fun findByUrl(url: String): WebNovelProviderPlugin? =
        builtIn.firstOrNull { plugin -> plugin.createAdapter().supports(url) }

    fun findDiscoverableByUrl(url: String): WebNovelProviderPlugin? =
        discoverable.firstOrNull { plugin -> plugin.createAdapter().supports(url) }
}
