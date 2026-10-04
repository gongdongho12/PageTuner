# Web Novel Offline Translation

This document describes how PageTurner turns a rendered web novel into an
airplane-mode package. For the screen and component rules used by this flow,
also read [EINK_UI_GUIDE.md](EINK_UI_GUIDE.md).

Shared translation and site extension contracts are documented in
[TRANSLATION_SYSTEM.md](TRANSLATION_SYSTEM.md) and
[WEB_NOVEL_ADAPTERS.md](WEB_NOVEL_ADAPTERS.md).

## User flow

1. Open a web catalog and select a novel.
2. Choose **Original + translation** for all chapters, or **Save offline** for
   one chapter.
3. PageTurner downloads each rendered chapter sequentially, saves the original
   immediately, translates it to the target language configured in Settings,
   then adds that translation to the same chapter package.
4. A saved chapter displays its available languages, such as `EN+KO`.
5. Choose **Read original** or **Read KO/JA/...** above the chapter pager. An
   unavailable saved translation falls back to the original without starting a
   network request.

Run the download before enabling airplane mode. The current queue is scoped to
the running app, but it is resumable: starting the same download again skips
already saved originals and already completed target-language translations.

## Data flow

```text
Rendered source page
  -> WebNovelRemoteBookSource.download
  -> save original atomically
  -> PlainTextDocumentParser
  -> TranslationRepository / configured TranslationProvider
  -> save target-language translation atomically
  -> import preferred offline text into the local library
```

The source web page is not treated as an API. `WebNovelRemoteBookSource` uses
the rendered-page loader for sites such as WTR-LAB, extracts paragraphs, and
only then creates the text data consumed by the reader and translator.

## Storage contract

`OfflineNovelStorageStore` writes one JSON package per remote chapter under an
explicit provider -> book -> chapter hierarchy in the app-private
`files/offline_novels` directory:

```text
offline_novels/
  providers/
    {provider-account}-{source-hash}/
      books/
        {stable-book-id}/
          chapters/
            {zero-padded-chapter-number}-{remote-chapter-id-hash}.json
```

For example, WTR-LAB chapter 7 starts with
`providers/default_wtr_lab-…/books/…/chapters/00000007-….json`. Provider,
account, stable series identity, chapter number, and remote chapter identity
all participate in the path. Equal titles or chapter numbers therefore cannot
overwrite chapters from another provider or book.

Version-2 flat SHA-256 files remain readable. A later translation/save writes
the package to the version-3 structured location, so existing offline downloads
continue to work without an eager destructive migration.

Each version-2 package contains:

- source type, provider account, stable series/book ID;
- novel ID, stable remote chapter ID, display chapter number, and title;
- source language and original text;
- zero or more translations keyed by normalized target language;
- translation provider ID and save timestamps.

Files are replaced through a temporary file after the file descriptor is
synced. The original is committed before translation begins. A provider error
therefore leaves a readable source chapter instead of a broken package.

Downloading another target language preserves all existing languages. For
example, downloading with target `ko` and later with target `ja` produces one
package containing the original, Korean, and Japanese text.

## Translation behavior

The web-novel pipeline uses the same `TranslationSettings`, provider factory,
segmentation, batching, pacing, and JSON translation cache as the local reader.
The active source/target languages and provider are taken from Settings at the
time the queue starts.

Catalog translation is explicit. **Translate list** translates the currently
loaded/filtered catalog using the active target language, keeps the original
title as secondary text, and reuses the global translation cache on subsequent
runs. It does not silently translate while a user pages through the catalog.

## Queue and failure rules

- Chapters are fetched sequentially to avoid competing rendered WebView loads
  and source throttling.
- Progress distinguishes original download, bounded translation parts, saved chapters,
  and completion.
- A failed chapter increments the failure count and the queue continues.
- A translation failure never removes the saved original.
- Cancelling stops at the current coroutine boundary; committed chapter files
  remain available and allow a later resume.
- A completed target language is skipped when the same queue is restarted.

## E-Ink UI contract

- Use `EinkOperationIndicator`; do not depend on an animated spinner.
- Show numeric chapter/page progress and failure count as text.
- Keep catalog and chapter results inside `EinkAutoFitPagingContainer` so
  controls and rows cannot be clipped below the viewport.
- Rows have a fixed measured height. Long translated titles use bounded lines
  and ellipsis, while the original title remains visible as secondary text.
- Disable source, language, and action controls while a queue is active so the
  label always matches the settings captured by the running job.

## Verification checklist

- Download one chapter and confirm both source and target language badges.
- Enable airplane mode and open the saved translated chapter.
- Change the target language, download again, and confirm earlier languages
  remain available.
- Interrupt a multi-chapter download and restart it; completed content should
  be skipped.
- Force a translation provider failure and confirm the original still opens.
- Test narrow phone and larger E-Ink viewports for clipped rows or controls.
