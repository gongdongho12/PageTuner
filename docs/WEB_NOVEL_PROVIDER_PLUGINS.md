# Web Novel Provider Plugins

This document defines the installable boundary for adding another web-novel
website. A provider plugin packages stable metadata and an adapter factory;
catalog UI, routing, translation, offline storage, and the reader do not gain
provider-specific branches.

## Summary

- Package provider identity, default catalog metadata, and adapter creation as
  one validated plugin.
- Derive built-in source accounts and URL ownership from plugin manifests.
- Verify a newly installed sample website through the complete common flow and
  rerun NovelBuddy against the real website through its plugin registry.

## Main files

| File | Responsibility |
| --- | --- |
| `WebNovelProviderPlugin.kt` | Plugin manifest, adapter factory, and built-in provider list |
| `WebNovelSiteAdapter.kt` | Plugin-based registry construction and runtime registration |
| `RemoteSourceAccountStore.kt` | Default accounts derived from discoverable manifests |
| `WebNovelProviderPluginFullFlowTest.kt` | Reusable new-site end-to-end contract |
| `NovelBuddyFullFlowLiveTest.kt` | Actual remote flow resolved through the NovelBuddy plugin |

## Plugin composition

```mermaid
flowchart TD
    A["New website"] --> B["WebNovelProviderManifest"]
    A --> C["WebNovelSiteAdapter implementation"]
    B --> D["FactoryWebNovelProviderPlugin"]
    C --> D
    D --> E["WebNovelSiteAdapterRegistry"]
    E --> F["Common catalog search and paging"]
    E --> G["Common book and chapter routing"]
    E --> H["Common original extraction"]
    H --> I["Translation service"]
    H --> J["Offline original storage"]
    I --> K["Offline translated storage"]
    J --> L["E-Ink reader"]
    K --> L
```

`WebNovelProviderManifest` owns the provider ID, display name, stable account
ID, and optional default catalog URL. `WebNovelProviderPlugin` creates the
adapter and validates that the manifest and adapter IDs agree. A provider with
no default catalog URL can remain a fallback without appearing as a saved
source account.

The built-in list currently installs WTR-LAB, NovelBuddy, and the generic HTML
fallback in that order. Default source accounts are derived from discoverable
plugin manifests, so adding a default provider no longer requires another
hostname branch in `WebCatalogViewModel`.

## Add another website

1. Implement `WebNovelSiteAdapter` for URL classification, catalog/detail/
   chapter parsing, search URLs, and chapter loading.
2. Declare one `WebNovelProviderManifest` with a unique ID and stable account
   ID.
3. Wrap both in `FactoryWebNovelProviderPlugin`.
4. Register the plugin before the generic fallback.
5. Run the reusable plugin contract and a provider-specific opt-in live test.

```kotlin
val myProvider = FactoryWebNovelProviderPlugin(
    manifest = WebNovelProviderManifest(
        id = "my-provider",
        displayName = "My Provider",
        accountId = "default_my_provider",
        defaultCatalogUrl = "https://novels.example/catalog",
    ),
    adapterFactory = ::MyProviderSiteAdapter,
)

val registry = WebNovelSiteAdapterRegistry.fromPlugins(
    listOf(myProvider, WebNovelProviderPlugins.genericHtml),
)
```

## Full-flow contract

`WebNovelProviderPluginFullFlowTest` installs a completely new sample website
through a plugin-only registry. It then runs catalog → book → chapter list →
original download using `WebNovelRemoteBookSource` unchanged. This is the
minimum contract test to copy for the next real provider.

```mermaid
sequenceDiagram
    participant Test
    participant Plugin as Provider plugin
    participant Registry as Adapter registry
    participant Source as WebNovelRemoteBookSource
    participant Website as Provider responses

    Test->>Plugin: createAdapter()
    Plugin-->>Test: validated adapter
    Test->>Registry: fromPlugins(plugin)
    Test->>Source: loadCatalogPage(1)
    Source->>Registry: resolve(catalog URL)
    Registry-->>Source: provider adapter
    Source->>Website: catalog request
    Website-->>Source: provider document
    Source-->>Test: normalized book
    Test->>Source: loadNovelDetail() and list()
    Source->>Website: detail and chapter-index requests
    Source-->>Test: stable series and chapter identity
    Test->>Source: download(chapter)
    Source->>Website: original chapter request
    Source-->>Test: validated UTF-8 original
```

The opt-in `NovelBuddyFullFlowLiveTest` also constructs its registry from the
NovelBuddy plugin. It verifies real keyword + genre search, detail parsing, the
complete remote chapter index, and original body extraction without WebView.

## Real-site evidence

The following values were observed directly from NovelBuddy at
`2026-08-15 19:02 PHT`. They are recorded as evidence, not frozen assertions;
catalog and chapter counts may naturally increase after this run.

| Observation | Actual remote value |
| --- | --- |
| Search request | [`q=shadow slave`, `genres=fantasy`](https://novelbuddy.me/search?q=shadow%20slave&genres=fantasy) |
| Search catalog | 159 works, 7 remote pages |
| Selected work | [`Shadow Slave`](https://novelbuddy.me/shadow-slave) |
| Parsed author/status | `Guiltythree` / `ongoing` |
| Detail chapter count | 3,157 |
| Loaded chapter index | 3,157 entries; equal to detail count |
| First chapter | [`Chapter 1: Nightmare Begins`](https://novelbuddy.me/shadow-slave/chapter-1-nightmare-begins) |
| Parsed original body | 91 paragraphs, 10,560 body characters |
| Serialized reader text | 10,591 characters including the Markdown title |
| Render path | Direct HTTP + Next.js JSON; `WebView=false` |

```mermaid
flowchart LR
    A["Real HTTP search<br/>159 works / 7 pages"] --> B["Shadow Slave detail<br/>Guiltythree / ongoing"]
    B --> C["Real chapter-index API<br/>3,157 entries"]
    C --> D["Real chapter HTML<br/>Chapter 1: Nightmare Begins"]
    D --> E["Parser output<br/>91 paragraphs / 10,560 chars"]
    E --> F["Common reader original<br/>10,591 chars"]
```

This is not the sample fixture flow. The live test uses the production
`WebNovelHttpClient`, a plugin-only adapter registry, and `renderedChapterLoader = null`.
Therefore a fixture, cached rendered page, or WebView fallback cannot satisfy
the test. It additionally asserts the exact title and author, a catalog larger
than one page, more than 3,000 chapters, equality between detail/index counts,
the first chapter number and series identity, a body longer than 5,000
characters, and the real character name `Sunny`.

The test prints a `LIVE_WEB_NOVEL_EVIDENCE` block into the JUnit output with the
actual values above, allowing subsequent opt-in runs to be compared without
weakening assertions when the remote catalog grows.

## Verification

| Check | Result |
| --- | --- |
| Plugin manifest/adapter validation | PASS |
| Plugin-only registry composition | PASS |
| New sample provider full-flow contract (deterministic fixture) | PASS |
| Default accounts derived from manifests | PASS |
| Full debug unit suite | PASS |
| Debug lint and APK assembly | PASS |
| NovelBuddy real plugin flow (production HTTP, no fixture/WebView) | PASS |

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

RUN_LIVE_WEB_NOVEL_TESTS=1 ./gradlew :app:testDebugUnitTest \
  --tests 'com.dongholab.pagetuner.source.NovelBuddyFullFlowLiveTest'
```

Remote live tests remain opt-in because provider availability, throttling, and
DOM changes are not deterministic build inputs.
