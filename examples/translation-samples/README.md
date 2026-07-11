# Translation Samples

These small files are original PageTurner test texts for manually checking
translation providers, source/target language selectors, page segmentation,
offline prefetch, and translation cache reuse.

## Files

- `en-to-ko-short-reader.txt`
  - Suggested settings: source `en`, target `ko`.
  - Good for Google Web HTML, Google Cloud, and LLM provider smoke tests.
- `ko-to-en-short-reader.txt`
  - Suggested settings: source `ko`, target `en`.
  - Good for Korean-to-English verification.
- `mixed-markdown-language-check.md`
  - Suggested settings: source `auto`, target `ko` or `en`.
  - Good for checking Markdown import and mixed-language paragraphs.

## Manual Test Flow

1. Open PageTurner on a device or emulator.
2. Import one sample file through the document picker.
3. Select the provider and API key.
4. Pick source and target language chips, or type a language code manually.
5. Tap `Translate Page`.
6. Tap `Prefetch Offline Cache`.
7. Reopen the saved book and tap `Load Offline Saved` to confirm cache reuse.

Imported books store translation cache files beside the saved copy under
`local_library/books/translate/<book-name>.translations.json`.
