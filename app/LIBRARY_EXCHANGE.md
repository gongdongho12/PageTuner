# App and PC library ZIP exchange

Open **Local → ZIP** to import a `.ptlibrary.zip`, open an imported book, or save a ZIP using the Android document picker. The **Device books** section exports any locally imported or downloaded book. For the current original book, **With translation** adds a separate translated document only when every paragraph is available in the active provider/language/glossary cache. Partial caches fail with an explicit message.

The format is [PageTurner library exchange v1](../contracts/library-exchange-v1.md). Packages contain content and explicitly selected book metadata. Account credentials, saved connections, API keys and global app settings are excluded.

## Included data

- Native TXT/Markdown and downloaded novel text retain their current reader segment identities; EPUB text, outline and available raster images are normalized into the same document format.
- Original PDF bytes are included. On Android versions that cannot extract PDF text, the PDF remains an asset-only document and opens through the PDF renderer. Missing PDF or EPUB image bytes fail export before the picker opens.
- Folder, tags, book glossary entries (including type, display alias, case sensitivity and enabled state), bookmarks, notes, reading position and source-page highlight text are exported. Native notes are anchored to the start of their page because the native reader does not store a selected character range.
- New server downloads also retain the original structured server paragraph IDs in the imported ZIP library before the local text reader reflows them. Older downloaded local files retain their existing native reader identities; original server IDs cannot be reconstructed from flattened text.
- Imported ZIP documents retain their full original metadata, including fields that the native UI does not edit. The native text reader can add/remove bookmarks and notes and continue reading. UTF-16 offsets and highlight ranges survive unchanged reader projections and re-export.
- Imported original PDFs have a **PDF** action when extracted text is also available. The text reading action exposes portable paragraph notes. Physical PDF page progress and page-only notes use passive `android` extension metadata, while paragraph notes remain preserved for text reading and PC re-export.

## Persistence and limits

Validation finishes before any library mutation. Each imported package is stored as one atomically replaced file under `files/portable_library`; native local books are never overwritten. The same canonical package content skips a duplicate import and preserves saved reading changes. An incoming metadata or content revision creates an independent copy. Exporting a selected imported document includes only the assets it references.

All shared limits apply, including 32 MiB compressed and 64 MiB expanded ZIP size. Archives are never extracted using user-supplied filesystem paths. Native reader pages split long paragraphs at safe UTF-16 boundaries without changing archive paragraph IDs.

`PortableLibraryTest` consumes the same ZIP fixture as the browser and core codec tests, checks import → reader → update → export preservation, duplicate/conflict behavior, malformed import atomicity, supplementary Unicode ranges, embedded image bytes, PDF assets and missing-asset errors. The ZIP collection uses the standard 124 dp `AdaptiveCollection` contract and has an instrumentation benchmark fixture; its device rendering timings have not been measured yet.
