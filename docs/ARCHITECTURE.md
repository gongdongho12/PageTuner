# PageTurner Architecture

PageTurner should grow by adding feature modules behind stable boundaries, not
by appending everything to the activity.

## Current Boundaries

```text
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
  -> TranslationViewModel for translation result, progress, cache status, and prefetch queue state

source/
  -> RemoteBookSource interface
  -> PageTurner Web Catalog parser/source
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
- Keep page-turn behavior in `reader/`.
- Keep display-mode behavior in `display/` and renderer-specific pipelines.
- Keep OCR behind a future provider boundary; see `docs/OCR_PLAN.md`.
- Keep remote services behind source abstractions before adding network UI.
- Avoid placing new parsing, network, cache, or provider code in
  `MainActivity`.

## Next Refactor Target

`MainActivity` now launches the app and `PageTurnerApp` assembles the feature
components. Reader document/page state, persistent settings, translation state,
and local library side effects already have ViewModel boundaries. The next
structure pass should move renderer side effects behind stable app models:

- Renderer state model: PDF bitmap loading and future EPUB image rendering.
- Persistent settings through DataStore.
