# Reader preferences v1

`GET /api/v1/reader-preferences` and `PUT` at the same path synchronize one set of portable reading controls for the authenticated account. This is an account resource, independent of documents and document revisions. It contains no credentials or translation-provider settings.

## Portable values

Every non-null `preferences` object contains exactly these fields:

| Field | Type and allowed values | Meaning |
| --- | --- | --- |
| `fontSize` | integer, 14–36 | Reader text size in logical CSS pixels / Android sp. Physical rendering may differ by device. |
| `lineHeightPercent` | integer, 110–240 | Line-height multiplier times 100; for example 195 means 1.95. Integers avoid platform float rounding differences. |
| `pageMargin` | integer, 0–48 | Reader page margin in logical CSS pixels / Android dp. |
| `touchDirection` | `left-previous`, `left-next`, `buttons-only` | Direction for tapping the sides of a reader page; `buttons-only` disables touch-page turning. |
| `listMode` | `paged`, `scroll` | List-screen preference. Reader body stays paged in either mode. |

Font family, hardware-key behavior, display contrast, PDF fit, language, translation pacing, provider configuration and credentials remain outside this resource. Existing account language preferences retain their existing account endpoint. Clients retain their local-only fields when adopting or updating portable values. No preference field is optional and the server never clamps values.

## Authentication and scope

Both operations require HTTP Basic authentication. PUT also requires `X-CSRF-TOKEN` from `/api/v1/csrf` with its matching session cookie. Successful responses and endpoint errors use `Cache-Control: no-store`. The server uses the authenticated username; no request can choose another account. Client state and pending mutations must be separated by server origin and canonical authenticated account.

GET does not create a database row. Before the account explicitly saves preferences, it returns:

```json
{"version":0,"preferences":null,"updatedAt":null}
```

The server does not choose defaults. A newly connected client keeps its local preferences and shows an explicit choice to publish them or adopt existing server values before enabling automatic sync. Merely opening settings or logging in must not publish defaults over another device's choices. An absent server value is not a command to reset local settings.

PUT accepts exactly:

```json
{"expectedVersion":0,"mutationId":"4c187bd5-ccdf-4ce9-bff3-5c94bdb56177","preferences":{"fontSize":20,"lineHeightPercent":195,"pageMargin":28,"touchDirection":"left-previous","listMode":"paged"}}
```

A successful response contains the positive version, all portable values and the server's UTC ISO 8601 `updatedAt`. This timestamp is informational; only the version controls concurrency.

```json
{"version":1,"preferences":{"fontSize":20,"lineHeightPercent":195,"pageMargin":28,"touchDirection":"left-previous","listMode":"paged"},"updatedAt":"2026-09-30T13:00:00Z"}
```

Response versions are JSON-safe integers in `0..9007199254740991`. Request `expectedVersion` is an integer in `0..9007199254740990`. UUIDs use the complete hyphenated form. JSON rejects unknown or duplicate fields at every level, omitted/null values in requests, string-coerced or fractional numbers, and trailing JSON. Requests are bounded to 8 KiB, including requests without Content-Length.

## Compare-and-set, offline updates and conflicts

1. Read the current view, preserving existing local settings until the initial explicit choice.
2. Persist a pending mutation with the observed version, a new UUID and the complete desired portable values before sending.
3. PUT increments the version atomically. A per-account PostgreSQL transaction lock serializes first writes and later writes across server processes.
4. If a response is lost, retry the exact same mutation ID, expected version and preferences. If it is still the latest committed mutation, the server acknowledges the same stored view, including timestamp, without incrementing it.
5. Reusing the latest mutation ID for a different expected version or different values returns `400 READER_PREFERENCES_MUTATION_REUSED`. Once another mutation commits, an old mutation is subject to ordinary CAS; the server keeps only the latest mutation.
6. A stale expected version returns `409 READER_PREFERENCES_CONFLICT` with `current`. Preserve pending local values and offer an explicit choice to use server values or keep local values. A local choice creates a **new** mutation against the displayed server version. Never silently replace the expected version and overwrite a concurrent edit.

```json
{"status":409,"code":"READER_PREFERENCES_CONFLICT","current":{"version":2,"preferences":{"fontSize":22,"lineHeightPercent":150,"pageMargin":18,"touchDirection":"left-next","listMode":"paged"},"updatedAt":"2026-09-30T13:01:00Z"}}
```

The `current` view may also be version zero with null values when the caller incorrectly expected a nonzero version. If it changes again before conflict resolution commits, show the new conflict. Local edits made while a request is in flight must remain pending after an earlier acknowledgement; old GET/PUT responses must not roll back newer state. Guard conflict-choice actions against both the displayed remote version and pending local values changing.

Once a client has explicitly joined synchronization, coalesce changes and retry transient network failures with backoff. Preserve pending updates across application restarts and reader/settings screen closures. Authentication failures, malformed responses and version exhaustion must not be shown as synchronized. Honor Retry-After across this account's preference retries. Disconnecting or switching accounts must not apply a late response to another account's local settings.

Reset is an explicit ordinary PUT of the desired default values and follows the same CAS rules. There is no DELETE or special reset endpoint, no per-document override and no automatic reset when a server document is deleted.

## Errors and limits

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | `READER_PREFERENCES_INVALID` | Invalid JSON, version, UUID or portable preference values. |
| 400 | `READER_PREFERENCES_MUTATION_REUSED` | Latest mutation ID reused for different content. |
| 401 / 403 | Framework authentication/CSRF response | Reauthenticate or renew CSRF; keep local pending values. |
| 409 | `READER_PREFERENCES_CONFLICT` | `current` provides the server view for an explicit choice. |
| 409 | `READER_PREFERENCES_EXHAUSTED` | Maximum version reached; terminal, no `current` conflict to resolve. |
| 413 | Body-limit Problem Detail | Body exceeds 8 KiB. |
| 429 | `READER_PREFERENCES_LIMIT` | At most 120 PUT attempts per account per minute per process. Obey `Retry-After` seconds. |

Rate-limit bookkeeping is bounded to 10,000 active accounts per server process. Full bookkeeping returns 429 instead of evicting existing limits. GET does not consume the write limit. At maximum version, the latest exact mutation retry still succeeds; a different valid write returns EXHAUSTED without incrementing or wrapping the version.
