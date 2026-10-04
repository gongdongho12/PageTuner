import {
  GlobalWorkerOptions,
  getDocument,
  type PDFDocumentProxy,
} from "pdfjs-dist/legacy/build/pdf.mjs";
import workerUrl from "pdfjs-dist/legacy/build/pdf.worker.min.mjs?url";

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
    data: bytes,
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

export async function parsePdfPages(bytes: Uint8Array, signal?: AbortSignal) {
  const pdf = await openLocalPdf(bytes.slice(), signal);
  const cancel = () => {
    void pdf.destroy();
  };
  signal?.addEventListener("abort", cancel, { once: true });
  try {
    return await extractPdfPages(pdf, signal);
  } finally {
    signal?.removeEventListener("abort", cancel);
    await pdf.destroy();
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
