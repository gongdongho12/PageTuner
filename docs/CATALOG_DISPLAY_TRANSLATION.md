# Catalog display translation

The web novel catalog and book description screen can translate the current titles and descriptions. Android already uses the shared `CatalogTranslationService` and `TranslationFieldSegmenter`; the server now uses those same implementations and `ChapterTranslationEngine`. This feature does not create chapter records or publish translation artifacts.

The web screen defaults to Google Web and the account's target language. Its settings also support Google Cloud, DeepSeek and OpenAI-compatible providers through the existing server provider configuration. API keys stay in the browser's current component and the executing server coroutine. Neither keys nor display translations are written to IndexedDB, files or the database. The UI can switch between original and translated display text while retaining the original book IDs, source URLs and navigation.

## Limits and lifecycle

- One request contains at most 24 unique books, 400 UTF-16 code units per title, 2,000 per description, and 24,000 in total. The browser explicitly reports when it translates only a bounded prefix; the server rejects oversized requests. Prefixes and shared 400-unit segments preserve surrogate pairs.
- The server permits one active display request per account and 16 globally. It retains at most 128 requests. Finished records last up to 15 minutes, may be evicted earlier at capacity, and disappear on restart.
- A request has a two-minute execution deadline. The browser polls segment progress, but results remain empty until every requested field is complete. Cancellation prevents a late provider response from publishing results. Changing the catalog or leaving the screen requests cancellation; an unreachable server still has its execution deadline.
- Request IDs are idempotent within the retained record's lifetime. Reusing an ID with different source text or provider settings returns a conflict. A new request is required after failure when changing settings. Credentials are excluded from the request signature and cannot change an existing request's execution.
- Provider endpoints use the same HTTPS validation and configured allowlist as chapter translation. Provider errors expose safe error codes, never exception text or submitted credentials. Google Web remains an unofficial external service and can fail or throttle independently of this application.

## HTTP contract

See [the OpenAPI contract](../contracts/catalog-translations-v1.openapi.json) and [shared fixtures](../contracts/fixtures/catalog-translations-v1/).

| Method | Path | Result |
| --- | --- | --- |
| POST | `/api/v1/catalog-translations` | Start or retrieve an identical request |
| GET | `/api/v1/catalog-translations/{requestId}` | Account-scoped state and complete display result |
| POST | `/api/v1/catalog-translations/{requestId}/cancel` | Cancel an active request |

All endpoints require the current account. POST requests reuse the token and session cookie from `/api/v1/csrf`. Responses use `Cache-Control: no-store`. The shared source digest uses SHA-256 over each item's UTF-16-length-prefixed key, title and description, with rows separated by LF. The browser verifies the digest, request identity, target/provider, field completeness and item order before displaying a result.

The Android failed-job list also maps safe server error codes to localized guidance for credentials, limits and interrupted work. Unknown codes use a general provider/connection message; raw error content is never displayed.

## Verification

The core regression checks lossless Unicode segmentation and unchanged segment identity for unaffected inputs. Server unit tests exercise the real shared field mapper and engine with a deterministic provider, account separation, request identity, cancellation races, complete results and safe errors. Web unit tests use the same contract fixtures and verify limits, source digests, authenticated CSRF transport and aborted polling. Android checks error-code mapping; its benchmark fixture matches the job card's 176 dp height.

Run the normal core, server and Android suites through the root Gradle build and `npm run verify` in `web`. Regenerate types with `npm run generate:api`. The separately tagged `CatalogTranslationLiveTest` requires explicit `RUN_LIVE_CATALOG_TRANSLATION_TESTS=1` and the `:server:catalogTranslationLiveTest` task; it sends a short real English title to Google Web through the shared runtime and checks the Korean result. It has no fixture fallback. Live evidence should be recorded only after that task passes.
