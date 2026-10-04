# Web Novel Site Adapter Architecture

Navigation and chapter hierarchy are source-independent. See
[Remote Catalog Navigation](REMOTE_CATALOG_NAVIGATION.md) before adding a new
catalog or site adapter; Compose screens must not own provider-specific back
stacks.

The complete catalog-to-offline runtime and its pure Kotlin integration-test
boundary are documented in [Web Novel End-to-End Flow](WEB_NOVEL_END_TO_END_FLOW.md).
Executed test results and the real-site verification diagram are in
[Web Novel Provider Test Report](WEB_NOVEL_TEST_REPORT.md).
The installable manifest + adapter factory contract is documented in
[Web Novel Provider Plugins](WEB_NOVEL_PROVIDER_PLUGINS.md).

Web novel support is split into a site-independent source orchestrator and
site-specific adapters. Adding a site must not require conditionals in
`WebNovelRemoteBookSource`, the catalog ViewModel, or Compose UI.

## Layers

```text
RemoteBookSource / UI / offline downloader
  -> WebNovelRemoteBookSource
     -> WebNovelSiteAdapterRegistry
        -> WtrLabSiteAdapter
        -> NovelBuddySiteAdapter
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

### Provider-backed keyword and genre search

The Compose layer consumes `WebNovelCatalogCapabilities` and
`WebNovelCatalogRequest`; it does not know whether a provider uses numeric
genre IDs or slugs. Each adapter owns its search parameters, available genre
options, pagination URL, and whether extra provider controls are supported.

WTR-Lab search must query the remote finder rather than filter the ten books
already loaded in the current server page:

```text
/{language}/novel-finder?text={keyword}&gi={genreId}&page={page}
```

- `WtrLabCatalogQueryParams` is the only place that constructs and parses the
  finder URL. It preserves keyword, genre, sort, status, language, and page
  while navigating.
- The `gi` values are WTR-Lab's live genre IDs. They are not positional IDs
  invented by the client; for example Fantasy is `9`, Romance is `22`,
  Xianxia is `37`, and Yuri is `40`.
- The finder currently searches the translated/raw title fields exposed by the
  site. Parsed author and description metadata are retained, and generic local
  catalog fallback search also checks description and tags.
- WTR-Lab leaves the unfiltered global count in finder `pageProps`. Search
  pagination therefore uses the finder's explicit page links and does not show
  that stale count as a result count.
- E-Ink source management separates `Search` from `Sort & status`; genre uses a
  bounded stepper rather than rendering forty chips in one clipped panel.

NovelBuddy uses its own server-backed browse endpoint:

```text
/search?q={keyword}&genres={genreSlug}&status={status}&page={page}
```

`NovelBuddySiteAdapter` preserves supported search filters, provides the live
genre slugs through capabilities, and maps `/home` to `/search`. The latter is
important: the home page is a collection of sections, while `/search` is the
complete 24-items-per-page catalog with real totals and server pagination.

Fixture tests cover URL round-tripping, real genre IDs, encoded pagination
links, and description fallback search. Opt-in live tests request a combined
keyword + genre search and compare two remote result pages.

### Work and chapter identity

Every chapter returned by `WebNovelRemoteBookSource` carries optional grouping
metadata:

- `seriesId`: canonical novel-detail URL shared by every chapter;
- `seriesTitle`: parent work title;
- `chapterNumber`: source chapter number.

Display titles are never identities. `WebNovelChapterKeys` derives a stable
chapter ID from the canonical chapter URL and exact chapter number, while
translation and offline-storage keys also include the parent `seriesId`. Two
books may therefore both contain `Chapter 1` (or the same custom title) without
sharing cached text, translations, download state, or quick-jump selection.
Previously saved WTR packages that used the ambiguous account + `chapter_N`
key are intentionally not attached to a newly resolved work because their
original parent book cannot be determined safely.

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
- provider capabilities and provider-owned search URL construction;
- optional dedicated full chapter-index loading;
- conversion to common site models;
- canonical catalog and server-page URL construction;
- server-page totals and next/previous availability.

`WtrLabSiteAdapter` uses WTR-LAB Next.js DOM models. Chapter loading defaults to
`HttpThenWebView`: it calls the site's lightweight reader endpoint and converts
the returned JSON body, glossary markers, and text patches directly into clean
paragraphs. A rendered WebView is retained as a compatibility fallback for
browser verification, response-shape changes, or temporary endpoint failures.
`HttpOnly` and `WebViewOnly` are available for diagnostics and provider tests.
`NovelBuddySiteAdapter` is `HttpOnly`: its Next.js `__NEXT_DATA__` contains
catalog, detail, and chapter bodies, while
`https://api.novelbuddy.me/titles/{id}/chapters` supplies the complete chapter
index. The adapter uses the API index instead of mistaking the 50 chapters
embedded in the detail page for the whole work.
`GenericWebNovelSiteAdapter` uses semantic link/text extraction and the existing
scraper registry as a fallback.

Both built-in Next.js providers share `NextJsPageData` for extracting page
props. Site-specific response keys and normalization remain in their DOM
scrapers (`WtrLabDomScraper` and `NovelBuddyDomScraper`).

The shared `WebNovelChapterLoadPolicy` defines the fallback and cancellation
contract. New providers should select a `WebNovelChapterLoadStrategy` and keep
their HTTP request/response format inside their adapter. Do not start WebView
work after a successful HTTP extraction, and never swallow coroutine
cancellation while falling back.

### `WebNovelScraperEngine`

This remains the lower-level HTML parsing extension point. It is useful for
custom DOM rules and parsing static HTML, but it does not own fetching,
JavaScript rendering, or URL policy. A site with any non-trivial URL or loading
behavior should get a full `WebNovelSiteAdapter`.

## Adding another site

1. Implement `WebNovelSiteAdapter` in `source/webnovel`.
2. Give it a unique stable `id` and a strict `supports(url)` host check.
3. Implement all three page classifications and common model mappings.
4. Declare `WebNovelCatalogCapabilities` and implement provider-owned catalog
   request parsing/search URL construction when remote search exists.
5. Override `loadChapters` if the detail page does not embed the complete index.
6. Select `HttpOnly`, `WebViewOnly`, or `HttpThenWebView` and implement the
   provider's lightweight HTTP path before using `RenderedChapterLoader`.
7. Register it before the generic fallback:

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
  generic fallback, runtime registration, WTR/NovelBuddy URL classification, landing URL
  canonicalization, and filter-preserving page URLs.
- `NovelBuddyDomScraperTest` covers catalog totals, metadata, embedded and full
  chapter indexes, first-chapter resolution, and body extraction.
- `NovelBuddySiteAdapterTest` covers capabilities, route classification,
  provider-owned search URLs, and dedicated index loading.
- `WebNovelRemoteBookSourceAdapterTest` injects a fake site adapter and proves
  that connection, listing, detail, and chapter loading are delegated without
  changing the source orchestrator.
- Site implementations should add fixture-based tests for catalog, detail,
  chapter list, chapter body, URL resolution, and missing rendered content.
- `WtrLabCatalogLiveTest` is opt-in through
  `RUN_LIVE_WEB_NOVEL_TESTS=1`; it verifies that live pages 1 and 2 contain
  different books and report a catalog larger than one page.
- `NovelBuddyFullFlowLiveTest` uses the same opt-in flag and performs real
  search → detail → complete chapter index → original chapter body requests,
  with no WebView.

## Relationship to offline translation

Adapters finish at clean original text. `WebNovelBatchDownloader` then passes
that original to `ContentTranslationService` and persists the result through
`OfflineNovelStorageStore`. See
[WEB_NOVEL_OFFLINE_TRANSLATION.md](WEB_NOVEL_OFFLINE_TRANSLATION.md) for the
full airplane-mode flow.
