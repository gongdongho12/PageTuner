# Book Glossary and Reader Return Flow

## Goal

Each saved work owns one glossary shared by all of its chapters. It supports two
independent reading needs:

- keep character, place, and terminology spelling stable during translation;
- replace a difficult translated term with a personal reading alias without
  translating the page again.

Web-novel chapters already resolve to one stable local book ID through
`RemoteLibraryIdentity`. The glossary uses that local book ID, so opening a new
chapter of the same work does not create another dictionary.

## Entry contract

```text
sourceTerm       original source spelling, e.g. Qin Feng
translatedTerm   protected translation spelling, e.g. 진풍
displayTerm      optional reading-only alias, e.g. 주인공
kind             Character / Place / Term
```

`translatedTerm` is a translation contract. Changing it changes the glossary
fingerprint in the provider ID and therefore uses a new translation cache key.
`displayTerm` is presentation-only and intentionally excluded from the
fingerprint, so changing an alias is immediate and does not spend another
network translation request.

## Translation flow

```text
book page
  -> longest-match glossary lookup
  -> source names replaced by deterministic protection tokens
  -> TranslationProvider
  -> tokens restored as translatedTerm
  -> TranslationRepository cache
  -> optional displayTerm applied in ReaderSurface
```

Latin names use word boundaries, so an entry for `Qin` does not alter
`Qinling`. Longer terms are protected first to keep `Qin Feng` from being
partially consumed by an entry for `Qin`.

The common provider decorator is `GlossaryTranslationProvider`. The regular
reader and `ContentTranslationService` can both use it. Offline web-novel batch
download resolves the series local-book ID and supplies its glossary to the
content service, so downloaded translated chapters keep the same names.

## Storage and UI

`BookGlossaryStore` writes one atomic JSON file per hashed book ID under the app
files directory. Invalid or missing files fall back to an empty glossary for
that book.

The reader's **Terms** sub-page uses `EinkAutoFitPagingContainer`. Every row is
exactly 92 dp and the same value is passed as `estimatedItemHeight`; editing is
kept in a dialog so every list item stays fully visible on a bounded E-Ink
viewport.

## Reader return destination

Reader entry records an explicit parent destination:

- local book -> Local library;
- remote chapter -> the parent book detail and chapter list, followed by its
  exact catalog and source system.

The parent `RemoteBookItem` is held at the application level instead of only in
the web panel's `remember` state. Android back from reader mode first restores
controls and this parent destination, then clears stale navigation frames.
Android back inside a web-novel detail returns to its catalog list, matching the
visible back action.

The provider-independent route and hierarchy-resolver contract are documented
in [Remote Catalog Navigation](REMOTE_CATALOG_NAVIGATION.md).

## Tests

- `GlossaryTextProcessorTest`: protection, longest-match behavior, word
  boundaries, display aliases, and cache fingerprint behavior.
- `GlossaryTranslationProviderTest`: verifies the vendor sees protected text
  and the caller receives the fixed translated spelling.
- `BookGlossaryStoreTest`: verifies per-book JSON persistence and isolation.
