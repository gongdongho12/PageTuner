# Reading translation previews v1

This authenticated API translates only the original ranges currently needed by a paged reader. It never creates a complete translation artifact, source row, or durable translation checkpoint. Complete chapter translation and library saving continue to use `/api/v1/translation-jobs`.

`POST /api/v1/reading-translations`, `GET /api/v1/reading-translations/{requestId}`, and `POST /api/v1/reading-translations/{requestId}/cancel` share the existing account authentication, CSRF, request byte limits, provider endpoint allowlist, language validation and glossary processing. Every response uses `Cache-Control: no-store`.

```json
{
  "requestId": "22222222-2222-4222-8222-222222222222",
  "chapterRecordId": "11111111-1111-4111-8111-111111111111",
  "sourceRevision": "<64 lowercase SHA-256 hex>",
  "fragments": [{ "paragraphId": "p-one", "start": 0, "end": 9 }],
  "providerKind": "GOOGLE_WEB_TRANSLATE_HTML",
  "targetLanguage": "ko",
  "readingWordsPerMinute": 210,
  "paceMode": "READING",
  "glossary": []
}
```

`requestId` and `chapterRecordId` are UUIDs. The server loads only the caller's stored original, verifies its actual content revision, then extracts the text itself. The client supplies paragraph IDs and half-open UTF-16 ranges, never substitute source text. Ranges must be nonempty, within an existing paragraph, and not split a surrogate pair. Requests contain 1–64 distinct ranges with at most 24,000 total source characters, excluding whitespace-only ranges. Overlapping ranges are allowed because display aliases can span page boundaries. Optional `sourceLanguage`, `endpoint`, `model`, `apiKey` and `glossary` use the same semantics as full chapter translation. Credentials stay in the running coroutine and are never included in task records or responses.

WPM is an integer from 120 to 420, default 210. `paceMode` is `READING` (default), `FAST` or `OFFLINE_PREFETCH`. The shared runtime derives the delay between provider batches from words / WPM × 60,000: READING clamps to 750–14,000 ms; FAST uses 28% clamped to 250–4,000 ms; OFFLINE_PREFETCH uses 8% clamped to 80–1,200 ms.

Responses have `scope: "READING_PREVIEW"`, `requestId`, `status`, `chapterRecordId`, `sourceRevision`, `sourceHash`, `providerKind`, normalized `targetLanguage`, `completedFragments`, `totalFragments`, `items`, nullable `errorCode`, and ISO `updatedAt`. Status is QUEUED, RUNNING, COMPLETED, FAILED or CANCELLED. Items are empty until the entire requested batch is complete. Completed items preserve the exact ordered requested `{paragraphId,start,end}` and add translated `text`. A completed batch is only a reading preview; no `translationRecordId`, artifact revision or full-chapter payload hash is emitted. Output is bounded to 96,000 total characters.

The source hash is SHA-256 of UTF-8 `chapterRecordId + "\n" + sourceRevision + "\n" + fragments.map(f => f.paragraphId.length + ":" + f.paragraphId + ":" + f.start + ":" + f.end).join("\n")`. Lengths and ranges use UTF-16. Clients verify this hash, source identity, request ID, provider and target language, and every returned range before using results.

One reading job may be active per account; at most eight globally. The registry holds at most 128 entries, expires finished entries after 15 minutes, and may evict the oldest finished entry sooner under pressure. Jobs time out after 120 seconds. Reusing a request UUID with identical source ranges and effective configuration returns that job; changing the source, glossary, provider settings, WPM or pace with the same UUID returns 409. Retries after failure/cancellation use a new UUID. Cancellation takes precedence over late provider output. A cancelled job retains its account and global execution slot until its provider coroutine has actually completed cleanup; starting another job during that cleanup returns 429. Entries whose workers are still alive cannot be evicted or expired. Cancelling an as-yet-unknown UUID returns 404 but retains an account-scoped cancellation marker for 15 minutes, preventing a delayed POST with that UUID from starting; up to 512 such markers are retained, then new markers return 429. Server restart discards all reading previews; the original and complete translation artifacts remain available.

The web reader uses actual rendered source page boundaries: the current page's aligned block contains 10 pages, and reaching its fifth page begins preparing the next block. The current page is prioritized, repeated ranges reuse the session cache, and changing layout or translation settings invalidates pending work. The reader displays a clear temporary-reading label and keeps full chapter saving on its existing action. Closing or stopping the reader cancels its active request; WPM and pace may be stored per account, but API credentials and preview text are not persisted.
