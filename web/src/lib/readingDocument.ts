import type { ReadingAnchor } from './offline'

/** A reader document is independent of server source/translation persistence. */
export type ReadingDocument = {
  id: string
  bookTitle: string
  chapterTitle: string
  language: string
  kind: 'original' | 'translation' | 'introduction' | 'local'
  /** Only server records have cross-device progress; display-only derivatives omit this identity. */
  serverProgress?: { kind: 'ORIGINAL' | 'TRANSLATION'; recordId: string }
  glossaryIdentity?: { providerId: string; bookId: string }
  paragraphs: { paragraphId: string; text: string }[]
  outline?: { title: string; paragraphId: string }[]
  local?: { format: 'txt' | 'markdown' | 'epub' | 'pdf'; byteLength: number; contentHash: string; encoding?: string; pdfTextPages?: boolean[]; pdfTextErrorPages?: boolean[] }
  assets?: { pdf?: Blob; images?: { paragraphId: string; alt: string; blob: Blob }[] }
}

export function validAnchor(document: ReadingDocument, anchor: ReadingAnchor): boolean {
  const paragraph = document.paragraphs.find(item => item.paragraphId === anchor.paragraphId)
  return !!paragraph && Number.isSafeInteger(anchor.characterOffset) && anchor.characterOffset >= 0 &&
    anchor.characterOffset < paragraph.text.length
}

export function firstAnchor(document: ReadingDocument): ReadingAnchor {
  const paragraph = document.paragraphs[0]
  if (!paragraph) throw new Error('읽을 수 있는 본문이 없습니다.')
  return { paragraphId: paragraph.paragraphId, characterOffset: 0 }
}
