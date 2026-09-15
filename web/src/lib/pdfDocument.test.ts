import { DOMMatrix, ImageData, Path2D, createCanvas } from "@napi-rs/canvas";
import { beforeAll, describe, expect, it, vi } from "vitest";
import { webcrypto } from "node:crypto";
import { IDBFactory } from "fake-indexeddb";

function samplePdf(): Uint8Array {
  const text = "BT /F1 18 Tf 20 120 Td (Readable original PDF) Tj ET";
  const rectangle = "0 g 20 20 80 80 re f";
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 160] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 160] /Resources << >> /Contents 7 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    `<< /Length ${text.length} >>\nstream\n${text}\nendstream`,
    `<< /Length ${rectangle.length} >>\nstream\n${rectangle}\nendstream`,
  ];
  let pdf = "%PDF-1.4\n";
  const offsets = [0];
  for (const [index, value] of objects.entries()) {
    offsets.push(pdf.length);
    pdf += `${index + 1} 0 obj\n${value}\nendobj\n`;
  }
  const xref = pdf.length;
  pdf +=
    `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n` +
    offsets
      .slice(1)
      .map((offset) => `${String(offset).padStart(10, "0")} 00000 n \n`)
      .join("");
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return new TextEncoder().encode(pdf);
}

beforeAll(async () => {
  vi.stubGlobal("DOMMatrix", DOMMatrix);
  vi.stubGlobal("ImageData", ImageData);
  vi.stubGlobal("Path2D", Path2D);
  vi.stubGlobal("crypto", webcrypto);
  // Tests use the actual PDF.js worker on disk; browser builds use Vite's same-origin hashed URL.
  await import("./pdfDocument");
  const { GlobalWorkerOptions } = await import(
    "pdfjs-dist/legacy/build/pdf.mjs"
  );
  GlobalWorkerOptions.workerSrc = new URL(
    "../../node_modules/pdfjs-dist/legacy/build/pdf.worker.min.mjs",
    import.meta.url,
  ).href;
});

describe("actual PDF.js document parsing and raster rendering", () => {
  it("extracts text while retaining a readable non-text original page", async () => {
    const { parsePdfPages, openLocalPdf } = await import("./pdfDocument");
    const pages = await parsePdfPages(samplePdf());
    expect(pages.hasText).toEqual([true, false]);
    expect(pages.textErrors).toEqual([false, false]);
    expect(pages.texts[0]).toContain("Readable original PDF");
    const pdf = await openLocalPdf(samplePdf());
    try {
      const page = await pdf.getPage(2),
        viewport = page.getViewport({ scale: 1 }),
        canvas = createCanvas(200, 160);
      await page.render({
        canvas: canvas as unknown as HTMLCanvasElement,
        canvasContext: canvas.getContext(
          "2d",
        ) as unknown as CanvasRenderingContext2D,
        viewport,
      }).promise;
      const black = canvas.getContext("2d").getImageData(40, 90, 1, 1).data;
      expect([...black]).toEqual([0, 0, 0, 255]);
    } finally {
      await pdf.destroy();
    }
  });
  it("preserves extraction failure separately from image pages through saved storage and blocks partial translation", async () => {
    const { extractPdfPages, openLocalPdf } = await import("./pdfDocument");
    const {
      parseLocalDocument,
      createLocalDocuments,
      localDocumentForTranslation,
    } = await import("./localDocuments");
    const { localUploadInput } = await import("./localUpload");
    const bytes = samplePdf();
    const doc = await parseLocalDocument({
      name: "original.pdf",
      size: bytes.length,
      arrayBuffer: async () => bytes.slice().buffer as ArrayBuffer,
    });
    const pdf = await openLocalPdf(bytes.slice());
    try {
      const textPage = await pdf.getPage(1);
      vi.spyOn(textPage, "getTextContent").mockRejectedValueOnce(
        new Error("Forced text extraction failure"),
      );
      const pages = await extractPdfPages(pdf);
      expect(pages.hasText).toEqual([false, false]);
      expect(pages.textErrors).toEqual([true, false]);
      doc.paragraphs = doc.paragraphs.map((paragraph, index) => ({
        ...paragraph,
        text: pages.texts[index],
      }));
      doc.local.pdfTextPages = pages.hasText;
      doc.local.pdfTextErrorPages = pages.textErrors;
      const library = createLocalDocuments("reader", {
        indexedDB: new IDBFactory(),
      });
      await library.save(doc);
      const stored = (await library.list()).books[0].document;
      expect(stored.local.pdfTextErrorPages).toEqual([true, false]);
      expect(stored.assets?.pdf?.size).toBe(bytes.length);
      expect(() => localDocumentForTranslation(stored)).toThrow(
        "전체를 번역할 수 없습니다",
      );
      expect(() => localUploadInput(stored)).toThrow(
        "전체를 번역할 수 없습니다",
      );
      // A failure to extract text does not prevent the same original from rendering.
      const canvas = createCanvas(200, 160),
        viewport = textPage.getViewport({ scale: 1 });
      await textPage.render({
        canvas: canvas as unknown as HTMLCanvasElement,
        canvasContext: canvas.getContext(
          "2d",
        ) as unknown as CanvasRenderingContext2D,
        viewport,
      }).promise;
      delete stored.local.pdfTextErrorPages;
      expect(() => localDocumentForTranslation(stored)).toThrow("다시 가져와");
    } finally {
      await pdf.destroy();
      vi.restoreAllMocks();
    }
  });
  it("rejects invalid and cancelled PDF inputs with a clear result", async () => {
    const { openLocalPdf } = await import("./pdfDocument");
    await expect(
      openLocalPdf(new TextEncoder().encode("not a PDF")),
    ).rejects.toThrow("PDF를 열지 못했습니다");
    const controller = new AbortController();
    controller.abort();
    await expect(
      openLocalPdf(samplePdf(), controller.signal),
    ).rejects.toMatchObject({ name: "AbortError" });
  });
});
