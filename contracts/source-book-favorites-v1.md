# Source book favorites v1

An account's source-site book favorites use **exact original `providerId` + `bookId`** as their identity. They are independent of server original/translation record UUIDs, document classification, screen page numbers, local Android `remoteId`, titles, or display URLs. Existing catalog IDs are authoritative; never infer an ID from a title, URL, or another provider. The existing WTR-Lab catalog uses `wtr-lab` plus its canonical series URI (see the shared fixture); Android's `novel_42` alone is not that identity.

## Read committed changes

`GET /api/v1/source-book-favorites?afterRevision=0&limit=50`

The authenticated account is implicit. `afterRevision` defaults to 0, `limit` to 50 (1–100). Response: `{items,nextAfterRevision,watermark,hasMore}`. Every item is:

```json
{"providerId":"wtr-lab","bookId":"https://wtr-lab.com/en/novel/42/river","version":1,"changeRevision":1,"deleted":false,"book":{"title":"River","authors":["Author"],"language":"en","url":"https://wtr-lab.com/en/novel/42/river"},"updatedAt":"2026-10-02T00:00:00Z"}
```

History is immutable and ordered by increasing account-wide `changeRevision`. The same identity may appear multiple times, including tombstones. The first page captures a committed watermark. While `hasMore`, continue with `afterRevision=nextAfterRevision&untilRevision=watermark`; writes after that watermark belong to the next read. `untilRevision` may not precede `afterRevision` or exceed the current account revision. All versions/revisions are integers from 0 to 9007199254740991; persisted items start at 1.

Nonfinal `nextAfterRevision` equals the last item revision; final `nextAfterRevision` equals `watermark`, including empty responses. Persist applying a page and its cursor atomically. Never advance the cursor from a PUT acknowledgment; another book may have an intervening change. Ignore stale versions without discarding newer local pending edits. The account-wide transaction lock serializes commit order with revision order; a late committing lower revision cannot be skipped. Reading an empty account creates no defaults or favorites. History is retained without expiration in v1 and cascades when the owning account is deleted.

## Explicit create, adoption, update, delete, restore

`PUT /api/v1/source-book-favorites` accepts exactly:

```json
{"providerId":"wtr-lab","bookId":"https://wtr-lab.com/en/novel/42/river","expectedVersion":0,"mutationId":"9863c674-6f07-469f-b548-cfda7c01fc3d","deleted":false,"book":{"title":"River","authors":["Author"],"language":"en","url":"https://wtr-lab.com/en/novel/42/river"}}
```

The response is one feed item. The identity is in the JSON body so slashes, percent signs and Unicode IDs survive transport without path decoding. Only an explicit user adoption may upload existing device-local favorites. Local files remain available; login does not silently adopt, delete, or merge them. Legacy favorites lacking the original provider/book identity remain local until their original source supplies it. This v1 caps `bookId` at 500 UTF-16 units (some other source APIs accept 2000); unsupported longer identities remain local and must never be truncated or hashed into a new identity.

`deleted:true` requires `book:null`; otherwise all book fields are required. Even deleting a never-created identity with expected version 0 persists version-one tombstone. A delayed expected-version-zero create then conflicts. Restore explicitly against the tombstone version. Do not resolve conflicts by wall-clock timestamps.

Latest exact mutation replay returns the original item, including timestamp, without increasing revisions. Reusing the latest mutation ID for different content/expectedVersion returns `400 SOURCE_BOOK_FAVORITE_MUTATION_REUSED`. An old mutation retried after another update conflicts; unlimited mutation-ID history is not promised. Preserve the exact request in a durable account/server scoped outbox, separately from later local edits.

Stale expectedVersion returns `409 SOURCE_BOOK_FAVORITE_CONFLICT` with `current`. An absent identity's current view is `{providerId,bookId,version:0,changeRevision:0,deleted:true,book:null,updatedAt:null}` and is not persisted. Keep both pending and remote versions until explicit conflict selection. Saving local data uses the selected current version and a fresh mutation ID; another concurrent update may conflict again. Any editing/deletion/conflict choice must verify the selected item still matches, including pending changes. Do not silently resurrect remote deletions.

## Validation

All limits count UTF-16 code units. Required identity/display strings are nonempty, have no ISO control characters or malformed surrogate pairs, and equal their ECMAScript `trim()` result. Preserve exact case, normalization, interior spaces, punctuation and author order. Validation rejects rather than normalizes. Clients may normalize **display metadata only** before asking to adopt it; identity must never change.

| Field | Rule |
| --- | --- |
| `providerId` | 1–100 units, exact original provider ID. |
| `bookId` | 1–500 units, exact original provider book ID. |
| `book.title` | 1–500 units. |
| `book.authors` | 0–20 strings, each 1–200 units; order preserved, empty array means unknown. |
| `book.language` | 1–35 units; display/source language metadata. |
| `book.url` | 1–2048 visible ASCII characters; absolute HTTP(S) URI with hostname, no credentials/userinfo, backslash, literal whitespace or controls; port absent or 0–65535. Unicode path/query may be percent-encoded without altering `bookId`. The server stores this inert metadata and never fetches it. |
| `expectedVersion` | Integer 0–9007199254740990, ensuring increment is JSON-safe. |
| `mutationId` | Full hyphenated UUID. |

JSON is strict: duplicate/unknown/missing fields, trailing content, null/coerced values or fractional integers are invalid. Max PUT body is 32 KiB, including chunked bodies. Feed pages contain at most 100 items. Clients must bound response bodies, validate item identities and complete pagination, and keep storage errors visible without losing pending changes.

## Authentication and errors

GET requires Basic authentication; PUT also requires `X-CSRF-TOKEN` with its matching session cookie from `/api/v1/csrf`. Success and API problem responses carry `Cache-Control: no-store`. The owning account is selected exclusively by the authenticated principal. Each process allows 120 PUT attempts/minute/account and at most 10,000 active rate windows. Respect `Retry-After` and avoid retrying permanent errors until explicit retry/reconnection.

| HTTP | Code/meaning |
| --- | --- |
| 400 | `SOURCE_BOOK_FAVORITE_INVALID`, invalid JSON, value or cursor. |
| 400 | `SOURCE_BOOK_FAVORITE_MUTATION_REUSED`, changed payload for latest mutation ID. |
| 401/403 | Authentication/CSRF framework response. |
| 409 | `SOURCE_BOOK_FAVORITE_CONFLICT`, authoritative `current` included. |
| 409 | `SOURCE_BOOK_FAVORITE_REVISION_EXHAUSTED`, permanent safe-integer exhaustion; no `current`. |
| 413 | Request body exceeds 32 KiB. |
| 429 | `SOURCE_BOOK_FAVORITE_LIMIT`, retry after indicated seconds. |

The machine-readable contract is [source-book-favorites-v1.openapi.json](source-book-favorites-v1.openapi.json). Shared example request/item/feed fixtures live in `fixtures/source-book-favorites-v1/`. Feature evidence and platform limitations are recorded separately in [the synchronization document](../docs/SOURCE_BOOK_FAVORITES_SYNC.md).
