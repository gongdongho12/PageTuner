# PageTurner Translation Providers

PageTurner keeps translation behind `TranslationProvider` so the reader,
pagination, pacing, and offline cache do not depend on a single vendor.

## Current Providers

- `GOOGLE_CLOUD`
  - Provider: `GoogleCloudTranslationProvider`
  - Input: Google Cloud Translation API key
  - Best for deterministic machine translation.

- `GOOGLE_WEB_TRANSLATE_HTML`
  - Provider: `GoogleWebTranslateHtmlProvider`
  - Input: Google Web Translate API key
  - Endpoint: `https://translate-pa.googleapis.com/v1/translateHtml`
  - Best for HTML-segment translation using the web translate request shape.
  - The app does not embed copied API keys, browser validation headers, or
    session-specific headers in source.

- `OPENAI_COMPATIBLE_LLM`
  - Provider: `OpenAiCompatibleLlmTranslationProvider`
  - Input: API key, chat-completions endpoint, model name
  - Best for gateways or LLM APIs that follow the OpenAI-compatible
    `/v1/chat/completions` response shape.

## Extension Points

To add another provider:

1. Add a value to `TranslationProviderKind`.
2. Implement `TranslationProvider`.
3. Wire it in `TranslationProviderFactory`.
4. Add provider-specific settings to `TranslationSettings`.
5. Add provider UI fields in `TranslationControls` only if the provider needs
   extra configuration.

The cache key includes `provider.id`, so results from Google Cloud, Google Web
HTML, LLM, and future providers stay separated even when document, page, and
language pair match.

## Provider Contract

Providers receive a `TranslationRequest` containing ordered text segments and
must return exactly one `TranslatedSegment` per input segment, preserving ids and
order. Providers should not merge, split, summarize, or add commentary.

The repository handles:

- batching
- paced delays
- offline cache writes
- page/document prefetch
- cache lookup

The provider handles only:

- request formatting
- remote API call
- response parsing
- provider-specific validation
