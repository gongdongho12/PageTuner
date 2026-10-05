# Readable EPUB document export v1

This contract creates a new, reflowable EPUB 3 publication from the selected
book title, chapter title, language, and ordered paragraph text. It does not
reconstruct the original EPUB, its layout, images, styles, metadata, or complete
chapter collection. No original provider, book, chapter, paragraph, or revision
identity enters this publication. Credentials, notes, bookmarks, translation
caches, and other documents are not accepted by this API. The separate portable
ZIP contract is the identity-preserving exchange format.

The pure Kotlin API is
`EpubDocumentExports.create(bookTitle, chapterTitle, language, paragraphs)` in
`com.dongholab.pagetuner.core.content`. The nullable `language` is a string;
`EpubDocumentFile` contains `filename`, `mimeType`, and ordered `entries` of
`EpubDocumentEntry(path, text)`. The MIME type is `application/epub+zip`, and
`DocumentFileExports.safeFilename(bookTitle, chapterTitle, "epub")` supplies
the safe suggested filename. Its normalization and Windows device-name handling
are identical to `document-file-export-v1`.

## Text, language, and XML

Normalize the titles exactly as in `document-file-export-v1`: trim only ASCII
SP/TAB/CR/LF at the edges, use `Untitled` for an empty book title, and omit an
empty or duplicate chapter title. The display title is the normalized book plus
` - ` and the chapter when present. Emit the book as `h1`, the chapter as `h2`
when present, and every paragraph as one `p` in original order. Keep all body
whitespace, empty paragraphs, Unicode, and line endings. CSS uses `pre-wrap`
and gives empty paragraphs a minimum height. Rendering and pagination remain
the EPUB reader's responsibility.

Escape every ampersand, less-than sign, greater-than sign, double quote,
apostrophe, and CR as `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, and `&#xD;`,
respectively. Keep TAB and LF unchanged. CR must use a character reference so
XML line-ending normalization does not alter the parsed text. Source HTML,
Markdown, links, entity syntax, and scripts remain inert text. There are no
external resources or user-controlled resource paths. All generated separators
are LF, and every resource is UTF-8 without a BOM.

For language, first validate the raw string as text, then trim ASCII SP/TAB/CR/LF.
Accept at most 128 UTF-16 code units matching this whole-string, conservative
BCP 47 subset, and convert ASCII uppercase to lowercase:

```regex
[A-Za-z]{2,8}(?:-[A-Za-z]{4})?(?:-(?:[A-Za-z]{2}|[0-9]{3}))?(?:-(?:[A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*
```

This subset supports ordinary language, script, region, and variant forms;
it does not attempt registry validation or support extension/private-use tags.
Missing, empty, too-long, unsupported language values, and the application's
`auto` / `auto-detect` sentinels (case-insensitive) become `und`. Invalid
controls, unpaired surrogates, and XML-invalid characters are rejected even in
a language value that would otherwise fall back. Use the normalized value in
`dc:language`, `xml:lang`, and `lang`.

## Bounds and errors

Reuse the shared readable-text validator: at most 100,000 paragraphs and
5,000,000 raw input UTF-16 code units across the two titles, language (null
counts as empty), and all paragraphs. Bounds are inclusive. Reject unpaired
surrogates, U+0000–U+001F except TAB/LF/CR, and U+007F–U+009F. EPUB additionally
rejects U+FFFE and U+FFFF because XML 1.0 cannot represent them. Other Unicode
remains unchanged.

Limit the aggregate `text.length` of all six resources to 32,000,000 UTF-16
code units. In addition, each individual resource is limited to 8 MiB
(8,388,608 bytes) after UTF-8 encoding, and the aggregate encoded resource text
is limited to 32 MiB (33,554,432 bytes). These bounds keep output within the
existing EPUB importer's expanded-resource limits. Count all XML escaping,
repeated titles, metadata, CSS, and generated markup, and check the remaining
unit and byte budgets before appending each part. Reject with
`IllegalArgumentException` instead of truncating or replacing content. Platform
adapters may impose a separate encoded-file size bound and must surface failure
without reporting a successful save.

## Package and reproducibility

The ordered resources are exactly:

1. `mimetype`, containing exactly `application/epub+zip`, with no line ending.
2. `META-INF/container.xml`, pointing to `EPUB/package.opf`.
3. `EPUB/package.opf`, declaring EPUB 3.0, title, language, publication identifier,
   modified metadata, three manifest items, and the content spine item.
4. `EPUB/nav.xhtml`, containing a single table-of-contents link to
   `content.xhtml#document`, labeled with the chapter or book title.
5. `EPUB/content.xhtml`, containing the selected text.
6. `EPUB/styles.css`, containing only fixed readability rules.

The unique publication identifier is `urn:sha256:` followed by lowercase SHA-256
hex of the exact UTF-8 bytes of `EPUB/content.xhtml`, with no BOM. This is a new
content-derived publication identifier. It is not a source identity, source
revision, portable exchange identity, or proof that the original EPUB was
preserved. Equal rendered content yields an equal identifier, including when
raw titles normalize to the same title or unsupported languages fall back.

The `dcterms:modified` value is fixed to `2000-01-01T00:00:00Z` as a deterministic
export-format sentinel, not an original publication date or the wall-clock export
time. The exact resource strings, including indentation and final LFs, are
specified by the shared vectors. ZIP compressors may produce different archive
bytes; deterministic resource contents are required, identical archive bytes
are not.

The platform ZIP writer must put `mimetype` first, uncompressed (STORED),
unencrypted, without a BOM, without extra fields, and with the CRC and size
matching its exact bytes. It then writes the five remaining resources in the
given order. Do not add enclosing directories or additional files. The package
structure follows the [W3C EPUB 3.3 container and publication requirements](https://www.w3.org/TR/epub-33/).

## Shared vectors

`fixtures/document-ebook-export-v1/vectors.json` contains exact-output vectors:
`name`, `bookTitle`, `chapterTitle`, nullable `language`, `paragraphs`, and
`expected: { filename, mimeType, entries: [{ path, text }] }`.
`rejected-vectors.json` uses the same inputs without `expected`. Kotlin and web
consume the same UTF-8 JSON fixtures. Runtime tests cover large input/output
bounds and paragraph limits without storing multi-megabyte fixture files.
