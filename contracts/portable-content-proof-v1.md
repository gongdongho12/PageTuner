# Portable content proof v1 (S4b2a foundation)

This is a pure content-comparison contract, independent of provenance, account ownership and authorization. It does not extend library-identity-v1, activate any server endpoint, persist bindings or upload/delete reading metadata. Existing text-only identity verification continues rejecting asset documents. Callers compute proof from complete supplied bytes; asserted digests are insufficient.

A proof contains version=1, representation=TEXT|PDF|EPUB, exact language, paragraphHash, nullable originalFileByteLength/originalFileSha256 (both non-null or both null), ordered assets, and sha256. Each asset carries path, role, nullable paragraphId/alt, mimeType, byteLength and sha256. Paths must equal assets/<lowercase SHA-256 of actual bytes>. Null denotes absence and differs from an empty alt string. Proof objects always include these nullable fields; exchange input references may omit them. Array ordering and repeated references are significant. Unique payloads can be referenced repeatedly; payload paths must be unique and every supplied payload must be referenced.

PDF/EPUB require complete nonempty original-file bytes, bounded to 32MiB. PDF has exactly one pdf-role reference matching the complete original-file bytes; other references, when allowed by validation, are bound individually. EPUB has no pdf-role references and requires its original archive bytes in addition to extracted content. Older exchange ZIPs without the original EPUB cannot create this proof. TEXT has no assets; its optional complete original file is separately bound when supplied. TEXT and EPUB require nonempty paragraph arrays; PDF may have no extracted text. The digest does not decode or attest the validity of PDF/EPUB/image files, their extraction algorithm or a physical page count. Those are separate reader/ingestion obligations.

Paragraph IDs/order and exact text use the existing `pageturner.document-paragraphs.v1` framed digest; only PDF additionally permits count=0 without altering identity v1. Up to 50,000 unique nonblank IDs of at most 500 UTF-16 code units and 5,000,000 total UTF-16 text units are allowed. Invalid Unicode is rejected. Language uses the existing ASCII language-tag grammar, at most 35 characters, and is not case-normalized. Up to 512 references and 511 unique payloads; supported payload MIME types are application/pdf and image/png,image/jpeg,image/webp,image/gif, with matching pdf/image roles. Image paragraph references must exist; alternate text is at most 2,000 UTF-16 units. Total original-file bytes plus unique payload bytes are at most 64MiB (the PDF source and its asset are conservatively counted separately). Check bounds before allocating copies. Async implementations snapshot validated mutable input before hashing.

## Exact digest

For each string, frame UTF-8 decimal byte count, ASCII colon, then exact UTF-8 bytes. Concatenate frames and compute lowercase SHA-256. Integers use their canonical base-10 spelling with no sign or leading zeros. No JSON serialization, delimiter joining, sorting or title-based matching is involved.

1. `pageturner.content-proof.v1`, `1`, representation, language, paragraphHash.
2. Original presence marker `0` or `1`; when `1`, original byte length then original SHA-256.
3. Reference count.
4. For every reference in order: path, role, paragraphId presence marker and value if present, alt presence marker and value if present, MIME type, exact byte length, actual payload SHA-256.

Local document/copy IDs, titles, notes, position, organization, glossary, extensions and account credentials are excluded. They neither grant ownership nor establish source provenance. A different representation, language, source file, paragraph, reference order/association/alt/MIME/bytes produces a different proof. The same full content with different titles or local copy IDs produces the same proof.

## Anchors

The anchor namespace is explicit. A TEXT anchor identifies paragraphId and UTF-16 characterOffset. Validate the supplied ordered paragraphs against proof.paragraphHash before validating the position. Empty paragraph offset 0 and paragraph-end positions are valid; negative/out-of-range/fractional offsets and surrogate-pair splits are invalid. Explicit extracted-text anchors may be used on a text-bearing PDF, but are not physical PDF positions.

A PDF anchor identifies originalFileSha256 and zero-based pageIndex. A caller provides an independently decoded PDF context containing the same originalFileSha256 and pageCount (integer 1..2,147,483,647). Require proof representation PDF, proof/context/anchor file hashes identical, and pageIndex within count. Never derive pageCount from claimed wire metadata or convert between the two anchor namespaces. EPUB uses exact text anchors; reflowed screen page numbers are not portable.

Shared vectors in fixtures/portable-content-proof-v1 cover Unicode/delimiters/empty text, image-only PDF, and repeated EPUB image references with absent versus empty alt. They test byte/hash comparison, not PDF/EPUB decoding, physical device UI or server authorization. Runtime-specific tests cover tampering and anchor boundaries.
