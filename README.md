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
- PDF native text extraction on Android 15+ maps extracted text back to
  per-page segments for translation and search workflows.
- EPUB import through the package OPF spine, normalized into text pages with
  generated table-of-contents entries.
- EPUB chapter labels and previous/next chapter navigation.
- EPUB text normalization preserves basic heading/list breaks and image
  placeholders for fast e-ink reading.
- Internal document model normalized into pages and text segments.
- Local library panel for imported books.
- Imported books are copied into app-private storage for offline reopening.
- Local metadata tracks title, format, file path, current page, page count,
  reading progress, import time, and last-opened time.
- Duplicate imports are detected by file hash and reopen the saved copy.
- Recent books can be reopened or deleted from the local library.
- The most recently opened saved book is restored on app start.
- Local library list, import, open, delete, and progress writes are owned by
  `LibraryViewModel`.
- Current-page-only rendering to keep e-ink page turns light.
- Display mode flow for color, grayscale, monochrome, and e-ink high contrast.
- App-wide display palettes for color-service and monochrome-service modes.
- PDF rendering follows the selected display mode.
- EPUB embedded bitmap images are decoded on-page and transformed through the
  selected display mode.
- Web catalog cover thumbnails are fetched for visible results and transformed
  through the selected display mode.
- Shared bitmap display transform serves as the PDF/EPUB/cover/thumbnail image
  pipeline foundation.
- Manual e-ink refresh action clears rendered page cache and re-renders the
  active page.
- PDF fit-page / fit-width controls.
- PDF page-image cache for previous/current/next pages.
- Current-book text search with previous/next match navigation.
- In-reader page bookmarks with optional names, a bookmark list, and per-book
  local persistence.
- Page-level highlights and notes with per-book local persistence.
- Highlights and notes can be exported through the Android share sheet.
- Unsupported-format, import-failure, and network-unavailable states use
  distinct reader status messages.
- Reader font size, line spacing, and page margin settings.
- DataStore-backed persistent settings for display mode, page-turn mode,
  language pair, translation provider, LLM endpoint/model, reading speed, and
  pacing mode.
- Reader document/page state is owned by `ReaderViewModel`.
- Persistent settings are exposed through `SettingsViewModel`.

### Translation

- Translation is separated behind a `TranslationProvider` interface.
- Current provider options:
  - Google Cloud Translation API
  - Google Web Translate HTML endpoint
  - OpenAI-compatible LLM API
- Google Web Translate HTML provider uses a user-supplied API key and does not
  embed copied browser/session headers in source.
- LLM provider accepts:
  - API key
  - chat-completions-compatible endpoint
  - model name
- Provider-specific cache keys keep Google, Google Web HTML, and LLM
  translation results separate.
- Korean and English translation presets:
  - auto to Korean
  - English to Korean
  - Korean to English
  - auto to English
- Reading-speed-based request pacing.
- Fast mode for quicker translation.
- Offline prefetch mode for saving translations ahead of time.
- Whole-document offline prefetch runs through a visible translation queue.
- Prefetch queue controls:
  - pause
  - resume
  - cancel
  - retry failed pages
- Provider health check validates required Google, Google Web HTML, and LLM
  settings before use.
- JSON-file translation cache in app-private storage.
- Cached page loading for offline reading.
- Per-document translation cache status for the active provider/language pair.
- Clear translation cache action for the active document and provider/language
  pair.
- Translation display modes:
  - original only
  - translation only
  - original and translation together
- Provider configuration status for missing API keys or LLM endpoint/model
  fields.
- Translation result, cache status, progress, and busy state are owned by
  `TranslationViewModel`.

### Localization

- English UI is the default.
- Korean UI is available through Android locale resources.
- User-facing app strings are split between:
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-ko/strings.xml`

### Remote Library

- In-app remote library panel for PageTurner Web Catalog loading, search,
  cached catalog reload, and local-library import.
- `RemoteBookSource` interface for Drive, FTP, and web catalog connectors.
- PageTurner Web Catalog v0.1 parser/source with relative URL normalization,
  search, refresh, and injectable download support.
- App-private web catalog metadata cache for offline catalog recall.
- Planned source types:
  - Google Drive
  - FTP / FTPS
  - PageTurner Web Catalog
- Draft remote-source spec: [docs/REMOTE_SOURCES_TODO.md](docs/REMOTE_SOURCES_TODO.md)
- Static sample catalog:
  - [examples/pagetuner-catalog/catalog.json](examples/pagetuner-catalog/catalog.json)
- Local emulator trial URL:
  - `http://10.0.2.2:8088/catalog.json`

### Developer Docs

- Product TODO:
  [docs/PRODUCT_TODO.md](docs/PRODUCT_TODO.md)
- Architecture notes:
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Translation provider extension guide:
  [docs/TRANSLATION_PROVIDERS.md](docs/TRANSLATION_PROVIDERS.md)
- Remote library and web catalog TODO:
  [docs/REMOTE_SOURCES_TODO.md](docs/REMOTE_SOURCES_TODO.md)
- OCR plan for scanned PDFs:
  [docs/OCR_PLAN.md](docs/OCR_PLAN.md)

## Current Limitations

- PDF native text extraction depends on Android 15+ platform APIs. Scanned PDFs
  still need OCR implementation.
- EPUB support is text-first with basic embedded bitmap image rendering.
  Complex layout, custom fonts, advanced CSS, and SVG rendering are not
  implemented yet.
- Google Drive and FTP connectors are TODO/planning items.
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

1. Add secure credential storage before using real API keys in production.
2. Add FTP and Google Drive connectors.
3. Add source account management for remote libraries.
4. Add unsupported-format and network-unavailable error surfaces.
5. Choose and add the project license before distribution.
