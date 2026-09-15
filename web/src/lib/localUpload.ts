import type { ReadingDocument } from "./readingDocument";
import type { UploadChapter } from "./workflowTypes";

/** Explicit projection excludes original file assets, account scope and device notes. */
export function localUploadInput(document: ReadingDocument): UploadChapter {
  if (document.local?.format === 'pdf' && document.local.pdfTextErrorPages?.some(Boolean))
    throw new Error('텍스트를 추출하지 못한 PDF 페이지가 있어 파일 전체를 번역할 수 없습니다. 원본은 계속 읽을 수 있습니다.');
  if (
    document.kind !== "local" ||
    !document.local ||
    !document.paragraphs.length ||
    document.paragraphs.length > 10_000 ||
    document.paragraphs.reduce((n, p) => n + p.text.length, 0) > 1_000_000
  )
    throw new Error("번역할 문서는 10,000문단·100만자 이하로 나누어 주세요.");
  if (!/^[a-f0-9]{64}$/.test(document.local.contentHash))
    throw new Error("문서의 내용을 확인할 수 없습니다. 다시 가져와 주세요.");
  return {
    bookId: `local:${document.local.contentHash}`,
    bookTitle: document.bookTitle,
    chapterId: "document",
    chapterTitle: document.chapterTitle,
    sourceLanguage: document.language || "auto",
    paragraphs: document.paragraphs.map((paragraph, ordinal) => ({
      paragraphId: paragraph.paragraphId,
      ordinal,
      text: paragraph.text,
    })),
  };
}
