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
- Add basic search inside current book for text-based formats.
- Add bookmarks.
  - page bookmark
  - named bookmark
  - bookmark list per document
- Add reader preferences.
  - display mode
  - font size
  - line spacing
  - margin
  - e-ink contrast level

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
  - OPF spine parsing
  - chapter navigation
  - table of contents
  - basic inline formatting
  - image placeholder or image rendering
  - better HTML entity handling
- PDF viewer.
  - page image rendering
  - monochrome/e-ink rendering
  - page cache around current page
  - zoom presets
  - fit width / fit page
- PDF text layer.
  - extract text when available
  - map extracted text to page for translation
  - OCR TODO for scanned PDFs

## P0: Translation

- Keep all translation behind `TranslationProvider`.
- Done: add provider settings persistence.
- Add provider health check.
  - validate API key/endpoint/model
  - show readable error state
- Add translation cache management.
  - per-document cache status
  - clear translation cache
  - clear provider-specific cache
- Add offline prefetch queue.
  - selected pages
  - whole document
  - pause/resume/cancel
  - progress list
- Add translation display modes.
  - original only
  - translation only
  - original + translation
- Add fallback behavior.
  - show cached translation when offline
  - skip already cached pages
  - retry failed batches

## P1: E-Ink Performance

- Avoid continuous scrolling in reader surfaces.
- Render only the current page by default.
- Add small page cache.
  - previous page
  - current page
  - next page
- Add monochrome bitmap pipeline.
  - color passthrough mode
  - threshold mode
  - grayscale mode
  - high-contrast mode
- Reduce recomposition churn.
  - move UI state into view models
  - keep renderer state separate from settings state
- Add no-animation mode as default.
- Add manual refresh hooks for devices that support explicit e-ink refresh.
- Add memory guard for large PDFs.
  - capped bitmap size
  - bitmap recycle strategy
  - avoid holding full document images

## P1: Remote Sources

- Define `RemoteBookSource` interface.
  - connect
  - list
  - search
  - download
  - refresh
- Implement PageTurner Web Catalog first.
  - static `catalog.json`
  - local HTTP test
  - import selected item
- Implement FTP / FTPS source.
  - passive mode
  - username/password
  - folder browsing
  - file download
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
  - `ReaderViewModel`
  - `LibraryViewModel`
  - `TranslationViewModel`
  - `SettingsViewModel`
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
  - failed import
  - unsupported format
  - missing API key
  - network unavailable
- Add keyboard/D-pad page turning for e-ink devices with buttons.

## P2: Advanced Reader Features

- Table of contents for EPUB.
- PDF outline/bookmarks.
- Highlights.
- Notes.
- Dictionary lookup.
- Export notes/highlights.
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
2. Improve EPUB chapter/TOC support.
3. Add PDF text extraction path.
4. Add settings and reader ViewModel boundaries.
5. Add translation queue and cache management UI.
6. Implement PageTurner Web Catalog source.
7. Add FTP source.
8. Add Google Drive source.
9. Add secure credential storage.
10. Add release/license/privacy groundwork.
