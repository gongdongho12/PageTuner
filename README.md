# PageTurner

English is the default project documentation language.

Korean: [README.ko.md](README.ko.md)

Working checklist: [TODO.md](TODO.md)

PageTurner is an Android e-ink reader prototype focused on page-based reading,
paced translation, and offline reuse of translated text. The codebase is an
independent implementation; GPL readers may be used as product references only,
not as source-code bases.

## Implemented Features

### Reader

- Android native app built with Kotlin and Jetpack Compose.
- E-ink-friendly high-contrast UI with restrained motion.
- Page-based reading surface instead of vertical document scrolling.
- Previous/next page controls.
- Hardware key and D-pad page turning for left/right and PageUp/PageDown style
  controls.
- Configurable page-turn behavior:
  - left tap previous, right tap next
  - left tap next, right tap previous
  - buttons only
- Quick hide/show for reader controls.
- Document details dialog with format, page count, progress, and local file
  size when available.
- Text and Markdown import through Android's document picker.
- PDF import and page-image viewing through Android `PdfRenderer`.
- EPUB import through the package OPF spine, normalized into text pages.
- Internal document model normalized into pages and text segments.
- Local library panel for imported books.
- Imported books are copied into app-private storage for offline reopening.
- Local metadata tracks title, format, file path, current page, page count,
  reading progress, import time, and last-opened time.
- Duplicate imports are detected by file hash and reopen the saved copy.
- Recent books can be reopened or deleted from the local library.
- The most recently opened saved book is restored on app start.
- Current-page-only rendering to keep e-ink page turns light.
- Display mode flow for color, grayscale, monochrome, and e-ink high contrast.
- PDF rendering follows the selected display mode.
- PDF fit-page / fit-width controls.
- PDF page-image cache for previous/current/next pages.
- Reader font size, line spacing, and page margin settings.
- DataStore-backed persistent settings for display mode, page-turn mode,
  language pair, translation provider, LLM endpoint/model, reading speed, and
  pacing mode.

### Translation

- Translation is separated behind a `TranslationProvider` interface.
- Current provider options:
  - Google Cloud Translation API
  - OpenAI-compatible LLM API
- LLM provider accepts:
  - API key
  - chat-completions-compatible endpoint
  - model name
- Provider-specific cache keys keep Google and LLM translation results separate.
- Korean and English translation presets:
  - auto to Korean
  - English to Korean
  - Korean to English
  - auto to English
- Reading-speed-based request pacing.
- Fast mode for quicker translation.
- Offline prefetch mode for saving translations ahead of time.
- JSON-file translation cache in app-private storage.
- Cached page loading for offline reading.

### Localization

- English UI is the default.
- Korean UI is available through Android locale resources.
- User-facing app strings are split between:
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-ko/strings.xml`

### Remote Library Planning

- In-app TODO panel for future remote library sources.
- Planned source types:
  - Google Drive
  - FTP / FTPS
  - PageTurner Web Catalog
- Draft remote-source spec: [docs/REMOTE_SOURCES_TODO.md](docs/REMOTE_SOURCES_TODO.md)
- Static sample catalog:
  - [examples/pagetuner-catalog/catalog.json](examples/pagetuner-catalog/catalog.json)

### Developer Docs

- Product TODO:
  [docs/PRODUCT_TODO.md](docs/PRODUCT_TODO.md)
- Architecture notes:
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Translation provider extension guide:
  [docs/TRANSLATION_PROVIDERS.md](docs/TRANSLATION_PROVIDERS.md)
- Remote library and web catalog TODO:
  [docs/REMOTE_SOURCES_TODO.md](docs/REMOTE_SOURCES_TODO.md)

## Current Limitations

- PDF viewing is image-based. Text extraction, OCR, and PDF translation are not
  implemented yet.
- EPUB support is a basic text-first reader. Complex layout, embedded media,
  custom fonts, and advanced CSS are not rendered yet.
- Google Drive, FTP, and web catalog connectors are TODO/planning items.
- API keys are entered per session and are not persisted yet; production storage
  should use a more secure credential layer.
- The LLM provider expects OpenAI-compatible chat completions JSON.
- No product license has been selected yet.

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Suggested Next Steps

1. Add a local library that copies imports into app-private storage and restores
   recent books.
2. Add full PDF and EPUB reader adapters into the existing `ReaderDocument`
   model.
3. Add the PageTurner Web Catalog connector first, because it is easy to test
   locally.
4. Add secure credential storage before using real API keys in production.
5. Choose and add the project license before distribution.
