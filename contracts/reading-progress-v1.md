# Reading progress v1

This contract synchronizes the reading position of an existing, immutable server library record between app and web. It covers `ORIGINAL` records from `/api/v1/chapters` and `TRANSLATION` records from `/api/v1/translations`. Local-only files, bookmarks, highlights, notes, folders, glossary entries and favorites are outside this version.

## Identity and ownership

`GET /api/v1/reading-progress/{kind}/{recordId}` and `PUT` at the same path use uppercase `ORIGINAL` or `TRANSLATION` and the server record UUID. Keys are scoped to the authenticated username. A source record and its translation have independent positions; their character offsets are not interchangeable. Different revisions are different records and do not inherit positions. Missing and other users' records both return `404 READING_PROGRESS_NOT_FOUND`, even when no progress has been saved.

Both operations require HTTP Basic authentication. PUT also requires `X-CSRF-TOKEN` from `/api/v1/csrf` with the matching session cookie. Responses use `Cache-Control: no-store`. Reading position caches and outboxes must additionally be scoped to server origin and authenticated account.

## Wire shapes

GET returns a view, including explicit nulls before the first write:

```json
{"kind":"ORIGINAL","recordId":"30ea7fb9-fc58-49de-adab-d2acfe91320e","version":0,"anchor":null,"updatedAt":null}
```

PUT accepts exactly these fields:

```json
{"expectedVersion":0,"mutationId":"4c187bd5-ccdf-4ce9-bff3-5c94bdb56177","anchor":{"paragraphId":"p-1","characterOffset":12}}
```

Successful PUT returns the same view shape with a positive version, its anchor, and the server's UTC ISO 8601 `updatedAt`. `updatedAt` is informational; clients must use the version for concurrency. There is no client timestamp or device-priority rule.

`paragraphId` must identify exactly one paragraph in the owned record, be 1–200 UTF-16 code units, and contain no ISO control characters. `characterOffset` is an integer between zero and that paragraph's text length, measured in UTF-16 code units. Offsets inside a surrogate pair are rejected. For a translation, use the translated text and its persisted paragraph IDs; for an original, use the original text. Paragraph-end offsets are permitted.

Versions are JSON-safe integers. A response version is `0..9007199254740991`; request `expectedVersion` is `0..9007199254740990`. UUIDs use the full hyphenated representation. Request JSON rejects unknown or duplicate fields, missing values, fractional/string-coerced numbers and trailing JSON. The entire request body is limited to 8 KiB, including requests without Content-Length.

## Compare-and-set and retries

1. Read the current view. A document with no position has version zero; an absent document is a 404 instead.
2. Persist a local outbox entry containing the observed version, a newly generated UUID mutation ID, and the desired anchor before sending it.
3. PUT that entry. A successful write atomically increments the version by one. Concurrent creates and updates are serialized in PostgreSQL, including across server processes.
4. If a response is lost, retry the exact same mutation ID, expected version and anchor. If it remains the latest committed mutation, the server acknowledges the unchanged view without incrementing the version or timestamp.
5. A latest mutation ID reused with a different expected version or anchor returns `400 READING_PROGRESS_MUTATION_REUSED`.
6. A stale expected version returns `409 READING_PROGRESS_CONFLICT`, with the current view in the `current` property. An older mutation retried after another device's write also conflicts; the server retains only the latest mutation, not an unbounded history.

Example conflict (other standard Problem Detail fields may also be present):

```json
{"status":409,"code":"READING_PROGRESS_CONFLICT","current":{"kind":"ORIGINAL","recordId":"30ea7fb9-fc58-49de-adab-d2acfe91320e","version":2,"anchor":{"paragraphId":"p-2","characterOffset":0},"updatedAt":"2026-09-15T16:00:00Z"}}
```

The client retains its pending position and presents a choice to use the server position or explicitly save the local position against the returned version with a **new** mutation ID. Never automatically replace an expected version and resubmit a stale position: an offline device must not silently overwrite another device's progress. If the new write conflicts again, repeat the choice using the newer server view. Do not treat authentication, rate limits or network failures as successful synchronization.

## Errors and lifecycle

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | `READING_PROGRESS_INVALID` | Invalid path, JSON, version or anchor. |
| 400 | `READING_PROGRESS_MUTATION_REUSED` | Latest mutation ID was reused for different content. |
| 401 / 403 | Framework authentication/CSRF response | Reauthenticate or renew CSRF; keep the pending position. |
| 404 | `READING_PROGRESS_NOT_FOUND` | Record is absent or belongs to another account. |
| 409 | `READING_PROGRESS_CONFLICT` | `current` is authoritative; pending local data is retained for an explicit choice. |
| 413 | Body limit Problem Detail | Request exceeds 8 KiB. |
| 429 | `READING_PROGRESS_LIMIT` | At most 120 PUT attempts per account per minute per server process; obey `Retry-After` seconds. |

Rate-limit state is bounded to 10,000 active account windows per process; a full window table returns 429 rather than evicting existing limits. GET does not consume the PUT limit. Clients should coalesce ordinary page changes and retry failures with backoff instead of sending on each layout update. Deleting the source or translation record cascades deletion of its server progress. There is no account-wide progress list, progress deletion or local-file identity mapping in v1.
