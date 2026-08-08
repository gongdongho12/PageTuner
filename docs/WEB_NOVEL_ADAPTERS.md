# Web Novel Site Adapter Architecture

Navigation and chapter hierarchy are source-independent. See
[Remote Catalog Navigation](REMOTE_CATALOG_NAVIGATION.md) before adding a new
catalog or site adapter; Compose screens must not own provider-specific back
stacks.

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

### Background catalog warm-up

`WebCatalogViewModel` warms the default WTR-Lab catalog as soon as the app
creates the ViewModel; the user does not need to open the Web Novel tab first.
The warm-up is non-blocking and does not participate in the application-wide
`busy` flag.

HTML catalogs are converted to `PageTurnerCatalog` and saved with
`RemoteCatalogSnapshotJson`. The saved snapshot preserves source type, remote
identity, cover/description metadata, and series identity. On the next launch
the snapshot is available immediately while a fresh HTML load replaces it in
the background.

The WTR-Lab landing page (`/{language}`) is not the complete catalog. Its
sections happen to contain only a few dozen distinct books. The adapter must
canonicalize it to `/{language}/novel-list` before loading page 1.

### Remote catalog pagination

Remote website pages and E-Ink viewport pages are different layers:

```text
WTR-Lab remote page (10 books, page 1 / 8,586)
  -> RemoteCatalogPage / RemoteCatalogPagingState
     -> E-Ink auto-fit page (only the rows that fit the current screen)
```

- `WebNovelSiteAdapter.canonicalCatalogUrl` maps a landing URL to the complete
  catalog without adding WTR-specific branches to the ViewModel.
- `catalogPageUrl` changes only the `page` query parameter and preserves sort,
  status, and genre filters.
- `parseCatalogPage` returns page items plus `currentPage`, `totalPages`,
  `totalItems`, and previous/next availability.
- `WebNovelRemoteBookSource` implements `PaginatedRemoteBookSource` and emits
  `FetchingPage` and `ParsingDom` steps.
- `WebCatalogViewModel` keeps recently visited pages in memory, persists page 1
  as the warm-start snapshot, and never pretends that one remote page is the
  whole catalog.

The Compose layer uses `EinkRemoteCatalogPager` for server navigation and
`EinkAutoFitPagingContainer` for the rows already returned by that server page.
Do not merge these controls or eagerly download thousands of catalog pages.

### Work and chapter identity

Every chapter returned by `WebNovelRemoteBookSource` carries optional grouping
metadata:

- `seriesId`: canonical novel-detail URL shared by every chapter;
- `seriesTitle`: parent work title;
- `chapterNumber`: source chapter number.

`WebNovelSeriesKeys` removes query/fragment values and trailing chapter paths
to keep the work identity stable. `LocalLibraryStore` maps this identity to one
stable local-book ID. Opening another chapter of the same work replaces its
current chapter payload and updates the saved chapter/page position instead of
creating another library row. Translation records remain isolated because
their cache keys still contain parsed document and segment IDs.

### `WebNovelSiteAdapter`

Owns all site policy:

- whether a URL belongs to the site;
- catalog/detail/chapter page classification;
- catalog, detail, and chapter-list mapping;
- relative and canonical URL construction;
- static HTTP versus rendered-page chapter loading;
- conversion to common site models;
- canonical catalog and server-page URL construction;
- server-page totals and next/previous availability.

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
  generic fallback, runtime registration, WTR URL classification, landing URL
  canonicalization, and filter-preserving page URLs.
- `WebNovelRemoteBookSourceAdapterTest` injects a fake site adapter and proves
  that connection, listing, detail, and chapter loading are delegated without
  changing the source orchestrator.
- Site implementations should add fixture-based tests for catalog, detail,
  chapter list, chapter body, URL resolution, and missing rendered content.
- `WtrLabCatalogLiveTest` is opt-in through
  `RUN_LIVE_WEB_NOVEL_TESTS=1`; it verifies that live pages 1 and 2 contain
  different books and report a catalog larger than one page.

## Relationship to offline translation

Adapters finish at clean original text. `WebNovelBatchDownloader` then passes
that original to `ContentTranslationService` and persists the result through
`OfflineNovelStorageStore`. See
[WEB_NOVEL_OFFLINE_TRANSLATION.md](WEB_NOVEL_OFFLINE_TRANSLATION.md) for the
full airplane-mode flow.
