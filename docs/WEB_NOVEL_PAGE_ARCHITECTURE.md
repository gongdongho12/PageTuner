# Web Novel Page Architecture

This document defines the page boundary and rendering contracts for remote web
novel flows. The goal is to make refresh, remote-page navigation, viewport-page
navigation, and loading feedback independent from one another.

## 1. Route hierarchy

```text
RemoteCatalogRoute.SourceSystems
  -> RemoteCatalogRoute.Catalog(catalogUrl)
     -> RemoteCatalogRoute.Book(catalogUrl, book)
        -> main Reader screen
```

Each route is one full E-Ink page. `RemoteCatalogRoute.pageStateKey()` gives the
route a stable state bucket, so returning from a book to its catalog restores
page-local choices instead of constructing a different layout.

## 2. Page responsibilities

| Page | Owns | Must not own |
| --- | --- | --- |
| Source systems | source selection, direct URL dialog, search/sort form | DOM parsing, book-detail loading |
| Catalog | current server result page, local language filter, viewport pager | chapter list, reader state |
| Book | atomic detail/chapter load state, chapter filter, quick jump | catalog server page, reader pagination |
| Reader | document page and rolling translation window | remote catalog or chapter-list paging |

`WebNovelBookRoutePage` is the route-owned book loader. It exposes one atomic
`Loading`, `Content`, or `Error` state instead of changing book, chapters,
loading, and error fields in separate recompositions.

## 3. The two paging layers

Remote and viewport paging are intentionally different:

```text
remote page request (provider page=12)
  -> RemoteCatalogPagingState
  -> stable remote-pager slot
  -> current remote page items
  -> EinkPagingState
  -> auto-fit viewport page
```

- `EinkRemoteCatalogPagerSlot` always occupies the same height. Receiving the
  first server response cannot push the item list down.
- `EinkPagingState` belongs to the page, not to an `items` list instance.
  Refreshing equivalent data therefore keeps the viewport page.
- Filter/search keys intentionally create a new `EinkPagingState`, so a new
  result set starts at its first viewport page.
- When a shorter result set makes the old page unreachable, the state clamps
  to the last valid page.

## 4. Stable rendering contract

Transient work must not participate in normal page measurement.

`EinkStablePageContent` gives the body a fixed remaining viewport and draws
loading/error feedback as an opaque overlay. Showing `Fetching DOM`,
`Applying results`, or batch-translation progress no longer changes the number
of rows that fit below it.

Every paged row still follows the standard E-Ink contract:

1. the page root uses `fillMaxSize()`;
2. the stable page body owns `weight(1f)`;
3. the auto-fit pager owns the remaining bounded height;
4. actual row height equals `estimatedItemHeight`;
5. no vertical scrolling is introduced.

## 5. Refresh rules

| Event | Remote page | Viewport page | Layout geometry |
| --- | --- | --- | --- |
| Refresh current catalog | unchanged | preserved when valid | unchanged |
| Select another remote page | changed | reset to first | unchanged |
| Change keyword/genre/language | page 1 | reset to first | unchanged |
| Loading/error appears | unchanged | unchanged | unchanged; overlay only |
| Return Book -> Catalog | restored | restored from page state bucket | unchanged |

## 6. File boundaries

```text
source/RemoteCatalogRoute.kt                 route hierarchy + page state key
ui/screen/WebNovelScreen.kt                  state/callback adapter
ui/source/RemoteSourcesTodoPanel.kt          route host + source systems page
ui/source/WebCatalogPagePanel.kt             catalog page
ui/source/WebNovelBookRoutePage.kt           atomic book route loader
ui/source/WebNovelDetailPagePanel.kt         book content and chapter viewport
ui/common/EinkStablePageContent.kt           stable body/overlay contract
ui/common/EinkRemoteCatalogPager.kt          fixed server-pager slot
ui/common/EinkAutoFitPagingContainer.kt      persistent viewport paging state
```

New providers should plug into the existing source/adapter interfaces. They
must not add provider-specific paging state to these Compose pages. Search and
genre widgets read `WebNovelCatalogCapabilities`; numeric WTR genre IDs and
NovelBuddy genre slugs never leak into the widget implementation.

## 7. Regression checks

- refresh a catalog while viewing viewport page 2 or later;
- switch remote page, then refresh that same page;
- filter chapters and clear the filter;
- enter a book and return to its catalog;
- exercise loading, success, empty, and error states;
- verify identical chapter titles remain separate;
- run `testDebugUnitTest`, `lintDebug`, and `assembleDebug`.
