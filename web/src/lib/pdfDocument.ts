import {
  GlobalWorkerOptions,
  getDocument,
  type PDFDocumentProxy,
} from "pdfjs-dist/legacy/build/pdf.mjs";
import workerUrl from "pdfjs-dist/legacy/build/pdf.worker.min.mjs?url";
import { PdfDecoderInput } from './pdfDecoderInput';
import type { PortablePdfAnchor, VerifiedPdfContext } from './portableContentProof';

GlobalWorkerOptions.workerSrc = workerUrl;

// Vite emits these dependencies under public /assets with the worker. Names can
// only resolve to this build's packaged files, never URLs supplied by a PDF.
const cMaps = import.meta.glob<string>(
  "../../node_modules/pdfjs-dist/cmaps/*.bcmap",
  { query: "?url", import: "default", eager: true },
);
const fonts = import.meta.glob<string>(
  "../../node_modules/pdfjs-dist/standard_fonts/*.{pfb,ttf}",
  { query: "?url", import: "default", eager: true },
);
const wasm = import.meta.glob<string>(
  "../../node_modules/pdfjs-dist/wasm/*.wasm",
  { query: "?url", import: "default", eager: true },
);
async function packagedAsset(
  files: Record<string, string>,
  filename: string,
): Promise<Uint8Array> {
  const entry = Object.entries(files).find(
    ([path]) => path.split("/").pop() === filename,
  );
  if (!entry || /[/\\]/.test(filename))
    throw new Error("PDF의 내장 글꼴 또는 이미지 자료를 읽을 수 없습니다.");
  const response = await fetch(entry[1], { credentials: "omit" });
  if (!response.ok)
    throw new Error("PDF의 내장 글꼴 또는 이미지 자료를 읽을 수 없습니다.");
  return new Uint8Array(await response.arrayBuffer());
}
class PackagedCMaps {
  async fetch({ name }: { name: string }) {
    return {
      cMapData: await packagedAsset(cMaps, `${name}.bcmap`),
      compressionType: 1,
    };
  }
}
class PackagedFonts {
  fetch({ filename }: { filename: string }) {
    return packagedAsset(fonts, filename);
  }
}
class PackagedWasm {
  fetch({ filename }: { filename: string }) {
    return packagedAsset(wasm, filename);
  }
}

export async function openLocalPdf(
  bytes: Uint8Array,
  signal?: AbortSignal,
): Promise<PDFDocumentProxy> {
  signal?.throwIfAborted();
  const task = getDocument({
    // PDF.js may transfer this buffer to its worker. Never detach caller-owned memory.
    data: bytes.slice(),
    isEvalSupported: false,
    stopAtErrors: true,
    useWorkerFetch: false,
    disableFontFace: true,
    useSystemFonts: true,
    enableXfa: false,
    CMapReaderFactory: PackagedCMaps,
    StandardFontDataFactory: PackagedFonts,
    WasmFactory: PackagedWasm,
  });
  const cancel = () => {
    void task.destroy();
  };
  signal?.addEventListener("abort", cancel, { once: true });
  try {
    const pdf = await task.promise;
    signal?.throwIfAborted();
    if (!Number.isSafeInteger(pdf.numPages) || pdf.numPages <= 0) throw new Error('Invalid decoder page count');
    if (pdf.numPages > 2000) {
      await pdf.destroy();
      throw new Error("PDF가 너무 깁니다. 2,000페이지 이하로 나누어 주세요.");
    }
    return pdf;
  } catch (error) {
    await task.destroy();
    signal?.throwIfAborted();
    if (
      error instanceof Error &&
      error.message.startsWith("PDF가 너무 깁니다.")
    )
      throw error;
    if (error instanceof Error && error.name === "PasswordException")
      throw new Error(
        "암호로 보호된 PDF입니다. 암호를 해제한 파일을 가져와 주세요.",
      );
    throw new Error(
      "PDF를 열지 못했습니다. 손상되었거나 지원하지 않는 파일인지 확인해 주세요.",
    );
  } finally {
    signal?.removeEventListener("abort", cancel);
  }
}

export type VerifiedPdfDocument = {
  readonly pdf: PDFDocumentProxy; readonly context: VerifiedPdfContext; readonly byteLength: number
  assertOpen(): void; anchor(pageIndex: number): PortablePdfAnchor; close(): Promise<void>
}
/** A live handle, actual raw count and digest derived from exactly the bytes supplied to PDF.js. */
export async function openVerifiedPdf(bytes: Uint8Array | PdfDecoderInput, signal?: AbortSignal): Promise<VerifiedPdfDocument> {
  signal?.throwIfAborted()
  const source = bytes instanceof PdfDecoderInput ? bytes : PdfDecoderInput.capture(bytes)
  let pdf: PDFDocumentProxy | undefined, closed = false, closing: Promise<void> | undefined
  const close = () => { closed = true; signal?.removeEventListener('abort', cancel); return closing ??= pdf ? pdf.destroy() : Promise.resolve() }
  const cancel = () => { void close().catch(() => undefined) }
  const assertOpen = () => { signal?.throwIfAborted(); if (closed) throw new DOMException('PDF decoder closed', 'AbortError') }
  try {
    pdf = await openLocalPdf(source.copyBytes(), signal)
    signal?.addEventListener('abort', cancel, { once: true }); assertOpen()
    const context = await source.verifiedContext(pdf.numPages); assertOpen()
    return Object.freeze({ pdf, context, byteLength: source.byteLength, assertOpen,
      anchor(pageIndex: number) { assertOpen(); if (!Number.isSafeInteger(pageIndex) || pageIndex < 0 || pageIndex >= context.pageCount) throw new Error('Invalid PDF page index'); return Object.freeze({ type: 'PDF' as const, originalFileSha256: context.originalFileSha256, pageIndex }) }, close })
  } catch (error) { await close().catch(() => undefined); throw error }
}

export async function parsePdfPages(bytes: Uint8Array, signal?: AbortSignal) {
  const opened = await openVerifiedPdf(bytes, signal);
  try {
    const pages = await extractPdfPages(opened.pdf, signal); opened.assertOpen()
    return { ...pages, verifiedContext: opened.context };
  } finally {
    await opened.close();
  }
}

/** A successful empty extraction is an image page; an exception is incomplete text. */
export async function extractPdfPages(
  pdf: PDFDocumentProxy,
  signal?: AbortSignal,
): Promise<{ texts: string[]; hasText: boolean[]; textErrors: boolean[] }> {
  const texts: string[] = [],
    hasText: boolean[] = [],
    textErrors: boolean[] = [];
  let total = 0;
  for (let number = 1; number <= pdf.numPages; number++) {
    signal?.throwIfAborted();
    const page = await pdf.getPage(number);
    let text = "",
      extractionFailed = false;
    try {
      try {
        const content = await page.getTextContent();
        signal?.throwIfAborted();
        text = content.items
          .map((item) =>
            "str" in item ? item.str + (item.hasEOL ? "\n" : " ") : "",
          )
          .join("")
          .trim();
      } catch {
        signal?.throwIfAborted();
        extractionFailed = true;
      }
      total += text.length;
      if (total > 5_000_000)
        throw new Error(
          "PDF의 텍스트가 너무 큽니다. 더 작은 파일로 나누어 주세요.",
        );
      hasText.push(!!text);
      textErrors.push(extractionFailed);
      texts.push(text || `[PDF ${number}]`);
    } finally {
      page.cleanup();
    }
  }
  return { texts, hasText, textErrors };
}
