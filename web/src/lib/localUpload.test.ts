import { describe, expect, it } from "vitest";
import { localUploadInput } from "./localUpload";
import { localDocumentForTranslation, parseLocalDocument } from "./localDocuments";
import type { ReadingDocument } from "./readingDocument";
const document: ReadingDocument = {
  id: "account-private-document",
  kind: "local",
  bookTitle: "A book",
  chapterTitle: "Document",
  language: "auto",
  paragraphs: [{ paragraphId: "text-page-1", text: "Text" }],
  local: { format: "pdf", contentHash: "a".repeat(64), byteLength: 100 },
  assets: { pdf: new Blob(["private binary"]) },
};
describe("local upload projection", () => {
  it("accepts the actual file-reader projection and excludes PDF raster placeholders", async () => {
    const bytes = new TextEncoder().encode("Original text");
    const text = await parseLocalDocument({ name: "sample.txt", size: bytes.length, arrayBuffer: async () => bytes.buffer });
    expect(localUploadInput(localDocumentForTranslation(text)).paragraphs[0].text).toBe("Original text");
    const pdf = { ...text, local: { ...text.local, format: "pdf" as const, pdfTextPages: [false, true], pdfTextErrorPages: [false, false] },
      paragraphs: [{ paragraphId: "page-0", text: "PDF 1" }, { paragraphId: "page-1", text: "Actual PDF text" }],
      assets: { pdf: new Blob(["private binary"]) } };
    const input = localUploadInput(localDocumentForTranslation(pdf));
    expect(input.paragraphs).toEqual([{ paragraphId: "page-1", ordinal: 0, text: "Actual PDF text" }]);
    expect(JSON.stringify(input)).not.toMatch(/assets|private|byteLength|pdfTextPages|PDF 1/);
  });
  it("preserves stable text anchors and excludes binary assets and device metadata", () => {
    const input = localUploadInput(document);
    expect(input).toEqual({
      bookId: `local:${"a".repeat(64)}`,
      bookTitle: "A book",
      chapterId: "document",
      chapterTitle: "Document",
      sourceLanguage: "auto",
      paragraphs: [{ paragraphId: "text-page-1", ordinal: 0, text: "Text" }],
    });
    expect(JSON.stringify(input)).not.toMatch(/assets|private|byteLength/);
  });
  it("rejects oversized or non-local documents before any upload", () => {
    expect(() =>
      localUploadInput({ ...document, kind: "translation" }),
    ).toThrow();
    expect(() =>
      localUploadInput({
        ...document,
        paragraphs: [{ paragraphId: "long", text: "x".repeat(1_000_001) }],
      }),
    ).toThrow();
    expect(() => localUploadInput({ ...document, paragraphs: [] })).toThrow();
  });
});
