# Passive book glossary snapshots v1

This optional [library exchange v1](library-exchange-v1.md) document extension preserves complete glossary values under their original provider/book/target-language scope. It is an offline snapshot contract, not an account binding, synchronization request, deletion command or statement that the snapshot is still current. Reading or importing it must not change local or server glossaries, start synchronization, or upload/delete records.

## Shape

The extension is stored at `document.extensions.bookGlossarySnapshots`:

```json
{
  "version": 1,
  "snapshots": [{
    "providerId": "wtr-lab",
    "bookId": "https://wtr-lab.com/en/novel/42/river",
    "targetLanguage": "ko",
    "presence": "present",
    "entries": [{
      "id": "original-entry-2",
      "sourceTerm": " Alice ",
      "translatedTerm": " 앨리스 ",
      "displayTerm": " 아리 ",
      "kind": "Character",
      "caseSensitive": true,
      "enabled": true
    }]
  }]
}
```

All displayed fields are required, including `entries` when null and every one of the seven entry fields. Extra or missing fields, duplicate JSON object keys, coercion of string/boolean values, and unsupported versions fail typed interpretation. `version` is the numeric value one under the exchange format's existing binary64 number rules; a string `"1"` is invalid. JSON property order is insignificant. Snapshot and entry array order is preserved exactly.

`snapshots` contains 0–100 scopes. This fixed extension limit is independent of the package's document count. The exact tuple `(providerId, bookId, targetLanguage)` must be unique within this extension. Several target languages for the same original book are distinct scopes, as are the same book ID under different providers. Do not join identity fields with an ambiguous delimiter, sort entries, deduplicate source text, or replace original IDs with a title, document UUID, Android local book ID or content hash.

## Presence and entry values

The account API distinguishes an absent snapshot from a saved deletion using its version. This extension preserves that distinction without transferring the account version:

| `presence` | Required `entries` | Meaning |
| --- | --- | --- |
| `absent` | `null` | No account snapshot existed when the value was observed. |
| `deleted` | `null` | A saved deletion was observed. This is passive data, not permission to delete anything on import. |
| `present` | Array, including `[]` | A saved glossary was observed. `[]` is an intentionally present empty glossary. |

An omitted extension or omitted scope means no snapshot was supplied; it is not an absent/deleted value. No importer may resurrect a deleted or empty snapshot by silently falling back to the legacy document `glossary` array. Legacy entries remain separate data and retain their existing representation. Converting the extension into an active glossary requires a separate explicit adoption flow, including applicable identity and destination checks.

Scope and entry validation is identical to [book glossary synchronization v1](book-glossary-v1.md):

- Lengths count UTF-16 code units. `providerId` is 1–100, `bookId` is 1–2000, and an entry `id` is 1–200. Identity fields reject boundary ECMAScript whitespace, ISO control characters and malformed Unicode instead of trimming them.
- `targetLanguage` is 2–24 characters, matches `[a-z]{2,8}(?:-[a-z0-9]{1,8})*`, and is not `auto`. Case and spelling are never silently changed.
- `sourceTerm` and `translatedTerm` are 1–200 and nonblank after ECMAScript trim. `displayTerm` is 0–200 and can be empty or whitespace-only. All three preserve their exact outer whitespace, reject ISO controls and unpaired surrogates, and are never truncated.
- `kind` is exactly `Character`, `Place` or `Term`. `caseSensitive` and `enabled` are required booleans. Disabled entries, display-only aliases, duplicate source terms and all original entry IDs are retained.
- A present scope has at most 500 entries, with unique entry IDs. IDs need not be globally unique across different scopes. Array order is meaningful; no sorting, source-based ID regeneration, deduplication or normalization is allowed.

The existing `BookGlossaryShareCodec`, legacy document `glossary` projection and workflow glossary normalization must not be used to encode this snapshot: they have different identity, text and size semantics.

## Size, preservation and authority

The entire document `extensions` object, including sibling metadata, remains limited to **256 KiB of UTF-8 JSON**. Existing document, archive and nesting limits also apply. The 500-entry and 100-scope structural limits do not guarantee that their maximum-sized contents fit. An exporter must reject an oversized result explicitly; it must not truncate entries, drop scopes or replace values with empty/null data. Supporting this contract does not establish support for exporting every maximum-sized account glossary. A larger transport requires a separately reviewed format change.

Typed encoders project only this schema. Account IDs, usernames, server origins, credentials, account versions, `expectedVersion`, `mutationId`, outbox/retry state, pending edits and conflict records are not fields in the extension. No CAS or mutation state is transferred. Importing a `deleted` snapshot cannot become a server deletion request, and importing any snapshot cannot restore an old synchronization session.

As with `documentIdentity`, generic ZIP reading and re-export preserve safe opaque extension metadata without interpreting it. A malformed or unsupported `bookGlossarySnapshots` value fails explicit typed interpretation; it does not make otherwise valid passive metadata executable or authorize a write. General exchange credential, Unicode, numeric, size and nesting restrictions still apply. Adding or replacing a typed snapshot extension preserves `documentIdentity` and all other allowed sibling extensions.

This codec does not infer a relationship between a supplied glossary scope and the enclosing document. A later export/adoption adapter must explicitly check the original provider/book identity and chosen target language when that relationship is required. Snapshot data alone does not prove origin, ownership or freshness. To offer a latest-account export, that adapter must fetch the actual server view, map version zero/null to `absent` and saved null to `deleted`, and discard responses after account/origin/client changes. It must not relabel a cache, pending edit or conflict candidate as the latest server value.

The shared fixture [book-glossary-snapshots-v1.json](fixtures/book-glossary-snapshots-v1.json) is the extension value itself, without a document wrapper. It includes multiple language scopes, all three presence states, an empty present glossary, opaque Unicode IDs, duplicate source terms, exact whitespace and all entry fields. This contract and its codec are an S4c prerequisite; latest account export, explicit adoption and the remaining S1–S3 ZIP integration require separate implementation and verification.
