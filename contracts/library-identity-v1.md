# Library document identity verification v1

This is the first, read-only part of local/ZIP identity support. It verifies that a text-only portable document matches one immutable server record owned by the current account. It does not import, upload, merge, overwrite, create a synchronization binding, authorize future synchronization, or transfer reading metadata. The client invokes it only after the user explicitly asks to verify a chosen server UUID.

## Portable provenance

`extensions.documentIdentity` contains the `LibraryDocumentIdentity` object defined by `library-identity-v1.openapi.json`. `extensions.serverRecordId` is an optional separate server UUID hint for an input field, not part of document identity. A hint is not ownership evidence and may refer to another server or account. Existing passive metadata, including legacy `extensions.server.recordId`, is retained. When a canonical hint is present but invalid, clients do not silently replace it with another hint.

Both kinds require exactly `version:1`, `kind`, `contentProviderId`, `bookId`, `chapterId`, `sourceRevision`, `sourceLanguage`, and `paragraphHash`. `kind` is `ORIGINAL` or `TRANSLATION`. Translation identities additionally require every field `targetLanguage`, `translationProviderId`, `modelId`, `promptRevision`, `glossaryRevision`, `artifactId`, `revision`, and `payloadHash`. Originals must omit all translation fields; null is not an alternate spelling. Empty model/prompt/glossary strings are preserved as explicit values.

Provider, book and chapter components are distinct original strings, never split from a canonical concatenation, generated from a UUID, inferred from a title, trimmed, case-folded or Unicode-normalized. They are nonblank and at most 2,000 UTF-16 units each. Translation provider is also nonblank and at most 2,000 units; model/prompt/glossary fields allow 0–2,000. Identity strings reject ISO controls and unpaired surrogates. Nonblank uses Kotlin whitespace semantics, as in the existing core content model. Language strings are preserved exactly and match `[A-Za-z][A-Za-z0-9-]{0,34}`; this intentionally retains legacy source `auto`. Originals require a lowercase 64-hex source revision. Translations retain legacy nonblank source revisions of 1–64 UTF-16 units (for example `source-v1`). All four explicit hash fields use lowercase 64-hex SHA-256.

The ordinary ZIP codec still treats all extensions as passive data. A missing, unknown-version or corrupt `documentIdentity` must not prevent reading an otherwise valid archive; it prevents this verification feature. No invalid proof is silently repaired or synthesized. Pure Kotlin helpers are `DocumentIdentities.original`, `.translation`, `.paragraphHash`, `.validate`, and `.validateDocument`; the runtime exposes `DocumentIdentityJson.encode`, `.decode`, `.fromDocument`, and `.withIdentity`. The JSON runtime accepts numeric one after the ZIP codec's documented binary64 normalization; HTTP requests still require an integer version literal.

## Ordered paragraph fingerprint

`paragraphHash` is independent of ZIP member checksums, display titles, notes, position, organization, glossary, timestamps and JSON formatting. It covers every original paragraph ID and exact text in order, including whitespace, line breaks and empty text. IDs are unique, nonblank, at most 500 UTF-16 units. The text-only representation contains 1–50,000 paragraphs and at most 5,000,000 UTF-16 text units. IDs and text reject unpaired surrogates before UTF-8 encoding.

Define `frame(s)` as ASCII decimal length of the UTF-8 bytes of `s` (no leading zeros), followed by the ASCII colon byte, followed by those exact UTF-8 bytes. Compute SHA-256 over this concatenation:

```text
frame("pageturner.document-paragraphs.v1")
+ frame(decimal paragraph count)
+ frame(paragraph[0].paragraphId) + frame(paragraph[0].text)
+ frame(paragraph[1].paragraphId) + frame(paragraph[1].text)
+ ...
```

Lengths count UTF-8 bytes, not UTF-16 units. The domain frame, count and individual frames prevent separator ambiguities even when IDs or content contain colons, pipes or newlines. Existing `sourceRevision`, `artifactId`, `revision` and `payloadHash` algorithms are unchanged for compatibility. Clients recompute the original source revision using ordinal `0..n-1` in ZIP order, or reconstruct the complete translation artifact and recompute all three translation hashes, in addition to verifying this new paragraph fingerprint. Comparing only a claimed hash is insufficient.

For local validation, document kind must equal the identity kind in lowercase; document language must exactly match source language for originals or target language for translations. This version rejects all documents with PDF or image asset references, including asset-only documents. PDF extraction/page indexes and EPUB reflow anchors are not remapped. Unproven independent local files remain independent. Shared fixtures include Unicode, delimiters and empty translation option fields in `fixtures/library-identity-v1`.

## Explicit server verification

`POST /api/v1/library-identity/verify` requires HTTP Basic authentication plus `X-CSRF-TOKEN` from `/api/v1/csrf` with its session cookie. POST avoids URL limits for long identities, but is read-only and creates no database rows. The request is exactly:

```json
{"kind":"ORIGINAL","recordId":"30ea7fb9-fc58-49de-adab-d2acfe91320e","identity":{"version":1,"kind":"ORIGINAL","contentProviderId":"source-a","bookId":"book-1","chapterId":"chapter-1","sourceRevision":"<64 lowercase hex>","sourceLanguage":"en","paragraphHash":"<64 lowercase hex>"}}
```

The endpoint reads the current account's source or translation record, checks its persisted source/artifact integrity, recomputes its portable identity from the full ordered paragraphs, and requires equality with every requested field. It never modifies the supplied identity to make a match. Success returns exactly `{kind,recordId,verified:true,identity}` with the verified identity, no null-only translation fields, and `Cache-Control: no-store`. The client checks the echoed tuple and full identity, invalidates the result on document/account/server/UUID changes, and does not persist it as permission for S1–S3 writes.

JSON is strict: unknown, duplicate, missing or wrongly typed keys, invalid Unicode, version coercion/fractional spelling and trailing data fail. The complete body is limited to 64 KiB before binding, including missing Content-Length. A successful result exposes no credentials or other account data.

| Status | Code / meaning |
| --- | --- |
| 400 | `LIBRARY_IDENTITY_INVALID`: invalid body, field, UUID or kind disagreement. |
| 401 / 403 | Authentication or CSRF rejection. |
| 404 | `LIBRARY_IDENTITY_NOT_FOUND`: nonexistent, foreign or wrong-kind UUID, without differentiating them. |
| 409 | `LIBRARY_IDENTITY_MISMATCH`: an owned record exists but some provenance/hash field differs. No current body is disclosed or overwritten. |
| 409 | `LIBRARY_IDENTITY_UNAVAILABLE`: stored legacy metadata, paragraphs, order or persisted hashes cannot produce a complete verified identity. No repair occurs. |
| 413 | Request exceeds 64 KiB; the early body filter may return generic Problem Detail. |

Database outages remain server errors, not identity mismatches. All endpoint success/failure responses are no-store. S4 follow-ups must separately define durable account/origin/local-record links, explicit progress/notes/classification adoption, stable legacy note/glossary ID mappings, tombstones and CAS, and richer asset anchor mappings. This verification result does not complete those features.
