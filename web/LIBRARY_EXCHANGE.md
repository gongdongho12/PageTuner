# App and PC ZIP transfer

Open **On this device → App · Web ZIP transfer**. Select **Export ZIP**, choose saved originals, translations or local files, then export. Server books must first be saved on the device. Android provides the matching **Local → ZIP** screen.

**Import ZIP** validates the whole file, shows its documents, and saves them together only after the Import button is pressed. Imported documents appear under **Imported books**. The format is [library exchange v1](../contracts/library-exchange-v1.md), with 32 MiB compressed / 64 MiB expanded limits and at most 100 documents.

Paragraph IDs, original text, translations, language, outline, compatible reading positions, bookmarks, notes, highlights, organization, glossary fields and passive source metadata are preserved. PDF bytes and EPUB raster images are included. No account credentials or provider API keys are exported. Source/translation metadata excludes repeated paragraphs because the normalized document already contains them.

Identical document and metadata snapshots are skipped on repeated import. A changed incoming snapshot is stored as an independent copy; current books and their reading changes are not overwritten. Import uses one IndexedDB transaction for documents and supported notes/positions. A storage failure aborts the entire import.

The portable format can represent more note/glossary options than the web editor. Fields outside the current editor limits remain intact in the imported record and are included on re-export. Deleted editable notes are not restored from the initial ZIP. Imported PDF assets are displayed using their actual PDF pages; native page-based annotations stay preserved in the ZIP while this web view displays the original pages. They are not converted into invented text anchors.

The Kotlin codec, Android adapter and browser codec consume the same checked-in archive fixture. Browser tests also exercise real Kotlin `ZipOutputStream` output, Unicode offsets, malformed archives, atomic account-scoped imports and import → edit → export metadata preservation.
