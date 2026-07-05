# PageTurner Product TODO

This list tracks the baseline work PageTurner should have even when a feature is
not explicitly requested. Priorities are scoped for an Android e-ink reader with
translation and offline-first reading.

## P0: Reader Core

- Done: split `MainActivity` UI into feature components.
  - `ui/reader`: reader header, pager, surface, page tap zones
  - `ui/settings`: page-turn settings, translation settings
  - `ui/source`: remote source TODO/list UI
- Done: add persistent settings.
  - display mode
  - page-turn mode
  - source/target language
  - translation provider kind
  - LLM endpoint/model
  - reading speed
  - translation pacing mode
  - pending with local library: last opened document
- Done: add local library.
  - imported book list
  - title, format, file path, last opened time
  - current page
  - reading progress percentage
- Done: add robust file import.
  - copy imported files into app-private storage
  - stable document id from file hash
  - duplicate detection
  - safe delete from local library
- Done: add basic search inside current book for text-based formats.
- Done: add in-reader bookmarks.
  - done: page bookmark
  - done: named bookmark
  - done: bookmark list per document
  - done: persist bookmark list per local book
- Done: add page-level highlights and notes.
  - done: page highlight
  - done: note text
  - done: annotation list per document
  - done: persist annotation list per local book
  - done: export notes/highlights through Android share
- Done: add reader preferences.
  - display mode
  - font size
  - line spacing
  - margin
  - pending: e-ink contrast level

## P0: Format Support

- Text viewer.
  - plain text
  - UTF-8 first, fallback charset detection later
- Markdown viewer.
  - headings
  - paragraphs
  - blockquotes
  - lists
  - code blocks as readable text
- EPUB viewer.
  - done: OPF spine parsing
  - done: chapter navigation
  - done: table of contents
  - done: basic heading/list text normalization
  - done: image placeholders
  - pending: embedded image rendering
  - better HTML entity handling
- PDF viewer.
  - page image rendering
  - monochrome/e-ink rendering
  - page cache around current page
  - zoom presets
  - fit width / fit page
- PDF text layer.
  - done: extract text when available on Android 15+
  - done: map extracted text to page for translation
  - done: OCR plan for scanned PDFs
  - OCR TODO for scanned PDFs

## P0: Translation

- Keep all translation behind `TranslationProvider`.
- Done: add provider settings persistence.
- Done: add Google Web Translate HTML provider.
- Done: add provider health check.
  - done: validate required API key/endpoint/model fields locally
  - done: explain provider failures by category
  - pending: perform optional network health request
- Done: add translation cache management.
  - done: per-document cache status
  - done: clear translation cache for active provider/language
  - done: crash-safer temporary-file cache writes
  - pending: clear all provider-specific cache
- Done: add offline prefetch queue.
  - done: whole document
  - done: pause/resume/cancel
  - done: progress list
  - done: retry failed pages
  - pending: selected page ranges
- Done: add translation display modes.
  - done: original only
  - done: translation only
  - done: original + translation
- Add fallback behavior.
  - show cached translation when offline
  - skip already cached pages
  - done: retry failed page batches

## P1: E-Ink Performance

- Avoid continuous scrolling in reader surfaces.
- Render only the current page by default.
- Done: add small page cache.
  - previous page
  - current page
  - next page
- Add monochrome bitmap pipeline.
  - done: color passthrough mode
  - done: threshold mode
  - done: grayscale mode
  - done: high-contrast mode
  - done: shared bitmap transform for PDF and future image renderers
- Reduce recomposition churn.
  - done: move reader document/page state into `ReaderViewModel`
  - done: move persistent settings into `SettingsViewModel`
  - done: move translation result/cache/progress state into `TranslationViewModel`
  - done: move library side effects into `LibraryViewModel`
  - pending: keep renderer state separate from settings state
- Add no-animation mode as default.
- Done: add manual refresh hooks for devices that support explicit e-ink refresh.
- Add memory guard for large PDFs.
  - capped bitmap size
  - bitmap recycle strategy
  - avoid holding full document images

## P1: Remote Sources

- Done: define `RemoteBookSource` interface.
  - connect
  - list
  - search
  - download
  - refresh
- Done: implement PageTurner Web Catalog first.
  - done: static `catalog.json`
  - done: parser/source abstraction
  - done: local HTTP fetch UI
  - done: import selected item
  - done: catalog metadata cache
- Done: implement FTP / FTPS source core.
  - done: passive mode
  - done: username/password and anonymous defaults
  - done: folder browsing
  - done: file download through `RemoteBookSource`
  - pending: account-management UI wiring
- Implement Google Drive source.
  - OAuth
  - list supported files
  - download selected files
- Add source account management.
  - add source
  - edit source
  - remove source
  - refresh source

## P1: App Architecture

- Add view models.
  - done: `ReaderViewModel`
  - done: `LibraryViewModel`
  - done: `TranslationViewModel`
  - done: `SettingsViewModel`
- Add repositories.
  - `DocumentRepository`
  - `LibraryRepository`
  - `SettingsRepository`
  - `RemoteSourceRepository`
- Add persistence layer.
  - DataStore for settings
  - Room or JSON store for library metadata
  - app-private files for imported books
- Add dependency boundaries.
  - provider factory
  - document loader factory
  - renderer factory
- Keep UI components stateless where practical.

## P1: UX Basics

- Add first-run empty state.
- Add local library screen.
- Add recent books.
- Add open/import actions from the library.
- Add document details screen.
  - title
  - format
  - page count
  - translation cache status
  - source path
- Add reader top/bottom controls that can hide.
- Add error surfaces.
  - done: failed import
  - done: unsupported format
  - missing API key
  - done: network unavailable
  - done: current-page translation retry action
- Add keyboard/D-pad page turning for e-ink devices with buttons.

## P2: Advanced Reader Features

- Advanced EPUB image layout controls.
- PDF outline/bookmarks.
- Advanced text-range highlights.
- Advanced notes.
- Dictionary lookup.
- Advanced notes/highlights export formats.
- Per-book reading settings.
- Theme profiles.
  - high contrast
  - soft contrast
  - night mode for non-e-ink devices

## P2: Translation Quality

- Glossary support.
- Per-book terminology rules.
- Custom LLM prompt profile.
- Translation memory export/import.
- Side-by-side paragraph alignment.
- Batch tuning per provider.
- Cost estimate before whole-document prefetch.

## P2: Release Readiness

- Choose project license.
- Add dependency license report.
- Add privacy notes for translation providers.
- Add secure credential storage.
- Add backup/restore policy.
- Add crash-safe cache writes.
- Add CI build.
- Add instrumented tests for import and reader navigation.

## Suggested Implementation Order

1. Add local library and app-private file import.
2. Add translation queue and cache management UI.
3. Wire PageTurner Web Catalog import into the local library UI.
4. Add FTP source.
5. Add Google Drive source.
6. Add secure credential storage.
7. Add release/license/privacy groundwork.
8. Add instrumented tests for import and reader navigation.
9. Add offline thumbnail cache metadata and eviction policy.
10. Move renderer side effects behind a renderer state model.
