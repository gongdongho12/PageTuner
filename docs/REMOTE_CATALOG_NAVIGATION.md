# Remote Catalog Navigation

Every remote reading system follows the same parent hierarchy:

```text
Source systems
  -> Catalog
     -> Book information
        -> Chapter reader
```

The UI must not infer the previous page from Compose history. It keeps a
`RemoteCatalogRoute` instead:

- `SourceSystems`
- `Catalog(catalogUrl)`
- `Book(catalogUrl, book)`

The chapter reader is the only full-screen route outside this model. While a
chapter is open, its `Book` route remains stored as the explicit parent.
Therefore Android back from a chapter restores the book information and table
of contents. Back from the book restores its exact catalog URL, and back from
the catalog restores the source-system list.

This also applies when the user imports directly from a catalog row. The app
records the book route before starting the import, so the book-information
step is not skipped on the way back.

## Source implementations

Navigation does not depend on WTR-Lab or a particular scraper.
`RemoteBookHierarchyResolver` is the boundary for turning a catalog book into
book metadata and chapters:

- `WebNovelBookHierarchyResolver` loads web-novel detail and its chapter list;
- `SingleDocumentBookHierarchyResolver` exposes a downloadable catalog item as
  a one-chapter book;
- `RoutingRemoteBookHierarchyResolver` selects an implementation by
  `RemoteSourceType` and uses the single-document resolver as a safe fallback.

A new web-novel, drive, RSS, or document-site implementation should register a
resolver. It should not add provider-specific navigation branches to Compose
screens.

## E-Ink behavior

Each back action replaces one complete bounded screen. Catalog and chapter
lists retain `EinkAutoFitPagingContainer`; the navigation model does not add
continuous scrolling or animated transitions.

## Tests

- `RemoteCatalogRouteTest` verifies `Book -> Catalog -> SourceSystems` and
  provider independence.
- `RemoteBookHierarchyResolverTest` verifies registered implementations and
  the one-chapter fallback used by generic catalogs.
