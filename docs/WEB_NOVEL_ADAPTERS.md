# Web Novel Site Adapter Architecture

Web novel support is split into a site-independent source orchestrator and
site-specific adapters. Adding a site must not require conditionals in
`WebNovelRemoteBookSource`, the catalog ViewModel, or Compose UI.

## Layers

```text
RemoteBookSource / UI / offline downloader
  -> WebNovelRemoteBookSource
     -> WebNovelSiteAdapterRegistry
        -> WtrLabSiteAdapter
        -> another dedicated site adapter
        -> GenericWebNovelSiteAdapter (last fallback)
           -> WebNovelScraperRegistry / WebNovelScraperEngine
```

### `WebNovelRemoteBookSource`

Owns only common orchestration:

- endpoint HTML caching;
- `RemoteBookSource` connection/list/search/refresh behavior;
- mapping common web-novel models to `RemoteBookItem`;
- minimum readable-content validation and text serialization.

It does not know WTR-LAB hosts, URL formats, selectors, or rendering rules.

### `WebNovelSiteAdapter`

Owns all site policy:

- whether a URL belongs to the site;
- catalog/detail/chapter page classification;
- catalog, detail, and chapter-list mapping;
- relative and canonical URL construction;
- static HTTP versus rendered-page chapter loading;
- conversion to common site models.

`WtrLabSiteAdapter` uses WTR-LAB Next.js DOM models and the rendered WebView
loader. `GenericWebNovelSiteAdapter` uses semantic link/text extraction and the
existing scraper registry as a fallback.

### `WebNovelScraperEngine`

This remains the lower-level HTML parsing extension point. It is useful for
custom DOM rules and parsing static HTML, but it does not own fetching,
JavaScript rendering, or URL policy. A site with any non-trivial URL or loading
behavior should get a full `WebNovelSiteAdapter`.

## Adding another site

1. Implement `WebNovelSiteAdapter` in `source/webnovel`.
2. Give it a unique stable `id` and a strict `supports(url)` host check.
3. Implement all three page classifications and common model mappings.
4. Decide whether chapters use `fetchHtml` or `RenderedChapterLoader`.
5. Register it before the generic fallback:

```kotlin
WebNovelSiteAdapterRegistry.default.register(MyNovelSiteAdapter())
```

Built-in adapters should be added to `defaultAdapters()` so they are available
at process start. Runtime/custom adapters are inserted at highest priority by
`register`.

## Adapter implementation rules

- Use absolute resolved URLs in common models.
- Use stable remote book/chapter IDs; never use the visible list position when
  the site provides an ID or canonical URL.
- Return extracted paragraphs, not HTML, from `loadChapter`.
- Throw a meaningful error when required rendered content is unavailable.
- Do not translate in an adapter. Translation belongs to
  `ContentTranslationService` after extraction.
- Do not persist in an adapter. Offline storage belongs to the download
  workflow after a valid original has been produced.
- Keep the generic adapter last; its URL classification is intentionally
  heuristic.

## Testing

- `WebNovelSiteAdapterRegistryTest` verifies dedicated adapter precedence,
  generic fallback, runtime registration, and WTR URL classification.
- `WebNovelRemoteBookSourceAdapterTest` injects a fake site adapter and proves
  that connection, listing, detail, and chapter loading are delegated without
  changing the source orchestrator.
- Site implementations should add fixture-based tests for catalog, detail,
  chapter list, chapter body, URL resolution, and missing rendered content.

## Relationship to offline translation

Adapters finish at clean original text. `WebNovelBatchDownloader` then passes
that original to `ContentTranslationService` and persists the result through
`OfflineNovelStorageStore`. See
[WEB_NOVEL_OFFLINE_TRANSLATION.md](WEB_NOVEL_OFFLINE_TRANSLATION.md) for the
full airplane-mode flow.
