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

For DeepSeek and other OpenAI-compatible LLM providers, a translation response
may also return `characterAliases`. Only source names that actually occur in the
submitted book text are accepted. A suggestion such as `A-Pu -> 아푸` is merged
into that book's glossary without overwriting a manual entry. The provider keeps
accepted aliases in memory for the remaining batch, while `BookGlossaryStore`
makes them available to later chapters and app restarts.

`translatedTerm` is a translation contract. Changing it changes the glossary
fingerprint in the provider ID and therefore uses a new translation cache key.
`displayTerm` is presentation-only and intentionally excluded from the
fingerprint, so changing an alias is immediate and does not spend another
network translation request.

Automatic LLM character entries initially use the same value for
`translatedTerm` and `displayTerm`. Users can edit either field later. Only the
translation spelling affects the cache fingerprint; changing the reading-only
display alias remains immediate.

## Translation flow

```text
book page
  -> LLM receives existing character spellings for this book
  -> translation response may contribute new character aliases
  -> validated aliases merged without replacing manual choices
  -> longest-match glossary lookup
  -> source names replaced by deterministic protection tokens
  -> TranslationProvider
  -> tokens restored as translatedTerm
  -> TranslationRepository cache
  -> optional displayTerm applied in ReaderSurface
  -> character alias ranges rendered with bold weight
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

The Terms page can export or share a versioned
`*.pagetuner-dictionary.json` package. The package contains its schema version,
source book identity, display title, and provider-independent entries. Import
always targets the currently open book: matching source terms keep the reader's
existing manual choice, while new characters and terms are appended. This makes
community dictionaries portable even when two users have different local file
IDs for the same work.

The reader's **Terms** sub-page uses `AdaptiveCollection`. In paged mode every row
is exactly 92 dp and the same value is passed as `estimatedPagedItemHeight`; editing is
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
- `BookGlossaryShareCodecTest`: verifies the portable schema and non-destructive
  merge behavior.
- `OpenAiCompatibleLlmTranslationProviderTest`: verifies existing alias context,
  validated discovery, and the structured character-alias response contract.
