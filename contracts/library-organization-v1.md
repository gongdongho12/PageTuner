# Library organization v1

`GET` and `PUT /api/v1/library-organization/{kind}/{recordId}` synchronize folder, ordered tags and favorite state for one existing server document owned by the authenticated account. `kind` is exactly `ORIGINAL` or `TRANSLATION`; `recordId` is its complete hyphenated UUID. Originals and translations are independent even when derived from the same chapter. Clients must label this as organization **for this document**, not for its entire source book.

This resource does not infer identity from title, book name, page number or content. Standalone local/ZIP documents, source-book favorites, account-wide organization indexes, renaming or deleting a folder globally and glossary synchronization are outside this version.

## Values and canonical strings

Every non-null `organization` contains exactly:

| Field | Contract |
| --- | --- |
| `folder` | String of 0–200 UTF-16 code units. Empty means no folder. |
| `tags` | Ordered array of 0–32 strings, each 1–60 UTF-16 code units. Exact case-sensitive duplicates are rejected. |
| `favorite` | Boolean. |

Strings must already be trimmed: neither edge may contain U+0009–000D, U+0020, U+00A0, U+1680, U+2000–200A, U+2028–2029, U+202F, U+205F, U+3000 or U+FEFF (the ECMAScript trim set). Any U+0000–001F or U+007F–009F control character, and any unpaired UTF-16 surrogate anywhere, is rejected. Valid surrogate pairs count as two UTF-16 units. These rules apply to the empty folder only where meaningful; tags cannot be empty. The server does not trim, truncate, sort, deduplicate, case-fold or normalize Unicode. For example `Fantasy`, `fantasy` and `Ｆａｎｔａｓｙ` are distinct tags; preserve their spelling and order in retries.

## Authentication and identity

Both methods require HTTP Basic authentication. PUT also requires `X-CSRF-TOKEN` from `/api/v1/csrf` and its matching session cookie. Success and endpoint errors are `Cache-Control: no-store`. Authentication chooses the account; no payload field may choose another user. Missing documents, foreign documents and a valid UUID with the wrong kind all return the same `404 LIBRARY_ORGANIZATION_NOT_FOUND`. Client journals must be keyed by server origin, canonical authenticated account, kind and record UUID.

GET creates no row. A document without saved organization returns:

```json
{"kind":"ORIGINAL","recordId":"048586be-de37-4e17-a374-b0c375fcd65e","version":0,"organization":null,"updatedAt":null}
```

PUT accepts exactly:

```json
{"expectedVersion":0,"mutationId":"4c187bd5-ccdf-4ce9-bff3-5c94bdb56177","organization":{"folder":"Reading","tags":["Fantasy","한국어"],"favorite":true}}
```

Successful views have a positive version, the complete stored organization and a server UTC ISO 8601 `updatedAt`. The timestamp is informational, never a concurrency token. Response versions are JSON-safe integers in `0..9007199254740991`; request `expectedVersion` is an integer in `0..9007199254740990`. Requests reject unknown/duplicate/omitted/null fields, coerced numbers or booleans, fractional versions and trailing JSON. JSON bodies are limited to 8 KiB including requests without Content-Length; field limits do not guarantee every escaping-heavy combination fits the transport limit.

## Compare-and-set and recovery

1. Preserve local values until the user explicitly publishes them or adopts server organization. Logging in or opening a document must not publish defaults. A null organization is absence, not an instruction to clear a local classification.
2. Persist the complete pending request with a new UUID and the observed version before PUT. Updates serialize per account/kind/record across server processes and atomically increment the version. The document is protected against concurrent deletion while writing.
3. Retry response loss with the exact same mutation ID, expected version and organization. If still the latest committed mutation, it returns the same view and timestamp without incrementing. Reusing that latest ID with different content returns `400 LIBRARY_ORGANIZATION_MUTATION_REUSED`. Older IDs receive ordinary CAS handling after another mutation commits.
4. A stale expected version returns `409 LIBRARY_ORGANIZATION_CONFLICT` with `current` containing the complete current view, possibly the version-zero/null view. Preserve pending local values and require explicit local/server selection. Choosing local creates a new mutation against the displayed version; never silently replace the expected version and overwrite a concurrent edit.
5. Preserve edits made during an in-flight request; late acknowledgements must not erase later edits or conflicts. Conflict choice must compare the displayed remote version and local values to current state. Apply no late responses across account, server or document switches.
6. Persist pending requests across restarts; retry transient network failures with backoff. Honor `Retry-After` across all organization writes for the account. Authentication failures, malformed responses, missing documents and version exhaustion are not synchronization success.

An explicit clear is an ordinary PUT of `{"folder":"","tags":[],"favorite":false}`. It keeps a positive version and follows CAS; there is no DELETE. Deleting the underlying owned server document cascades its organization row; it does not affect another original or translation.

## Errors and limits

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | `LIBRARY_ORGANIZATION_INVALID` | Invalid path, JSON, version, UUID or organization values. |
| 400 | `LIBRARY_ORGANIZATION_MUTATION_REUSED` | Latest mutation ID reused for different content. |
| 401 / 403 | Framework authentication/CSRF response | Reauthenticate or renew CSRF; retain pending values. |
| 404 | `LIBRARY_ORGANIZATION_NOT_FOUND` | Document missing, foreign or wrong kind. |
| 409 | `LIBRARY_ORGANIZATION_CONFLICT` | `current` provides the view for explicit choice. |
| 409 | `LIBRARY_ORGANIZATION_EXHAUSTED` | Terminal maximum version; no `current` to resolve. |
| 413 | Body-limit Problem Detail | More than 8 KiB. |
| 429 | `LIBRARY_ORGANIZATION_LIMIT` | 120 PUT attempts/account/minute/process across all documents. Honor `Retry-After` seconds. |

Bookkeeping is bounded to 10,000 active accounts/process, returning 429 if full instead of evicting active limits. GET does not consume the write limit. At maximum version the latest exact retry still succeeds; other valid writes return EXHAUSTED without wrapping the version.
