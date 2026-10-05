import { DOMMatrix, ImageData, Path2D, createCanvas } from "@napi-rs/canvas";
import { beforeAll, describe, expect, it, vi } from "vitest";
import { webcrypto, createHash } from "node:crypto";
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
  it('binds real two-page decoding and physical anchors to immutable exact bytes', async () => {
    const { openVerifiedPdf } = await import('./pdfDocument')
    const { createPortableContentProof, validPortablePdfAnchor } = await import('./portableContentProof')
    const bytes = samplePdf(), original = bytes.slice(), hash = createHash('sha256').update(bytes).digest('hex')
    const pending = openVerifiedPdf(bytes); bytes.fill(0); const opened = await pending
    try {
      expect(opened.context).toEqual({ originalFileSha256: hash, pageCount: 2 }); expect(opened.pdf.numPages).toBe(2)
      const proof = await createPortableContentProof({ representation: 'PDF', language: 'ko', paragraphs: [], originalFile: original,
        assets: [{ path: `assets/${hash}`, mimeType: 'application/pdf', bytes: original }], assetReferences: [{ path: `assets/${hash}`, role: 'pdf' }] })
      expect(await validPortablePdfAnchor(proof, opened.context, opened.anchor(1))).toBe(true)
      expect(() => opened.anchor(2)).toThrow(); expect(() => opened.anchor(-1)).toThrow()
    } finally { await opened.close() }
    expect(() => opened.anchor(0)).toThrow('closed')
  })
  it('uses decoder count with empty or mismatched ZIP canonical paragraphs without synthetic IDs or mutation', async () => {
    const { parseLocalDocument } = await import('./localDocuments'), { openPdfReadingDocument } = await import('./pdfReadingDocument')
    const bytes = samplePdf(), native = await parseLocalDocument({ name: 'original.pdf', size: bytes.length, arrayBuffer: async () => bytes.slice().buffer })
    for (const count of [0, 1, 5]) {
      const doc = { ...native, id: 'exchange:canonical-copy', paragraphs: Array.from({ length: count }, (_, i) => ({ paragraphId: `original-id-${i}`, text: i ? '' : 'Canonical text is independent' })) }
      const before = JSON.stringify(doc), opened = await openPdfReadingDocument(doc)
      try {
        expect(opened.decoder.context.pageCount).toBe(2); expect(opened.canonical.paragraphs).toEqual(doc.paragraphs)
        expect(opened.nativePageAnchors).toBeUndefined(); expect(opened.decodedDisplay).toBeUndefined()
        expect(opened.decoder.anchor(1).type).toBe('PDF'); expect(JSON.stringify(doc)).toBe(before)
        expect(opened.canonical.paragraphs.some(p => p.paragraphId.startsWith('pdf-view:'))).toBe(false)
      } finally { await opened.decoder.close() }
    }
  })
  it('matches native display IDs/text/extraction state against the same actual PDF and leaves altered text unmapped', async () => {
    const { parseLocalDocument } = await import('./localDocuments'), { openPdfReadingDocument } = await import('./pdfReadingDocument')
    const bytes = samplePdf(), doc = await parseLocalDocument({ name: 'original.pdf', size: bytes.length, arrayBuffer: async () => bytes.slice().buffer })
    const opened = await openPdfReadingDocument(doc)
    try {
      expect(opened.nativePageAnchors?.map(a => a.paragraphId)).toEqual(doc.paragraphs.map(p => p.paragraphId))
      expect(opened.canonical.paragraphHash).toBe(opened.decodedDisplay?.paragraphHash)
      expect(opened.decodedDisplay?.hasText).toEqual([true, false]); expect(opened.decodedDisplay?.textErrors).toEqual([false, false])
      doc.paragraphs[0].text = 'changed after decode'; doc.local.pdfTextPages![0] = false
      expect(opened.matchesInput()).toBe(false); expect(opened.documentSnapshot.paragraphs[0].text).toContain('Readable original PDF')
      expect(opened.documentSnapshot.local?.pdfTextPages).toEqual([true, false])
    } finally { await opened.decoder.close() }
    const changed = await openPdfReadingDocument(doc)
    try { expect(changed.nativePageAnchors).toBeUndefined(); expect(changed.canonical.paragraphHash).not.toBe(changed.decodedDisplay?.paragraphHash); expect(changed.decoder.context.pageCount).toBe(2) }
    finally { await changed.decoder.close() }
  })
  it('keeps HTTP phone sharing readable without WebCrypto or fabricated local metadata', async () => {
    const { openPdfReadingDocument } = await import('./pdfReadingDocument')
    const bytes = samplePdf(), expected = createHash('sha256').update(bytes).digest('hex')
    vi.stubGlobal('crypto', undefined)
    try {
      const doc = { id: 'phone-copy', kind: 'original' as const, bookTitle: 'Phone', chapterTitle: 'Phone', language: 'ko', paragraphs: [{ paragraphId: 'x'.repeat(4096), text: '' }], assets: { pdf: new Blob([Uint8Array.from(bytes).buffer], { type: 'application/pdf' }) } }
      const opened = await openPdfReadingDocument(doc)
      try { expect(opened.decoder.context).toEqual({ originalFileSha256: expected, pageCount: 2 }); expect(opened.nativePageAnchors).toBeUndefined(); expect(opened.documentSnapshot.local).toBeUndefined() }
      finally { await opened.decoder.close() }
    } finally { vi.stubGlobal('crypto', webcrypto) }
  })
  it('restores phone visit navigation only from explicit physical projections matching the live bytes and count', async () => {
    const { openPdfReadingDocument } = await import('./pdfReadingDocument')
    const { sharedReadingDocument } = await import('../sharing/libraryGateway')
    const bytes = samplePdf(), hash = createHash('sha256').update(bytes).digest('hex'), id = `local-sha256:${hash}`
    const base = { id: 'phone-copy', kind: 'original' as const, bookTitle: 'Phone', chapterTitle: 'Phone', language: 'ko', assets: { pdf: new Blob([Uint8Array.from(bytes).buffer], { type: 'application/pdf' }) } }
    const blank = { ...base, paragraphs: [0, 1].map(index => ({ paragraphId: `pdf:${hash}:page:${index + 1}`, text: '' })) }
    const portable = sharedReadingDocument({ id: 'opaque-phone-inventory-id', title: 'Phone', format: 'pdf', edition: 'original', language: 'ko', revision: 'opaque-revision',
      paragraphs: [0, 1].map(index => ({ paragraphId: `${id}:p${index}`, text: index ? '' : 'Original canonical text is retained' })), outline: [],
      assets: [{ id: 'opaque-pdf-asset', role: 'pdf', mimeType: 'application/pdf', byteLength: bytes.length, alt: '' }] }, new Map([['opaque-pdf-asset', base.assets.pdf]])).document
    for (const doc of [blank, portable]) {
      const original = JSON.stringify(doc), opened = await openPdfReadingDocument(doc, undefined, true)
      try {
        expect(opened.visitPageAnchors).toEqual(doc.paragraphs.map(p => ({ paragraphId: p.paragraphId, characterOffset: 0 })))
        expect(opened.visitPageAnchors?.findIndex(a => a.paragraphId === doc.paragraphs[1].paragraphId)).toBe(1)
        expect(opened.nativePageAnchors).toBeUndefined(); expect(opened.decodedDisplay).toBeUndefined()
        expect(JSON.stringify(doc)).toBe(original); expect(opened.documentSnapshot.local).toBeUndefined()
      } finally { await opened.decoder.close() }
      const ordinary = await openPdfReadingDocument(doc)
      try { expect(ordinary.visitPageAnchors).toBeUndefined() } finally { await ordinary.decoder.close() }
    }
    for (const doc of [
      { ...blank, paragraphs: blank.paragraphs.slice(0, 1) },
      { ...blank, paragraphs: blank.paragraphs.map(p => ({ ...p, paragraphId: p.paragraphId.replace(hash, '0'.repeat(64)) })) },
      { ...blank, paragraphs: blank.paragraphs.map(p => ({ ...p, text: 'Not a blank projection' })) },
      { ...portable, paragraphs: portable.paragraphs.map(p => ({ ...p, paragraphId: p.paragraphId.replace(hash, '0'.repeat(64)) })) },
      { ...portable, paragraphs: portable.paragraphs.slice().reverse() },
    ]) {
      const opened = await openPdfReadingDocument(doc, undefined, true)
      try { expect(opened.visitPageAnchors).toBeUndefined(); expect(opened.decoder.context.pageCount).toBe(2) }
      finally { await opened.decoder.close() }
    }
  })
  it('rejects actual byte/hash mismatches and late source mutations, and destroys the live handle on abort', async () => {
    const { parseLocalDocument } = await import('./localDocuments'), { openPdfReadingDocument } = await import('./pdfReadingDocument'), { openVerifiedPdf } = await import('./pdfDocument')
    const bytes = samplePdf(), doc = await parseLocalDocument({ name: 'original.pdf', size: bytes.length, arrayBuffer: async () => bytes.slice().buffer })
    await expect(openPdfReadingDocument({ ...doc, local: { ...doc.local, contentHash: '0'.repeat(64) } })).rejects.toThrow('식별자')
    await expect(openPdfReadingDocument({ ...doc, local: { ...doc.local, byteLength: bytes.length + 1 } })).rejects.toThrow('원본')
    const pending = openPdfReadingDocument(doc); doc.paragraphs[0].text = 'changed while loading'
    await expect(pending).rejects.toThrow('대응')
    const controller = new AbortController(), opened = await openVerifiedPdf(bytes, controller.signal), destroy = vi.spyOn(opened.pdf, 'destroy')
    controller.abort(); await opened.close(); expect(destroy).toHaveBeenCalledTimes(1); expect(() => opened.anchor(0)).toThrow()
    const loadingController = new AbortController(), loading = openVerifiedPdf(bytes, loadingController.signal); loadingController.abort()
    await expect(loading).rejects.toMatchObject({ name: 'AbortError' })
  })
  it('rejects a real zero-page PDF instead of clamping its decoder count', async () => {
    const { openVerifiedPdf } = await import('./pdfDocument')
    const empty = new TextEncoder().encode('%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Count 0 /Kids [] >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF')
    await expect(openVerifiedPdf(empty)).rejects.toThrow('PDF를 열지 못했습니다')
  })
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
