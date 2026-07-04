# PageTurner Architecture

PageTurner should grow by adding feature modules behind stable boundaries, not
by appending everything to the activity.

## Current Boundaries

```text
MainActivity
  -> Compose app assembly and temporary UI state

document/
  -> ReaderDocument model
  -> Text / Markdown parsing
  -> EPUB package parsing
  -> PDF page rendering and native text extraction
  -> DocumentLoader for Android Uri imports

display/
  -> Color / grayscale / monochrome / e-ink high-contrast mode model

reader/
  -> Page-turn behavior model

settings/
  -> DataStore-backed reader settings model and persistence

library/
  -> App-private imported book storage
  -> JSON metadata for recent books, progress, duplicate detection

translation/
  -> Provider interface
  -> Google Cloud provider
  -> OpenAI-compatible LLM provider
  -> Provider factory
  -> Translation pacing
  -> Offline cache
  -> Page/document translation repository

source/
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
components. The next structure pass should move state and side effects behind
stable app models:

- `ReaderViewModel`: current document, page index, PDF bitmap loading.
- `SettingsViewModel`: display mode, page-turn mode, language/provider fields.
- `TranslationViewModel`: page translation, prefetch progress, cache status.
- Persistent settings through DataStore.
