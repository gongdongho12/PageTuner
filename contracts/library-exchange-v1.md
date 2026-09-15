# PageTurner library exchange v1

PageTurner Android and PC web exchange the same portable ZIP file (`.ptlibrary.zip`, MIME `application/zip`). A package contains normalized reading documents, the original PDF or embedded raster images when needed, and portable reading metadata. It contains no account login, password, API key, cookie, or executable code. Import validates the complete package before publishing any document to the destination library. This is an offline file format; importing does not call a translation provider or upload content to the server.

## Archive layout

```text
manifest.json
documents/<sha256-of-exact-UTF8-JSON-bytes>.json
assets/<sha256-of-exact-binary-bytes>
```

Hashes are lowercase hexadecimal SHA-256; `bytes` is the exact uncompressed byte count, not the JavaScript/Kotlin string length. JSON property order and whitespace are not canonicalized. Importers hash the bytes in the ZIP before parsing them. All paths use the exact ASCII spelling above, and every archive entry must appear in the manifest. Each document ID and each listed path is unique. Shared assets occur once in the manifest and can be referenced by several documents.

```json
{
  "format": "pageturner.library",
  "version": 1,
  "createdAt": "2026-09-15T00:00:00Z",
  "documents": [{ "path": "documents/<64 hex>.json", "sha256": "<64 hex>", "bytes": 1234 }],
  "assets": [{ "path": "assets/<64 hex>", "sha256": "<64 hex>", "bytes": 5678, "mimeType": "application/pdf" }]
}
```

`createdAt` and note timestamps use `YYYY-MM-DDTHH:MM:SS` with an optional 1–9 digit fraction, followed by `Z` or a `±HH:MM` UTC offset (at most 18:00). Years are 0001–9999 and dates/clock fields must be valid. Versions other than integer `1` are rejected. `documents` contains 1–100 entries. `assets` is required and can be empty.

## Document shape

```json
{
  "id": "book-1:chapter-1:original",
  "bookTitle": "Example book",
  "chapterTitle": "Chapter one",
  "language": "en",
  "kind": "original",
  "paragraphs": [{ "paragraphId": "p-1", "text": "Hello 🌏.\nSecond line." }],
  "outline": [{ "title": "Chapter one", "paragraphId": "p-1" }],
  "position": { "paragraphId": "p-1", "characterOffset": 6 },
  "notes": [{
    "id": "note-1",
    "kind": "highlight",
    "title": "A greeting",
    "text": "My annotation",
    "excerpt": "Hello",
    "anchor": { "paragraphId": "p-1", "characterOffset": 0 },
    "range": {
      "start": { "paragraphId": "p-1", "characterOffset": 0 },
      "end": { "paragraphId": "p-1", "characterOffset": 5 }
    },
    "createdAt": "2026-09-15T00:00:00Z"
  }],
  "organization": { "folder": "Reading", "tags": ["example"], "favorite": true },
  "glossary": [{ "source": "Hello", "target": "안녕", "kind": "TERM", "displayTerm": "안녕", "caseSensitive": true, "enabled": true }],
  "assets": [],
  "extensions": { "origin": { "providerId": "uploaded-document", "chapterId": "chapter-1" } }
}
```

- Required fields: `id`, `bookTitle`, `chapterTitle`, `language`, `kind`, `paragraphs`. `kind` is `original`, `translation`, or `local`; each original or translated chapter is an independent document with its own stable ID.
- Optional arrays `outline`, `notes`, `glossary`, and document `assets` default to `[]`. Optional `organization` defaults to `{ "folder": "", "tags": [], "favorite": false }`. If supplied, its three fields are required. `position` and `extensions` are optional and are omitted when absent; JSON `null` is not an alternate spelling.
- IDs and paragraph IDs contain 1–500 UTF-16 code units and are nonblank. Titles are nonblank and at most 2,000 code units. `language` is a tag matching `[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*`, at most 35 code units; `und` represents an unknown language. No text is invented for image-only pages.
- Paragraph order is reading order. Paragraph IDs are unique in a document. Paragraph text is preserved exactly (including whitespace and newlines). Text uses valid Unicode: unpaired UTF-16 surrogates are rejected. Empty paragraphs are allowed. A document with no paragraphs must have at least one PDF or image asset reference.
- Anchors use **UTF-16 character offsets**, shared by JavaScript and Kotlin. Offsets are integers from zero through the paragraph text length, and cannot split a surrogate pair. Outline entries and all note/range/position anchors must refer to an existing paragraph. This makes progress independent of viewport size, fonts, and device page breaks.
- Notes have required fields `id`, `kind`, `title`, `text`, `excerpt`, `anchor`, `createdAt`. `kind` is `bookmark`, `note`, or `highlight`; IDs are unique per document. Optional `range` uses start/end anchors and cannot run backwards. A note title can be empty and is at most 2,000 code units; text/excerpt can be empty and are at most 10,000 each. PDF page-only metadata without a text anchor belongs in a bounded extension.
- Folder is at most 500 code units, can be empty; tags are unique nonblank strings, at most 100 tags of at most 200 code units each. Favorite is a boolean.
- Glossary entries require nonblank `source` and `target` (at most 2,000 code units each). Optional `kind` is a nonblank string of at most 80 code units; optional `displayTerm` is at most 2,000. Optional booleans `caseSensitive` and `enabled` default to `true`. The portable fields preserve native glossary distinctions; a client with a simpler editor must retain unsupported distinctions on re-export.
- Unknown fields outside `extensions` are rejected, so data is not silently discarded. Clients preserve unsupported passive extension fields on re-export. Known credentials must never be copied into extensions by exporters.

## Assets and extensions

A document asset reference is `{ "path": "assets/<64 hex>", "role": "pdf" | "image", "paragraphId"?: "p-1", "alt"?: "description" }`. An optional `paragraphId` must exist; `alt` is at most 2,000 code units. PDF references require `application/pdf`; image references require `image/png`, `image/jpeg`, `image/webp`, or `image/gif`. SVG, HTML, scripts, external file paths, and remote asset URLs are not package assets. Every manifest asset must be referenced by at least one document. Asset contents are nonempty and remain byte-for-byte unchanged on import/re-export.

EPUB documents are normalized to paragraphs, outline, and embedded raster images. Original PDF bytes remain an asset for actual PDF rendering. PDF text extraction status and the original source/translation metadata can be preserved in `extensions`, an optional passive JSON object no larger than 256 KiB in UTF-8 and no deeper than 16 nested object/array levels (root is level zero). It is never executed or used as authorization. Normalizing extension key names to lowercase and removing non-alphanumeric characters, the names `authorization`, `password`, `passwd`, `token`, `apikey`, `accesstoken`, `refreshtoken`, `secret`, `clientsecret`, `credentials`, `cookie`, `setcookie`, and `basicauth` are forbidden at every nesting level. Clients must project known metadata instead of copying arbitrary app settings.

JSON numbers, including extension metadata, are interpreted as finite IEEE-754 binary64 values on both clients. Integral values must be in the safe range −9,007,199,254,740,991 through +9,007,199,254,740,991. A nonzero numeric literal that underflows to zero, or a literal that overflows to infinity, is rejected before platform JSON parsing. Native import/export normalizes decimal numbers to binary64 precision so re-export has the same numeric meaning as web. Exact decimal values and larger integers must be encoded as strings; numeric spelling, extra decimal digits beyond binary64 precision, and negative-zero spelling are not preserved.

## Limits and ZIP validation

| Limit | Maximum |
| --- | ---: |
| Compressed archive | 32 MiB |
| Actual combined expanded entries | 64 MiB |
| Manifest JSON | 1 MiB |
| One document JSON | 8 MiB |
| One binary asset | 32 MiB |
| Total archive entries including manifest | 512 |
| Documents | 100 |
| Paragraphs / outline entries per document | 50,000 |
| Paragraph text per document (UTF-16 code units) | 5,000,000 |
| Notes per document | 2,000 |
| Glossary entries per document | 2,000 |
| References per document | 512 |

Readers enforce declared sizes before inflation and actual byte limits during inflation. Only ZIP stored (method 0) and deflate (method 8) entries are accepted; stored entries must give their sizes in the local header, while deflate entries may use data descriptors. ZIP64, multi-volume, encrypted entries, symlinks, archive comments, directory entries, traversal, backslashes, duplicate paths, overlapping entries, prefixed/hidden data, undeclared entries and assets, or mismatched central/local headers are rejected. UTF-8 JSON is decoded strictly, with JSON nesting bounded to 32 levels before parsing. Only standard JSON grammar is accepted; duplicate decoded object keys, unquoted keys, single-quoted strings, trailing commas and trailing data are rejected. SHA-256 and manifest byte counts are checked before document validation. Readers do not extract untrusted ZIP paths onto the filesystem.

## Kotlin API and interoperability fixtures

The `core-backup` package `com.dongholab.pagetuner.core.backup.exchange` exposes `LibraryExchangeCodec.read(ByteArray): LibraryExchangePackage` and `write(LibraryExchangePackage): ByteArray`. Data classes are in `LibraryExchangeModels.kt`; `ExchangeAsset(bytes, mimeType)` derives `sha256` and `path`. `ExchangeDocument.extensionsJson` is encoded as the wire `extensions` JSON object.

`fixtures/library-exchange-v1/portable-v1.zip` is an actual interoperable package. Its JSON document, asset, manifest and expected summary are supplied next to it; Kotlin and web tests must both consume the same archive. Importing must finish validation before any library write, preserve an existing document on duplicate/conflicting IDs unless the destination explicitly creates an independent copy, and publish an archive import atomically to the extent supported by the destination storage transaction.
