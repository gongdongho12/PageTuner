# Shared translation execution

`translation-runtime` is a Kotlin/JVM module used by Android and the server. The existing Google Cloud, Google Web, DeepSeek, and OpenAI-compatible implementations, settings, term protection, Korean particle correction, batching, pacing and cost estimate have moved here. Their original packages and provider/cache IDs are preserved. Android-only reader pages, caches, queue state and UI remain in `app`.

```kotlin
val settings = TranslationSettings(
    providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
    apiKey = "", // no key for this public provider
    sourceLanguage = chapter.sourceLanguage,
    targetLanguage = "ko",
    paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
)
val identity = TranslationRuntimeIdentity.describe(settings, glossary)
val paragraphs = ChapterTranslationEngine().translate(
    chapter = chapter,
    settings = settings,
    glossary = glossary,
    completed = persistedParagraphsById,
    onParagraph = { paragraphId, text -> persistCheckpoint(paragraphId, text) },
    onProgress = { done, total -> publishProgress(done, total) },
)
// Build TranslationArtifact with chapter.sourceRevision, the concrete languages,
// identity's four fields, and these paragraphs, then save through TranslationStore.
```

Checkpoint maps must belong to the exact source revision and execution identity; the caller is responsible for that association and for durable checkpoint transactions. The engine rejects unknown IDs, blank values, invalid source language, unordered/duplicate paragraphs and paragraphs exceeding 1,000,000 UTF-16 characters. Long paragraphs are split into stable provider-only chunks of at most 1,000 characters for Google Web or 4,000 for the other providers. Source slices preserve whitespace and surrogate pairs, and glossary terms remain intact. A glossary term longer than the chunk limit is rejected. Only a fully reassembled paragraph is checkpointed under its original paragraph ID. If a job stops midway through a paragraph, resuming translates that entire unfinished paragraph again; completed paragraph checkpoints are reused. Every provider batch is validated before publishing any checkpoint, and progress advances only after each checkpoint callback succeeds. Maximum batch count is 24, with a 24,000-character batch target. Transient network/server/rate failures get at most two retries by default; cancellation, invalid configuration and invalid responses stop immediately.

The chapter engine uses a fixed prompt and glossary protection. It does not mutate aliases while translating. `TranslationRuntimeIdentity.describe` excludes credentials, includes the provider endpoint hash/model and fixed prompt revision, and includes only glossary terms that affect translation. Display aliases do not invalidate translations. Existing Android interactive alias discovery is still supported by the shared provider factory; callers enabling that separate mode must track its dictionary revision themselves. `BookGlossary.bookId` remains a caller-owned association so legacy Android document IDs stay compatible. Cost estimates retain the existing generic assumptions and are not live provider pricing.

All default transports use HTTPS (loopback HTTP is available for development), refuse redirects, disconnect on coroutine cancellation, bound connection/read/total duration, and cap responses at 4 MiB. Raw provider error bodies are not exposed because they can echo submitted text or credentials. HTTP adapters can be injected for deterministic tests. Provider credentials remain caller-owned; this module does not store them. Paid providers require a caller-supplied API key, and LLM providers also require an endpoint and model. Google Web's public endpoint needs no key but availability and throttling are outside the application’s control.

Ordinary unit tests are offline:

```powershell
.\gradlew.bat :translation-runtime:test
```

An explicit network test fails unless opted in (it is excluded from ordinary tests):

```powershell
$env:RUN_LIVE_TRANSLATION_TESTS = '1'
.\gradlew.bat :translation-runtime:googleWebTranslationLiveTest
```

`org.json` is compile-only here because Android supplies it. JVM consumers such as the server must provide `org.json` at runtime.
