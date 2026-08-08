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
        -> TranslationProvider
        -> TranslationCache
```

### `TranslationProvider`

Vendor boundary. Implementations only translate `TextSegment` requests. Current
implementations support Google Cloud, Google Web Translate, and an
OpenAI-compatible LLM endpoint.

### `TranslationRepository`

Reader-document boundary. It performs cache lookup, request batching, pacing,
progress publication, and cache writes. The reader and whole-document prefetch
queue use this directly because they already operate on `ReaderDocument`.

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

## Adding a translation provider

1. Implement `TranslationProvider` with a stable `id`.
2. Add the provider kind and construction branch to
   `TranslationProviderFactory`.
3. Map transport/configuration errors to the existing provider error types.
4. Add request/response, error, and cache-key tests.
5. Do not add vendor checks to a ViewModel or source adapter.
