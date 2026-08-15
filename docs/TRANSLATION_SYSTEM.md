# Translation System Architecture

PageTurner has two translation entry points that share the same providers,
cache keys, pacing, and segment rules:

- reader-page translation through `TranslationRepository`;
- arbitrary structured content through `ContentTranslationService`.

Remote sources must use `ContentTranslationService`. They must not construct a
synthetic `ReaderDocument`, call a provider directly, or implement their own
cache keys inside a ViewModel.

Catalog-specific field mapping is isolated in `RemoteCatalogTranslationService`;
the catalog ViewModel only owns UI job and progress state.

## Component boundaries

```text
UI / ViewModel / download workflow
  -> ContentTranslationService
     -> TranslationDocumentFactory (stable fields -> <=400-char segments)
     -> TranslationRepository
        -> GlossaryTranslationProvider (optional per-book decorator)
        -> TranslationProvider
        -> TranslationCache
```

## Per-book names and terminology

Saved works can own a `BookGlossary` containing character names, places, and
domain terms. `GlossaryTranslationProvider` protects matching source terms
before the vendor request and restores the requested fixed spelling after the
response. The decorator's provider ID includes the glossary translation
fingerprint, preventing a cached page from silently retaining an older spelling.

An optional display alias is applied by `ReaderSurface` after cache lookup. It
does not participate in the fingerprint because it changes presentation only.
Offline web-novel batch translation loads the same series glossary before it
creates `ContentTranslationService`. See
[Book Glossary and Reader Return Flow](BOOK_GLOSSARY_AND_READER_FLOW.md).

### `TranslationProvider`

Vendor boundary. Implementations only translate `TextSegment` requests. Current
implementations support Google Cloud, Google Web Translate, DeepSeek, and an
OpenAI-compatible LLM endpoint. See
[DeepSeek Translation](DEEPSEEK_TRANSLATION.md) for the local `.env` and
production subscription boundary.

### `TranslationRepository`

Reader-document boundary. It performs cache lookup, request batching, pacing,
progress publication, and cache writes. The reader and whole-document prefetch
queue use this directly because they already operate on `ReaderDocument`.
Rolling translation and offline prefetch pass 10-page groups to
`translatePages`. `TranslationRequestBatcher` combines segments across page
boundaries and only splits at the 24-segment or 24,000-character safety limit,
then restores each translated segment to its original page cache.

### Rolling reader prefetch

Interactive reading uses a bounded rolling window instead of translating the
whole document immediately:

1. A reader translation request queues the current page and the following nine
   pages (10 pages total).
2. The window is translated as one provider request when it fits the shared
   segment and character safety limits.
3. Every page has a runtime flag: `Queued`, `Translating`, `Ready`, or `Failed`.
4. When the reader reaches page offset 5 in that window, the next non-overlapping
   10-page window is queued.
5. A large page jump starts a new window at the current page rather than filling
   every skipped window.
6. The rolling worker does not set the application's blocking `busy` flag, so
   page turns and reading remain available while look-ahead pages are cached.

`RollingTranslationPolicy` owns window calculation and has no Android or
provider dependency. `TranslationViewModel` owns runtime flags and the single
background worker. `TranslationRepository` remains the only cache/provider
boundary.

The flags themselves are process state. `Ready` is durable because the actual
translation is written to `TranslationCache`; after an app restart, a queued
window is served from cache and its flags are reconstructed without another
provider request.

### Initial reader loading lifecycle

`ReaderTranslationLoadState` keeps the first visible page in one continuous
E-Ink loading lifecycle:

```text
CheckingCache -> Queued -> Translating -> Ready
             \-> Missing
             \-> Failed
```

Opening a saved chapter starts at `CheckingCache`, before asynchronous cache
lookup begins. An automatic web-novel translation then owns the state through
queue registration and provider work. The pending document marker is cleared
only after translated text is visible or a recoverable error is published; the
rolling worker's non-blocking `busy=false` state must not end initial loading.

The reader uses `EinkOperationIndicator` for all three loading stages. Missing
and failed stages replace it with the persistent translate/retry action. State
is keyed by document ID and page index so a late result cannot hide the loading
state of a newly selected page.

### `ContentTranslationService`

Common remote-content boundary. Callers submit stable named fields such as
`book-id:title`, `book-id:description`, or `chapter-id:body`. The default
implementation:

1. splits every field into bounded translation segments;
2. creates stable document and segment IDs;
3. delegates batching, pacing, and caching to `TranslationRepository`;
4. reconstructs translated values under the original field IDs.

The service is used by both web catalog translation and offline chapter
download. Future RSS, blog, document-site, or additional web-novel sources
should use the same API.

`DefaultRemoteCatalogTranslationService` is a small domain implementation on
top of this common API. It maps `RemoteBookItem` title/description fields to
stable IDs and maps the result back to `CatalogItemTranslation`.

## Usage

```kotlin
val service = ContentTranslationServiceFactory.create(context, settings)
val result = service.translate(
    ContentTranslationRequest(
        namespace = "my-source-v1",
        title = "Catalog",
        fields = listOf(
            TranslatableField("book-42:title", book.title),
            TranslatableField("book-42:summary", book.summary),
        ),
    ),
    settings,
)

val translatedTitle = result.values["book-42:title"]
```

The namespace is part of the cache document ID. Keep it stable and add a new
version only when field semantics change. Field IDs must be stable remote IDs,
not visible list positions, and must be unique within one request.

## Language and cache contract

- Source and target languages come from `TranslationSettings`.
- Cache identity includes document ID, segment ID, source language, target
  language, and provider ID.
- A book glossary translation fingerprint is part of the decorated provider ID.
- Changing the target language creates a separate cached translation.
- Repeating the same request can be served entirely from cache.
- Long fields are reassembled in original chunk order.
- Blank fields are omitted instead of sending empty provider requests.

## Testing

`ContentTranslationServiceTest` uses a recording provider and in-memory cache
to verify:

- named-field reconstruction;
- the 400-character provider segment bound;
- stable cache reuse without a second provider call;
- cache separation across target languages.

`RemoteCatalogTranslationServiceTest` separately verifies remote item field IDs
and result mapping without a network provider.

Provider-specific tests remain alongside their provider implementations. Any
new remote translation consumer should add a test proving it uses the common
service or accepts `ContentTranslationService` as an injectable dependency.

`ReaderTranslationLoadStateTest`, `ReaderTranslationLoadPolicyTest`, and
`TranslationViewModelTest` cover initial cache loading, pending ownership,
rolling translation completion, and terminal states.

## Adding a translation provider

1. Implement `TranslationProvider` with a stable `id`.
2. Add the provider kind and construction branch to
   `TranslationProviderFactory`.
3. Map transport/configuration errors to the existing provider error types.
4. Add request/response, error, and cache-key tests.
5. Do not add vendor checks to a ViewModel or source adapter.
