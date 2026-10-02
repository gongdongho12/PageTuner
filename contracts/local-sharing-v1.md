# Local library sharing v1

The phone hosts both a bundled web reader and a read-only library API. A browser
opens the `http://<phone LAN address>:<port>/` address shown by Android. No cloud
account, external website, Internet connectivity or browser service worker is
required. This protocol is independent of account synchronization and ZIP exchange.

The wire schema is [local-sharing-v1.openapi.json](local-sharing-v1.openapi.json).
Framework-free Kotlin DTOs live in `core-sharing`; `sharing-runtime` handles HTTP
and session policy; Android supplies local library and packaged web asset adapters.
The React sharing entry reuses the same reader, collection, search and preference
components as the hosted web application.

## Session lifecycle

1. The user enables sharing in Android and selects a private IPv4 interface. A
   foreground service hosts the endpoint and displays a fresh eight-digit code.
2. A browser loads the static page and posts `{ "code": "12345678" }` to
   `/api/share/v1/pair`. The example code is illustrative, not a default.
3. Authenticated requests send the returned token in `X-PageTuner-Session`.
   Tokens are kept in browser memory, never in a URL, cookie or persistent storage.
4. `DELETE /api/share/v1/session` disconnects one browser. Android Stop, service
   destruction or the sharing deadline invalidates every browser. Re-enabling
   sharing requires a new code and token.

The server is restricted to the selected literal address and port. It rejects
foreign Host/Origin values, does not allow CORS, and serves no filesystem paths,
account settings or cloud APIs. JSON errors contain a stable `code` and a safe
`message`. Pair attempts, sessions, request size and worker concurrency are bounded.
The transport is HTTP, not encrypted TLS; use a trusted private hotspot or Wi-Fi.
Connection codes restrict application access but do not encrypt network traffic.
Up to eight browser sessions are retained. A newly successful pairing replaces the
least recently used session when full, so memory-only tokens lost on refresh cannot
lock out reconnection for the entire sharing lifetime. Invalid codes never evict a session.

## Library snapshots and identity

- Book IDs are opaque local sharing handles. A title, filename, network address or
  screen page number is never used as a cross-device document identity.
- Canonical `paragraphId` values and their order are preserved. Anchors use UTF-16
  code-unit offsets into those paragraphs. Browser pagination is only presentation.
  Native PDF sharing uses a separate content-hash/physical-page projection; verified
  portable web PDF page identities are retained. Neither rewrites stored identities.
- A document's `revision` identifies the read snapshot. Asset requests must carry
  that exact revision; an expired snapshot cannot silently return a newer PDF or
  illustration. Re-open the document after a snapshot conflict.
- Shared data is an explicit allowlisted projection. It excludes filesystem paths,
  source account IDs, credentials, annotations, private settings and raw cache JSON.
- GET does not update the phone's last-opened timestamp, progress, organization,
  translation cache or library metadata. Reading positions in the connected browser
  remain in that browser session; this feature does not synchronize edits.

`GET /books` uses zero-based `offset` and `limit` (1–50). `GET /books/{id}` returns
paragraphs, optional outline/anchor and asset descriptors. PDF and bitmap bytes are
read separately through `/books/{id}/assets/{assetId}?revision=...`. Every library
and asset route requires a paired session. Text is limited to 4,000,000 UTF-16
characters per document and each binary asset to 32 MiB; adapters may enforce
tighter aggregate limits to protect device memory.

The original/translation `edition` comes from stored metadata. An incomplete
translation cache or a guessed provider/model/glossary variant is not published as
a finished translation. Cloud documents must first be downloaded to the device.

## Browser boundary

The phone bundle is generated from web sources during APK build. It has no external
CDN or cloud-login dependency, service-worker registration or required WebCrypto /
IndexedDB path. The API and static UI share one origin, avoiding a cloud HTTPS page
fetching a phone's local HTTP endpoint. PDF.js and its worker ship inside the bundle.

Only read tools (page movement, search, outline, images and reader preferences) are
available. Translation execution, uploads, deletion and phone-side editing are not
routes in this protocol. See [implementation and validation](../docs/LOCAL_LIBRARY_SHARING.md)
for supported local stores and hardware verification limits.
