# PageTurner Web

Independent **Next.js App Router + React + TypeScript** workspace alongside `/app`
and `/server`. Android remains native; Spring owns persistent server records.
Next.js serves the web UI, proxies Spring requests on the same origin, and handles
translation provider requests. It does not duplicate the Spring database layer.

Next.js is a React framework, rather than an alternative to React. This project
uses it because a same-origin translation gateway and server proxy are useful
alongside an interactive, primarily client-rendered reader. References:
[React framework guidance](https://react.dev/learn/creating-a-react-app),
[Next.js installation](https://nextjs.org/docs/app/getting-started/installation).

## Run

Use Node 22.13+ (Node 24 LTS recommended).

```bash
cd frontend
npm ci
cp .env.example .env.local
npm run dev
```

Open <http://127.0.0.1:3000>. Library import, local reading and file backups work
without Spring. For server storage, start `/server` following its README and
enter its local account under **Settings → Server connection**.

```bash
npm run build
npm start
```

The default development and production commands bind to loopback. This is a
personal reader, not a public multi-user service. Before an internet-facing
release, provide application authentication and translation abuse limits,
HTTPS, and an operational credential policy. `PAGETURNER_SERVER_URL` and
`PAGETURNER_LLM_ENDPOINT` are server-side configuration, not `NEXT_PUBLIC_*` values.
The upstream LLM endpoint can only be configured by the deployer. Browser input
cannot make the gateway request arbitrary URLs; upstream redirects are refused.

## Implemented workflows

- TXT / UTF-8 Markdown import; SHA-256 duplicate detection; IndexedDB library.
- EPUB package/spine parsing, basic text/chapter normalization, script removal.
- PDF.js page rendering and text extraction; original PDF included in backups.
- Folder/tag organization, title/format/folder/tag search and paged collections.
- Page reading, keyboard/tap navigation, focus mode and measured text subpages.
  Expanded translations retain every character instead of overflowing the reader.
- Persistent document-page position, bookmarks, page notes and note export.
- Original / translation / dual display; app-aligned reading defaults and settings.
- English / Korean interface dictionaries and app-aligned settings categories.
- Google Web, Google Cloud and OpenAI-compatible translation gateway.
- Provider/model/language-aware translation cache; current-page and whole-book
  translation; pacing, pause/resume/cancel and failed-page retry. Pausing cancels
  an in-flight browser request; the upstream provider may already have received it.
  Completed results persist; the active queue itself does not survive a reload.
- Local library JSON export, version/schema/hash validation, merge preview and
  non-destructive restore. Existing books, cache and preferences win conflicts.
- Server library session login/logout, local source upload, book/chapter browsing,
  saved translation reading, paragraph-based position sync with explicit conflict
  resolution, and server bookmarks. Open **Library → Server**.
- Spring translation save, record backup download and JSON restore, including
  Basic credentials and per-write CSRF retrieval. No automatic mutation retries.
- PageTurner Web Catalog v0 browsing, relative URL resolution, source cache,
  title search and download/import. Anonymous browser fetch requires source CORS.
  Saved source lists and catalogs are included in library backups.
- Production service worker for the public application shell and static assets.
  After one complete online load, the shell and saved books can reopen offline.
  API responses and secrets are never service-worker cached. PDF rendering offline
  requires its reader chunk and worker to have been loaded online at least once.

Reader design follows [the project E-Ink guide](../docs/EINK_UI_GUIDE.md):
monochrome tokens, explicit paging, 44px controls, bounded collections and no
animation-dependent status. Touch scrolling applies only to collections after
explicit selection. It never changes the reader body into a scroll view.

## Storage and backup boundaries

Books, progress, notes, translation results, settings and public catalog metadata
are stored in IndexedDB on the **frontend origin**. Different ports/hosts are
separate libraries. Clearing browser data deletes these records. Download a
library backup to a separate disk or storage service for actual recovery.

API keys and server passwords remain in React memory for the current page session.
They are excluded from IndexedDB and every local export. Spring session cookies
are managed by Spring/browser rather than a local password store. Library backups
contain readable original books and translated text. Hashes detect corruption;
they do not authenticate the author or encrypt content.

Two JSON formats have separate restore actions:

1. `pageturner-web-backup`, schema 1: local books, reader metadata and translations.
   Restore previews the counts and adds missing books/cache entries. Existing
   records and settings are preserved, and repeated restore is idempotent.
2. Spring translation backup, schema 1: one server translation artifact. The
   server verifies identifiers/revisions/hashes and restores it to the signed-in
   owner. It does not contain the original book, bookmarks or reading positions.

See [the server API contract](../server/WEB_API.md).

## Limits and remaining integrations

- Imports: 50 MB per file; EPUB expanded size 100 MB; PDF at most 2,000 pages;
  normalized text at most 20,000 pages. Library backup input is capped at 100 MB.
- EPUB is text-first: embedded images, publisher styling, SVG and advanced layout
  are not reproduced. Markdown is read as text, never injected as HTML.
- Scanned PDF OCR, DRM, FTP/FTPS and Google Drive OAuth/upload are not implemented.
- Google Web is an unofficial public endpoint without an availability guarantee.
  It is limited to 5,000 characters per request; Cloud/LLM accept up to 30,000.
  API credentials, quotas and real provider availability must be verified with
  the user's own account. Automated tests mock paid/external providers.
- Changing the configured LLM endpoint while keeping the same model requires
  clearing the previous translation cache to avoid reusing the previous service.
- Local binary books and Spring source snapshots are distinct. Uploading a source
  snapshot is explicit; this is not automatic Android/browser library sync.

## Validation

```bash
npm run typecheck
npm test
npm run build
npm run test:e2e
E2E_PRODUCTION=1 npm run test:e2e  # includes offline restart tests
```

On macOS, Playwright uses installed Google Chrome by default. Elsewhere install
its Chromium with `npx playwright install chromium`, or set
`PLAYWRIGHT_CHROMIUM_EXECUTABLE`. E2E starts a loopback server on port 3100.
Tests cover backup corruption/idempotence, cache identity, Unicode boundaries,
provider errors and CSRF, plus desktop/mobile import/read/bookmark/restore,
translation cache, locale switching, EPUB/PDF, catalogs and offline startup.

## Layout

```text
frontend/
  src/app/                  Next routes, layout and monochrome CSS
  src/app/api/translate/    Allowlisted translation provider gateway
  src/components/          Library, reader, translation/backup/settings UI
  src/lib/                 Typed models, imports, storage, APIs and queue
  public/sw.js             Public asset/offline shell cache
  tests/                   Unit/contract tests and Playwright flows
```

## Managed local deployment and improvement checks

The full local stack uses the shared repository deployment manager.

```bash
npm run local:deploy # unit tests, build, candidate browser regression, promote
npm run local:verify # real browser → Next → Spring → PostgreSQL checks
npm run local:status
npm run local:down   # stop services, retain the database volume
```

The running release stays available while a new standalone release is built and
checked on port 3110. Only a passing candidate replaces port 3000. Credentials,
process records and releases remain in the repository's ignored `.local/` folder.
See [local deployment](../docs/LOCAL_DEPLOYMENT.md) and
[improvement history](../docs/LOCAL_IMPROVEMENT_LOG.md).

An optional sample book helps first-time readers try the app without choosing a
file. A page without a cached translation displays its original immediately.
The server library also accepts session credentials directly on its connection
screen, while keeping the settings-based connection workflow.
