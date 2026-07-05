# PageTurner Remote Sources TODO

This document keeps remote-library work separate from the reader core. Hoshi or
other GPL readers may be used as product references only; source code, schemas,
assets, and implementation structure should stay independent.

## Product Goal

PageTurner should import books from personal storage without making the reader
care where the file came from. Every remote source should eventually resolve to
the same local model:

```kotlin
RemoteBookSource -> RemoteBookItem -> local ReaderDocument -> translation cache
```

The first supported formats remain EPUB, PDF, Markdown, and plain text. Imported
files should be stored locally before reading so translation, bookmarks, and
offline access work the same way for every source.

## TODO

- Core source framework
  - Done: define `RemoteBookSource` with `connect`, `list`, `search`, `download`, and
    `refresh` operations.
  - Done: normalize each result into `RemoteBookItem`.
  - Store a stable remote identity: `sourceType + accountId + remotePath/id`.
  - Save downloaded files into app-private storage.
  - Track sync state: remote modified time, local file hash, local read progress.

- Google Drive
  - Use Google Drive API v3.
  - OAuth sign-in belongs outside the reader surface.
  - List candidate files with `files.list`.
  - Filter by extension and MIME type for EPUB, PDF, Markdown, and text.
  - Store Drive file id, name, mimeType, modifiedTime, size, and md5Checksum
    when available.
  - Download via Drive media endpoint, then import locally.

- FTP / FTPS
  - Done: use a Java/Kotlin FTP client behind the same source interface.
  - Done: support FTP, explicit FTPS, and implicit FTPS.
  - Done: use passive mode by default for home networks and mobile tethering.
  - Done: support username/password and optional anonymous mode.
  - Done: normalize remote paths and modification timestamps from MLSD.
  - Done: parse MLSD and common Unix `LIST` directory output.
  - Pending: wire account storage/UI into the reader surface.
  - Pending: stream large downloads to a temp file before local import.
  - Pending: verify size/hash when known, then import locally.

- PageTurner Web Catalog
  - Done: support a tiny JSON catalog first for easy self-hosting.
  - Done: parse v0.1 JSON into `RemoteBookItem`.
  - Done: resolve catalog-relative book and cover URLs.
  - Add OPDS 2.0 ingestion as the public interoperability target.
  - Support basic auth or bearer token later, but keep v0.1 public/static.
  - Cache catalog responses for offline browsing.
  - Allow prefetching selected books and their translations for offline reading.

## PageTurner Web Catalog v0.1

This is intentionally smaller than OPDS. It is for quick personal servers,
GitHub Pages, local NAS folders, and simple static hosting. The app should later
map OPDS 2.0 feeds into the same internal model.

Discovery:

```text
GET /.well-known/pagetuner-catalog.json
GET /catalog.json
```

Recommended media type:

```text
application/vnd.pagetuner.catalog+json
```

Minimal response:

```json
{
  "version": "pagetuner.catalog.v0",
  "id": "personal-library",
  "title": "Personal Library",
  "updatedAt": "2026-06-28T00:00:00Z",
  "links": [
    {
      "rel": "self",
      "href": "https://example.com/catalog.json",
      "type": "application/vnd.pagetuner.catalog+json"
    }
  ],
  "items": [
    {
      "id": "sample-text",
      "title": "Sample Text",
      "authors": ["PageTurner"],
      "format": "txt",
      "language": "en",
      "href": "https://example.com/books/sample.txt",
      "type": "text/plain",
      "updatedAt": "2026-06-28T00:00:00Z",
      "translationHints": {
        "sourceLanguage": "en",
        "targetLanguages": ["ko"]
      }
    }
  ]
}
```

Item requirements:

- `id`: stable inside this catalog.
- `title`: display title.
- `format`: one of `epub`, `pdf`, `md`, `txt`.
- `href`: absolute or catalog-relative URL to the downloadable file.
- `type`: HTTP content type.
- `language`: BCP 47 language tag when known.

Optional item fields:

- `authors`: list of display names.
- `size`: byte size.
- `checksum`: for example `sha256:<hex>`.
- `updatedAt`: ISO-8601 timestamp.
- `cover`: URL to a cover image.
- `translationHints.sourceLanguage`: `auto`, `en`, `ko`, etc.
- `translationHints.targetLanguages`: languages worth prefetching.

## OPDS Compatibility Target

OPDS 2.0 is the interoperability target for public ebook catalogs. PageTurner
should eventually accept `application/opds+json`, read `metadata`, `links`,
`navigation`, and `publications`, then convert acquisition/download links into
`RemoteBookItem`.

## Quick Local Trial

The repository includes a static example:

```bash
cd examples/pagetuner-catalog
python3 -m http.server 8088
```

Then open:

```text
http://localhost:8088/catalog.json
```

In an Android emulator, use this app URL instead:

```text
http://10.0.2.2:8088/catalog.json
```

On a physical e-ink device, use the host computer's LAN IP address. Adding that
URL in the app lists the sample book, saves catalog metadata for later recall,
and imports downloaded items into the local reader.

## References

- OPDS 2.0: https://specs.opds.io/opds-2.0
- Google Drive `files.list`: https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list
- Apache Commons Net `FTPClient`: https://commons.apache.org/proper/commons-net/apidocs/org/apache/commons/net/ftp/FTPClient.html
