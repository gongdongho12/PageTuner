# OCR Plan For Scanned PDFs

PageTurner should treat OCR as an optional extraction layer after native PDF
text extraction. It must not block basic image viewing.

## Flow

1. Open PDF with `PdfRenderer`.
2. Try native `PdfRenderer.Page.getTextContents()` on Android 15+.
3. If a page has no text, mark it as OCR-eligible.
4. Render OCR-eligible pages through the existing e-ink-safe bitmap pipeline.
5. Send rendered page images to a pluggable OCR provider.
6. Store OCR text per document id, page index, provider id, and source file hash.
7. Rebuild `ReaderPage.segments` from cached OCR text for translation/search.

## Provider Boundary

OCR should use a future `OcrProvider` interface rather than living inside
`PdfDocumentReader`.

```kotlin
interface OcrProvider {
    suspend fun extractText(pageImage: Bitmap, languageHints: List<String>): String
}
```

Planned providers:

- On-device OCR where available.
- Cloud OCR for high accuracy.
- Manual side-load/import of OCR text for privacy-first workflows.

## Product Rules

- Never upload page images unless the user explicitly enables a cloud OCR
  provider.
- Cache OCR output for offline reuse.
- Show OCR status per page and per book.
- Allow clearing OCR cache separately from translation cache.
- Keep scanned PDFs readable as images even when OCR fails.
