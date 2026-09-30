# Reading notes v1

This API synchronizes bookmarks, notes and exact text highlights for an existing immutable server `ORIGINAL` or `TRANSLATION` record. Its identity and authentication follow [reading-progress-v1](reading-progress-v1.md): account + server origin + kind + record UUID. Original and translated text have independent character offsets. Local-only documents and PDF page-only annotations are outside this version.

## Read committed changes

`GET /api/v1/reading-notes/{kind}/{recordId}?afterRevision=0&limit=50`

`afterRevision` defaults to zero; `limit` defaults to 50 and accepts 1–100. The first response captures a committed document `watermark`. Continue the same read with `afterRevision=nextAfterRevision&untilRevision=watermark` while `hasMore` is true. `untilRevision` is optional on the first request, must not precede `afterRevision`, and must not exceed the current document revision.

```json
{
  "kind":"ORIGINAL",
  "recordId":"30ea7fb9-fc58-49de-adab-d2acfe91320e",
  "items":[{
    "noteId":"ed4a4b22-ab8b-478d-9e52-31385f15b7bb",
    "version":1,
    "changeRevision":1,
    "deleted":false,
    "note":{"kind":"NOTE","title":"A clue","text":"Remember this name.","anchor":{"paragraphId":"p-1","characterOffset":0},"range":null,"createdAt":"2026-09-16T00:00:00Z","excerpt":"The reader opened the book."},
    "updatedAt":"2026-09-16T00:01:00Z"
  }],
  "nextAfterRevision":1,
  "watermark":1,
  "hasMore":false
}
```

Items are immutable changes ordered by strictly increasing document-wide `changeRevision`; a note can appear more than once. Apply them in order. Every successful mutation increments that note's `version` and the document's `changeRevision` independently. PostgreSQL serializes each document's mutation commits so a lower revision cannot appear after a client has advanced past it. Tombstones remain in this feed. Writes made after the captured watermark belong to a subsequent read.

On a nonfinal page, `nextAfterRevision` is the last item's revision. On the final page, it equals `watermark`, including empty responses. Persist cursor advancement atomically with applying the page; never advance first and risk losing the page on a device crash. A PUT acknowledgment must **not** advance the feed cursor: other notes may have intervening changes that the client has not read yet. A client with local pending changes must retain them and reconcile by each note's version, rather than allowing an incoming change to silently replace them.

This version retains the change history until its owning server document is deleted, at which point notes and history cascade away. There is no time-based cursor expiry or pruning in v1. A future retention policy requires an explicit full-resynchronization contract.

## Create, update, delete and restore

`PUT /api/v1/reading-notes/{kind}/{recordId}/{noteId}` accepts exactly:

```json
{
  "expectedVersion":0,
  "mutationId":"4c187bd5-ccdf-4ce9-bff3-5c94bdb56177",
  "deleted":false,
  "note":{"kind":"HIGHLIGHT","title":"Opening","text":"","anchor":{"paragraphId":"p-1","characterOffset":0},"range":{"start":{"paragraphId":"p-1","characterOffset":0},"end":{"paragraphId":"p-1","characterOffset":10}},"createdAt":"2026-09-16T00:00:00Z"}
}
```

The response is one item with the same shape as a feed item. The body is limited to 32 KiB. JSON is strict: unknown/duplicate fields, missing required fields, trailing JSON, fractional/string-coerced integers and invalid Unicode are rejected. UUIDs use full hyphenated form. Versions/revisions are JSON-safe integers; request expected versions stop at `9007199254740990` so the increment is also safe.

For deletion, set `deleted:true` and `note:null`. A first deletion with `expectedVersion:0` creates a version-one tombstone, preventing a delayed offline create with version zero from resurrecting the note. Restoring a deleted note requires an explicit update against the tombstone's version, with `deleted:false` and a complete note. Never use wall-clock time to resolve conflicts.

On a response-loss retry, resend exactly the same mutation ID and payload. If it is still the latest mutation of that note, the server acknowledges the same item without incrementing either version or timestamp. Reusing that latest ID for different content returns `400 READING_NOTE_MUTATION_REUSED`. An older mutation retried after another update conflicts; unlimited mutation-ID history is not an idempotency promise.

A stale expected version returns `409 READING_NOTE_CONFLICT` with `current` containing the authoritative item. For an ID that has never existed, `current` has `version:0`, `changeRevision:0`, `deleted:true`, `note:null`, and `updatedAt:null`. This empty conflict view is not a persisted tombstone. Keep the local pending note/deletion until the user selects a version. Choosing to save the local version uses a new mutation ID and the selected current version; another concurrent update may conflict again.

## Note validation

All note fields shown above are required, including `range:null` when no range applies. Requests do not accept `excerpt`; the server derives it from the owned immutable document.

| Field | Rule |
| --- | --- |
| `kind` | `BOOKMARK`, `NOTE` or `HIGHLIGHT`. |
| `title` | Nonblank; at most 200 UTF-16 code units. |
| `text` | At most 4,000 UTF-16 code units; nonblank for `NOTE`, optional content represented by an empty string otherwise. |
| `anchor` | Existing paragraph ID, 1–200 UTF-16 code units with no ISO control characters; integer offset from zero **before** the end of its text, never inside a surrogate pair. |
| `range` | Required for `HIGHLIGHT`, null otherwise. `start` equals `anchor`; `end` is exclusive and may be at paragraph end. Paragraph order must be forward. Selected text, joined with two newlines between paragraphs, must be nonblank and at most 4,000 code units. |
| `createdAt` | Valid UTC ISO 8601 timestamp ending in `Z`, years 0001–9999, with optional 1–9 fractional digits. Metadata only; clients may preserve or explicitly edit it. It is not a concurrency token. |
| Response `excerpt` | Exact selected text for highlights (at most 4,000 code units); up to 1,000 code units after the anchor in the same paragraph for other kinds. Never ends in the middle of a surrogate pair. |

Kotlin and JavaScript both count UTF-16 code units. Translation ranges refer to translated text; source text ranges cannot be reused by assuming equal character positions.

Legacy Android/ZIP note IDs are not necessarily UUIDs, and native page highlights may have no exact text range. Clients must retain a durable ID mapping and preserve unsupported original metadata. Do not guess an exact highlight range from a shortened or whitespace-normalized preview. Establish a source-mapped range or preserve it explicitly as a note.

## Authentication, errors and retry

GET requires Basic authentication. PUT additionally requires `X-CSRF-TOKEN` from `/api/v1/csrf` with its matching session cookie. Responses use `Cache-Control: no-store`. Missing and foreign documents both return the same 404. The body limit also applies without Content-Length. Each server process permits 120 PUT attempts per account per minute with at most 10,000 active rate windows. Honor `Retry-After` on 429; do not repeatedly retry permanent errors without user action or reconnection.

| Status | Code / meaning |
| --- | --- |
| 400 | `READING_NOTE_INVALID`: malformed request, invalid cursor or invalid note/range. |
| 400 | `READING_NOTE_MUTATION_REUSED`: latest mutation ID reused for different content. |
| 401 / 403 | Framework authentication / CSRF response. |
| 404 | `READING_NOTE_NOT_FOUND`: server document absent or owned by another account. |
| 409 | `READING_NOTE_CONFLICT`: `current` is authoritative. |
| 413 | Body limit Problem Detail. |
| 429 | `READING_NOTE_LIMIT`: wait for `Retry-After` seconds. |
| 409 | `READING_NOTE_REVISION_EXHAUSTED`: document or item reached the safe integer revision limit. |
