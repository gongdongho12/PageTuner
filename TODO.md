# PageTurner TODO

This is the working checklist. `README.md` describes what exists; this file
tracks what still needs to be built.

## P0: Structure First

- [x] Split `MainActivity` into focused UI files.
- [x] Move reader UI to `ui/reader`.
- [x] Move translation controls to `ui/translation`.
- [x] Move display/page-turn settings to `ui/settings`.
- [x] Move remote source panel to `ui/source`.
- [x] Add `ReaderViewModel`.
- [x] Add `SettingsViewModel`.
- [x] Add `TranslationViewModel`.
- [x] Add `LibraryViewModel`.
- [x] Add app-level state models for library side effects.

## P0: Persistent Settings

- [x] Add DataStore.
- [x] Persist display mode.
- [x] Persist page-turn mode.
- [x] Persist source language.
- [x] Persist target language.
- [x] Persist translation provider kind.
- [x] Persist LLM endpoint/model.
- [x] Persist reading speed.
- [x] Restore settings on app start.

## P0: Local Library

- [x] Add local library screen.
- [x] Copy imported files into app-private storage.
- [x] Store local book metadata.
- [x] Track current page per book.
- [x] Track reading progress percentage.
- [x] Show recent books.
- [x] Reopen last book.
- [x] Detect duplicate imported files.
- [x] Delete local book safely.

## P0: Reader Basics

- [x] Keep page-based navigation as the default.
- [x] Add hardware key / D-pad page turning.
- [x] Add fit page / fit width controls for PDF.
- [x] Add PDF page cache for previous/current/next pages.
- [x] Add reader font size setting.
- [x] Add line spacing setting.
- [x] Add page margin setting.
- [x] Add quick hide/show reader controls.
- [x] Add document details screen.

## P0: Format Support

- [x] Text import and paging.
- [x] Markdown import and basic text paging.
- [x] PDF page-image viewing.
- [x] EPUB spine text extraction.
- [x] EPUB table of contents.
- [x] EPUB chapter navigation.
- [x] EPUB basic inline formatting.
- [x] EPUB image placeholders.
- [x] EPUB embedded image rendering.
- [x] PDF text extraction.
- [x] PDF text-to-page mapping for translation.
- [x] OCR plan for scanned PDFs.

## P0: Display Modes

- [x] Add display mode model.
- [x] Add color mode.
- [x] Add grayscale mode.
- [x] Add monochrome mode.
- [x] Add e-ink high contrast mode.
- [x] Apply display mode to PDF rendering.
- [x] Apply display mode to EPUB images.
- [x] Apply display mode to cover images.
- [x] Apply display mode to remote catalog thumbnails.
- [x] Add app-wide color-service palette.
- [x] Add app-wide monochrome-service palette.
- [x] Add manual refresh hooks for e-ink devices.

## P0: Translation

- [x] Add `TranslationProvider` interface.
- [x] Add Google Cloud provider.
- [x] Add Google Web Translate HTML provider.
- [x] Add OpenAI-compatible LLM provider.
- [x] Separate cache by provider id.
- [x] Add reading-speed pacing.
- [x] Add offline prefetch mode.
- [x] Persist provider settings.
- [x] Add provider health check.
- [x] Add translation queue.
- [x] Add pause/resume/cancel for prefetch.
- [x] Add whole-document translation progress screen.
- [x] Add translation cache status per book.
- [x] Add clear translation cache action.
- [x] Add original-only / translation-only / side-by-side modes.
- [x] Add retry handling for failed batches.

## P1: Remote Sources

- [x] Draft PageTurner Web Catalog spec.
- [x] Add static sample catalog.
- [x] Add `RemoteBookSource` interface.
- [x] Implement PageTurner Web Catalog connector.
- [x] Import book from web catalog.
- [x] Cache catalog metadata.
- [ ] Add FTP / FTPS connector.
- [ ] Add FTP folder browsing.
- [ ] Add Google Drive connector.
- [ ] Add Google Drive OAuth flow.
- [ ] Add source account management.

## P1: Search, Bookmarks, Notes

- [x] Search current text-based book.
- [x] Add page bookmarks.
- [x] Add named bookmarks.
- [x] Add bookmark list.
- [x] Persist bookmarks per local book.
- [x] Add highlights.
- [x] Add notes.
- [x] Export notes/highlights.

## P1: Error Handling

- [x] Add unsupported format screen.
- [x] Add import failure state.
- [x] Add network unavailable state.
- [x] Add missing API key state.
- [ ] Add provider error explanations.
- [ ] Add retry actions.
- [ ] Add crash-safe cache writes.

## P2: Translation Quality

- [ ] Add glossary support.
- [ ] Add per-book terminology rules.
- [ ] Add custom LLM prompt profile.
- [ ] Add translation memory export/import.
- [ ] Add batch size tuning per provider.
- [ ] Add cost estimate before large prefetch.

## P2: Release Readiness

- [ ] Choose project license.
- [ ] Add `LICENSE`.
- [ ] Add dependency license report.
- [ ] Add privacy notes for translation providers.
- [ ] Add secure credential storage.
- [ ] Add backup/restore policy.
- [ ] Add CI build.
- [ ] Add instrumented tests for import flow.
- [ ] Add instrumented tests for page navigation.

## Immediate Next Batch

- [x] Split `MainActivity` UI components.
- [x] Add DataStore settings.
- [x] Add local library metadata store.
- [x] Copy imported files into app-private storage.
- [x] Add PDF text extraction path.
- [x] Add EPUB table of contents and chapter navigation.
