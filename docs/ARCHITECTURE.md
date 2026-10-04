# PageTurner Architecture

PageTurner should grow by adding feature modules behind stable boundaries, not
by appending everything to the activity or Compose state.

The paging-specific dependency flow and extraction plan are documented in
[`CORE_PAGING_ARCHITECTURE.md`](CORE_PAGING_ARCHITECTURE.md). The staged
implementation and device-validation plan is in
[`CORE_PAGING_REFACTOR_PLAN.md`](CORE_PAGING_REFACTOR_PLAN.md).

## Current Boundaries

```text
core-model/ (pure Kotlin; no Android or Compose dependency)
  -> PageRequest / PageResult / PageLoader
  -> immutable page metadata and list slices
  -> aligned reader-page windows

core-content/ (shared by app, server, and future web API schema)
  -> provider/book/chapter/paragraph identities
  -> source revisions and device-independent reading anchors

core-translation/
  -> stable translation identities and content revisions
  -> translated paragraph artifacts

core-backup/
  -> backup keys and duplicate-upload decisions
  -> already-backed-up / active-job / enqueue states

MainActivity
  -> Compose app assembly and renderer side effects

document/
  -> ReaderDocument model
  -> Text / Markdown parsing
  -> EPUB package parsing
  -> PDF page rendering and native text extraction
  -> DocumentLoader for Android Uri imports

display/
  -> Color / grayscale / monochrome / e-ink high-contrast mode model
  -> App-wide color-service and monochrome-service palettes
  -> Shared bitmap transform for PDF and future image renderers

reader/
  -> Page-turn behavior model
  -> ReaderViewModel for current document, page index, and reader chrome state
  -> Manual e-ink refresh token for renderer-specific refresh hooks

settings/
  -> DataStore-backed reader settings model and persistence
  -> SettingsViewModel for exposing persistent settings to Compose

library/
  -> App-private imported book storage
  -> JSON metadata for recent books, progress, duplicate detection
  -> LibraryViewModel for list/import/open/delete/progress side effects

translation/
  -> Provider interface
  -> Google Cloud provider
  -> OpenAI-compatible LLM provider
  -> Provider factory
  -> Translation pacing
  -> Offline cache
  -> Page/document translation repository
  -> ContentTranslationService for stable named-field translation outside the reader
  -> TranslationViewModel for translation result, progress, cache status, and prefetch queue state

source/
  -> RemoteBookSource interface
  -> PageTurner Web Catalog parser/source
  -> RemoteCatalogTranslationService for remote item title/description mapping
  -> WebNovelRemoteBookSource orchestration
  -> WebNovelSiteAdapter registry with dedicated and generic site implementations
  -> WebCatalogPageService for background fetch/DOM parse/page mapping
  -> Remote library TODO model

ui/
  -> Shared UI-facing models
  -> reader surfaces in ui/reader
  -> display and page-turn controls in ui/settings
  -> translation controls in ui/translation
  -> remote source list UI in ui/source
  -> reusable status UI in ui/common
  -> localized label helpers in ui/text
  -> E-ink color tokens in ui/theme
```

## Growth Rules

- Keep Android file imports in `document/DocumentLoader`.
- Add new document formats by returning `ReaderDocument`.
- Keep translation vendors behind `TranslationProvider`.
- Keep provider construction inside `TranslationProviderFactory`.
- Translate non-reader structured content through `ContentTranslationService`.
- Keep page-turn behavior in `reader/`.
- Keep display-mode behavior in `display/` and renderer-specific pipelines.
- Keep OCR behind a future provider boundary; see `docs/OCR_PLAN.md`.
- Keep remote services behind source abstractions before adding network UI.
- Put reusable page/list rules in `:core-model`; Android and Compose types must
  never be added to that module.
- Make provider pages implement the common `PageResult<T>` contract and expose
  loading through `PageLoader<T>` or a feature service.
- Keep DOM parsing, cached JSON decoding, and image preparation off the main
  dispatcher. Publish one immutable result to the ViewModel when work completes.
- Add web novel sites through `WebNovelSiteAdapter`; keep host checks and URL
  rules out of `WebNovelRemoteBookSource` and ViewModels.
- Avoid placing new parsing, network, cache, or provider code in
  `MainActivity`.

## Next Refactor Target

`MainActivity` now launches the app and `PageTurnerApp` assembles the feature
components. Reader document/page state, persistent settings, translation state,
and local library side effects already have ViewModel boundaries. The next
structure pass should move renderer side effects behind stable app models:

- Renderer state model: PDF bitmap loading and EPUB image rendering cache.
- Persistent settings through DataStore.
