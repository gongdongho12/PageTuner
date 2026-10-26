# Passive reading progress snapshot v1

The optional `document.extensions.readingProgressSnapshot` preserves one
observed account reading position for the exact enclosing text document.
Reading or importing it does not move the legacy local position, connect an
account, start synchronization or authorize an account write.

## Shape and presence

Exactly four fields are required: `version`, `identity`, `presence`, `anchor`.
`version` is numeric one under the exchange binary64 rules (`1.0` and `1e0`
are equivalent). `identity` is the complete ORIGINAL or TRANSLATION
DocumentIdentity v1, including every translation field when applicable.
Titles, local IDs, server UUIDs and PDF page indices cannot replace it.

- Missing extension: no observed account value was supplied.
- `presence: "absent", anchor: null`: no account position existed when observed.
- `presence: "present", anchor: {"paragraphId":"p:1","characterOffset":0}`:
  an exact saved account anchor was observed.

There is no `deleted`, reset or present-null state. The account progress API
has no DELETE/reset operation. Absence never authorizes clearing a position.
No server version, timestamp, account, origin, binding, credential, mutation,
retry, conflict or outbox state is transferable.

## Exact anchor and proof

`anchor` has exactly `paragraphId` and `characterOffset`. The paragraph ID is
1–200 UTF-16 code units, nonblank under ECMAScript trim whitespace, without
ISO controls (U+0000–001F or U+007F–009F) or unpaired surrogates. Preserve its
case, Unicode normalization and all leading, trailing and internal whitespace;
the nonblank check does not trim the stored ID. The offset is an integer in
0–2147483647, measured in UTF-16 code units, without string coercion.

Structural decoding validates only structure and values. Typed document read
and attachment additionally recompute full DocumentIdentity proof over exact
kind, displayed language, ordered paragraph IDs/text, original revision and
all translation hashes/provenance. The anchor must identify an actual paragraph
and be between zero and that paragraph's exact text length, inclusive. Reject
offsets inside a surrogate pair. Paragraph-end positions and offset zero in
an empty paragraph are valid; never substitute a page number, nearest nonempty
paragraph, source-language offset or trimmed/truncated paragraph ID.

Asset-backed PDF/EPUB documents cannot use this text identity contract. If a
`documentIdentity` sibling exists, it must be supported, valid and exactly
equal to the embedded identity. An omitted sibling is not created implicitly.
Content proof establishes consistency, not account ownership or freshness.

## Preservation and rejection

A typed replacement validates any existing own snapshot and its full document
proof before replacement. Unsupported versions, malformed/duplicate/unknown
keys, wrong primitives, unsafe numbers and inconsistent content are rejected
without changing the original. Raw JSON entry points reject duplicate keys
and malformed Unicode before conversion. Generic ZIP codecs continue preserving
safe opaque future extensions without invoking typed interpretation.

All legacy position, notes, outline, organization and glossary fields and all
allowed sibling extensions (including organization/glossary snapshots) remain
unchanged. Writers return detached values and do not mutate input lists or
objects. Full extensions remain bounded by 256 KiB UTF-8 JSON, the document
by 8 MiB, and existing archive, metadata, Unicode, number and credential rules
still apply. Reject excessive input/output; do not truncate or discard siblings.
Legacy ZIP paragraph IDs beyond the account API bound remain generic data;
typed account snapshots reject unsupported mappings.

The [shared fixture](fixtures/reading-progress-snapshot-v1.json) contains
`{cases:[{name,document,snapshot}]}` for original/translation, absent, zero/end
offset, empty text, exact whitespace/Unicode IDs, language case, missing sibling
identity and preservation of other metadata.

## Later account integration

This passive contract performs no GET or PUT. Latest export must use a direct
GET without opening a synchronization controller, revalidate canonical content,
raw text binding nonce and terminal account/origin/client/selection authority,
and reject stale responses or device pending/conflict substitutions. Explicit
adoption is a separate action requiring nullable journal CAS, fresh remote/base
checks and a new mutation intent. Neither adapter nor UI is supplied here.

See [reading progress API](reading-progress-v1.md),
[portable identity](../docs/PORTABLE_DOCUMENT_IDENTITY.md) and
[library exchange](library-exchange-v1.md). S2 notes/ranges/deletion tombstones
require their own contract and cannot be inferred from this S1 snapshot.
