# PDF content storage v1

This first bounded asset-storage profile stores one immutable account-owned PDF snapshot. It preserves exact supplied language, ordered paragraphs, ordered asset references, and actual payload bytes, then computes the existing `PortableContentProof` from those stored bytes. It is separate from document provenance, translation artifacts, synchronization authority, and the ZIP format. The normative wire schema is [pdf-content-v1.openapi.json](pdf-content-v1.openapi.json); the validation implementation is `core-backup`'s `PdfContentValidation`.

This API does not decode the PDF, attest page count, create a library document or binding, or mutate reading positions, notes, classifications, or glossaries. A leading ASCII `%PDF-` signature is required as a minimal framing check; successful storage is not a statement that any PDF parser can open the file. EPUB and larger payloads are outside this profile. Stored IDs are independent snapshot UUIDs and must not be passed to existing document synchronization APIs as document IDs.

## Requests and responses

All endpoints require the current account. POST requests additionally require its matching CSRF token. Foreign-account and missing snapshot UUIDs have the same `404 PDF_CONTENT_NOT_FOUND` response. JSON success and original-file responses use `Cache-Control: no-store`.

| Endpoint | Input | Success |
| --- | --- | --- |
| `POST /api/v1/pdf-content` | `{uploadId, content}` | `200 {recordId, createdAt, proof}` |
| `GET /api/v1/pdf-content/{recordId}` | Owned canonical lowercase UUID | `200 {recordId, createdAt, content, proof}` |
| `POST /api/v1/pdf-content/{recordId}/verify` | `{proof}` | `200 {recordId, verified: true, proof}` |
| `GET /api/v1/pdf-content/{recordId}/original` | Owned canonical lowercase UUID | Exact original bytes, `application/pdf`, `attachment; filename="<recordId>.pdf"`, `nosniff` |

Every JSON object has exact required keys. Unknown keys, omitted nullable keys, duplicate keys, trailing JSON, invalid UTF-8/Unicode, noninteger numeric fields, and unsupported versions fail. HTTP `Content-Encoding` must be absent or `identity`; compressed or stacked encodings fail with `415 PDF_CONTENT_ENCODING`. Size limits apply during reading, including chunked transfer without `Content-Length`.

`content` has exactly `{version: 1, language, paragraphs, assets, payloads}`. A paragraph is `{paragraphId, text}`. A reference is `{path, role, paragraphId, alt}` with the last two keys always present and individually string-or-null. A payload is `{path, mimeType, base64}`. There is no asserted proof, title, source provider/book/chapter identity, page count, credential, or synchronization journal in the upload.

Exactly one `pdf` reference points to the original `application/pdf` payload and has `paragraphId: null`. Other references may have role `image` with PNG, JPEG, WebP, or GIF MIME. Each non-null paragraph binding refers to an actual paragraph. Repeated image references are permitted and their order is significant. Duplicate payload paths, missing/orphan payloads, unsupported MIME, or paths different from `assets/<SHA-256 of actual bytes>` fail. The original PDF payload is transmitted once and reused internally to compute the proof's original-file field and PDF reference. Null and empty alt text remain distinct.

The upload UUID is account-scoped idempotency, not a portable source identifier. The same account/upload UUID and exact ordered content return the original immutable receipt. Different content with that UUID fails with `409 PDF_CONTENT_UPLOAD_REUSED`, including changed payload order when its proof would otherwise be equal. JSON object key order and escape spelling do not distinguish content after strict parsing. Another upload UUID creates another independent snapshot; matching hashes never merge records.

GET, verification, and binary download reconstruct and validate all stored metadata and actual payload bytes before returning success. Stored corruption fails with `409 PDF_CONTENT_UNAVAILABLE` and no successful partial content. Verification validates the complete PDF-profile proof DTO and compares it to the complete recomputed proof; inequality is `409 PDF_CONTENT_MISMATCH`. It neither writes a binding nor authorizes synchronization. Actual original-file bytes determine physical PDF identity; extracted paragraph text never asserts a physical page anchor or decoder page count.

## Bounded profile

| Limit | Value |
| --- | --- |
| Upload JSON | 8 MiB |
| Full JSON content response | 12 MiB; contains both content and proof |
| Verify request JSON / stored proof JSON | 2 MiB |
| Sum of unique decoded payloads | 4 MiB, original PDF counted once |
| Unique payloads | 64 |
| Ordered references | 128 |
| Ordered paragraphs | 4096; an empty list is legal for PDF |
| Aggregate metadata | 262144 UTF-16 code units |
| Paragraph ID | 1–500 UTF-16 code units, nonblank, unique, exact spelling |
| Each reference alt | At most 2000 UTF-16 code units |
| Language | Exact `[A-Za-z][A-Za-z0-9-]{0,34}`, case preserved |

The aggregate metadata count is precisely `language.length + sum(paragraphId.length + text.length) + sum(non-null reference.paragraphId.length + non-null reference.alt.length)` in UTF-16 code units. Hash paths and MIME values are separately fixed-shape/enum bounded and excluded from this aggregate. Valid surrogate pairs count as two; unpaired surrogates fail. Whitespace, control characters in paragraph/alt strings, empty paragraphs, repeated image bindings, and language case are preserved without normalization. The 2 MiB proof limit accommodates the largest allowed references even when control characters need six-byte JSON escapes; the former proposed 128 KiB limit could not round-trip them. GET repeats exact references in content and proof, so its independent response cap is 12 MiB; a legal upload can produce a GET response above the unchanged 8 MiB request limit.

Base64 uses the RFC 4648 standard alphabet with required padding, no whitespace, and zero unused padding bits. URL-safe, unpadded, noncanonical, and empty encodings fail before decoded allocation. Each encoded payload is bounded by 5592408 characters; total decoded payload bytes are checked before allocation. The pure Kotlin helper also works on Android API 23 without `java.util.Base64` or platform desugaring. Wire overflow returns `413 PDF_CONTENT_TOO_LARGE`; invalid decoded size or content profile returns `400 PDF_CONTENT_INVALID`. No data is truncated to meet a limit.

## Ordered-content request fingerprint

Idempotency compares a SHA-256 of the full exact content, independently of the content proof. Each string is framed as its decimal UTF-8 byte length, ASCII `:`, and its exact UTF-8 bytes. Hash the following frames in order and encode the digest as lowercase hex:

1. `pageturner.pdf-content-upload.v1`, decimal version, language.
2. Decimal paragraph count, then each paragraph's ID and text in order.
3. Decimal reference count, then each reference's path, role, nullable paragraph ID, and nullable alt in order.
4. Decimal payload count, then each payload's path, MIME, and canonical base64 in order.

A nullable string is frame `0` when null, or frame `1` followed by the string frame when present. Decimal integers have no padding/sign. The upload UUID is excluded because it is the account-scoped lookup key. Payload array order is included here even though the existing content-proof algorithm deliberately associates payloads by path and hashes ordered references.

[fixtures/pdf-content-v1.json](fixtures/pdf-content-v1.json) contains the upload and full expected proof/fingerprint. Its sibling Python generator computes both digests independently from the framing specification. It uses opaque PDF storage bytes and is not decoder evidence. Kotlin cross-checks the expected digests and web/server tests consume the exact wire fixture.

## Consumer obligations and remaining work

Clients should copy mutable input before asynchronous hashing/upload, independently recompute full proofs from returned content bytes, and discard responses after account/origin/client/generation changes. Abort is useful but does not replace generation checks or prove a server write never happened; retry uncertain uploads with the same UUID and exact content. Do not use receipts, cached proof assertions, or this read-only comparison as an account binding.

App/web upload UI, explicit durable asset binding, actual PDF decoder/page anchors, EPUB storage, and integration with latest S1/S2/classification ZIP data remain later units. This bounded profile is not a claim to support every maximum-sized ZIP or local library document.
