# Passive document organization snapshot v1

This optional [library exchange v1](library-exchange-v1.md) extension preserves
one observed account classification for the exact enclosing text document. It
is passive data: importing or reading it does not edit the legacy local
classification, bind an account, start synchronization or issue a write.

## Shape and presence

The key is `document.extensions.libraryOrganizationSnapshot`:

```json
{
  "version": 1,
  "identity": { "...": "complete DocumentIdentity v1 object" },
  "presence": "present",
  "organization": {
    "folder": "Fiction / Part  A",
    "tags": ["Reading", "reading", "원문🌏"],
    "favorite": true
  }
}
```

The identity placeholder above is explanatory only. Actual data must contain
all and only the fields of an ORIGINAL or TRANSLATION DocumentIdentity v1,
including original provider/book/chapter IDs, source language/revision and
ordered paragraph hash, and every translation field when applicable. See
[portable document identity](../docs/PORTABLE_DOCUMENT_IDENTITY.md) and the
[shared fixture](fixtures/library-organization-snapshot-v1.json) for complete
examples. Document UUIDs, server record IDs, Android local IDs, titles and PDF
page indices cannot replace this identity.

All four top-level fields are required. Only the following states exist:

| Presence | Required organization | Meaning |
| --- | --- | --- |
| `absent` | `null` | No account classification existed when observed. |
| `present` | An object with exactly `folder`, `tags`, `favorite` | A saved classification was observed. |

`present` with `{"folder":"","tags":[],"favorite":false}` is an explicitly
saved empty classification, not `absent`. An omitted extension means no
snapshot was supplied. The account organization API has no DELETE operation;
`deleted` is invalid and must not be borrowed from glossary snapshots. Neither
absence nor an empty value authorizes clearing local or server data.

`version` is numeric one under the exchange format's existing binary64 rules
(`1`, `1.0` and `1e0` are equivalent); a string `"1"` is invalid. Unknown or
missing keys, duplicate JSON object keys including escaped duplicates, type
coercion, malformed Unicode, unsupported versions and unknown presence values
are rejected by typed interpretation. JSON property order is insignificant.

## Lossless account values

Use the exact account [library organization API](library-organization-v1.openapi.json)
semantics, without passing through local organization normalization:

- Lengths are UTF-16 code units. Folder is 0–200; empty means unfiled.
- Tags are an ordered array of 0–32 strings; each string is 1–60 units.
  Exact duplicate strings are invalid. Case variants, composed/decomposed
  Unicode and internal whitespace remain distinct and retain their order.
- Folder and tags reject leading/trailing ECMAScript trim whitespace, ISO
  controls (U+0000–001F and U+007F–009F), and unpaired surrogates. They are never
  trimmed, case-folded, normalized, sorted, deduplicated or truncated.
- `favorite` is a required Boolean, with no default/coercion.

The existing `document.organization` allows different lengths and remains
separate legacy/local data. Attaching this extension must preserve it exactly,
even when its values cannot be represented by the account snapshot contract.

## Structural decoding versus document proof

Pure snapshot validation and raw JSON decoding validate only the supplied
structure and values. They do not establish that the claimed identity belongs
to any document or account.

Explicit typed document read/attachment must additionally recompute and verify
DocumentIdentity v1 against the enclosing canonical text: exact kind, displayed
language, ordered paragraph IDs/body/hash, source revision, and all translation
artifact/revision/payload fields. Assets are unsupported by this text identity
contract and must fail typed document operations. Never substitute a PDF/EPUB
content proof or screen page index to make a text snapshot pass.

If `extensions.documentIdentity` is supplied, it must itself be supported,
valid for the document, and exactly equal to the embedded snapshot identity.
If it is absent, the embedded identity is sufficient for content verification;
the codec does not create a sibling identity or binding automatically. This
verifies content consistency only, not account ownership, origin or freshness.

A typed attachment must validate any existing `libraryOrganizationSnapshot`
including its document proof before replacing it. Unsupported, malformed or
inconsistent existing snapshots are refused and preserved, never silently
overwritten. Generic ZIP reading and re-export continue preserving safe opaque
extensions without invoking typed interpretation, including future snapshot
versions. Missing snapshots return no value; they do not trigger a legacy value
fallback or become an absent account observation.

## Limits and authority

The complete `extensions` object including all siblings remains bounded by
**256 KiB of UTF-8 JSON**. Existing document, archive, metadata depth, Unicode,
number and credential restrictions still apply. Reject excessive input/output
explicitly; do not discard siblings or tags to fit. Raw string entry points
check Unicode before UTF-8 conversion, so lone surrogates cannot be replaced
silently. Typed writers preserve all allowed siblings, including glossary
snapshots, and return independent copies without mutating their input.

Only the four snapshot fields and the nested identity/classification fields
belong here. No account ID, origin, credentials, CAS version, `expectedVersion`,
mutation ID, queued edit, retry state, conflict or outbox is transferable.

Latest-account export and explicit adoption require later adapters. Latest
export must directly GET the current account value, check exact identity and
binding, and discard responses after account/origin/client/generation changes.
Cached local values, pending edits and conflicts are not latest server values.
Adoption must be a separate explicit choice after revalidation and conflict
handling. This codec provides neither operation and makes no server request.

The shared fixture is `{ "cases": [{ "name", "document", "snapshot" }] }`.
It covers original/translation provenance, missing sibling identity, language
case, absent/present-empty, ordered Unicode tags, legacy organization and
opaque sibling preservation. JVM runtime and web tests consume the same JSON
fixture; pure Kotlin tests exercise the same model rules without a JSON
dependency. Cross-runtime ZIP tests verify passive round trips.
