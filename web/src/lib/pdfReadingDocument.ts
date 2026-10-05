import { extractPdfPages, openVerifiedPdf, type VerifiedPdfDocument } from './pdfDocument'
import { localSha256 } from './localSha256'
import type { ReadingDocument } from './readingDocument'
import type { ReadingAnchor } from './offline'

type Display = { paragraphs: ReadingDocument['paragraphs']; paragraphHash: string; hasText: boolean[]; textErrors: boolean[] }
export type PdfReadingDocument = {
  decoder: VerifiedPdfDocument
  documentSnapshot: ReadingDocument
  matchesInput(): boolean
  /** Canonical text stays separate from newly decoded display text, especially for imported ZIPs. */
  canonical: { paragraphs: ReadingDocument['paragraphs']; paragraphHash: string }
  decodedDisplay?: Display
  nativePageAnchors?: readonly ReadingAnchor[]
  /** Phone-sharing visit navigation only; never a text identity or synchronization permission. */
  visitPageAnchors?: readonly ReadingAnchor[]
}
async function paragraphHash(paragraphs: ReadingDocument['paragraphs']) {
  // Platform display context, not the narrower portable proof contract or an asserted provenance identity.
  if (paragraphs.length > 100_000 || paragraphs.reduce((n, p) => n + p.text.length, 0) > 5_000_000 || paragraphs.reduce((n, p) => n + p.text.length + p.paragraphId.length, 0) > 32 * 1024 * 1024 || paragraphs.some(p => p.paragraphId.length > 4096)) throw new Error('저장된 PDF 원본을 확인할 수 없습니다.')
  const encoder = new TextEncoder(), values = ['pageturner.pdf-display-paragraphs.v1', String(paragraphs.length), ...paragraphs.flatMap(p => [p.paragraphId, p.text])]
  return localSha256(encoder.encode(values.map(value => `${encoder.encode(value).length}:${value}`).join('')))
}
/** Reads one immutable Blob, then keeps that decoder alive for raster rendering and physical bounds. */
export async function openPdfReadingDocument(document: ReadingDocument, signal?: AbortSignal, phoneSharingVisit = false): Promise<PdfReadingDocument> {
  signal?.throwIfAborted()
  const documentSnapshot = structuredClone(document)
  const blob = document.assets?.pdf, local = document.local ? { ...document.local,
    pdfTextPages: document.local.pdfTextPages?.slice(), pdfTextErrorPages: document.local.pdfTextErrorPages?.slice() } : undefined
  const id = document.id, native = document.kind === 'local' && !document.serverProgress
  const paragraphs = document.paragraphs.map(p => ({ ...p }))
  const metadata = (value: ReadingDocument) => JSON.stringify([value.id, value.kind, value.bookTitle, value.chapterTitle, value.language, value.local, value.outline, value.serverProgress, value.glossaryIdentity])
  const expected = metadata(documentSnapshot)
  const matchesInput = () => document.assets?.pdf === blob && metadata(document) === expected && document.paragraphs.length === paragraphs.length && document.paragraphs.every((p, i) => p.paragraphId === paragraphs[i].paragraphId && p.text === paragraphs[i].text)
  if (!(blob instanceof Blob) || !blob.size || blob.size > 32 * 1024 * 1024 || local && (local.format !== 'pdf' || blob.size !== local.byteLength)) throw new Error('저장된 PDF 원본을 확인할 수 없습니다.')
  const bytes = new Uint8Array(await blob.arrayBuffer()); signal?.throwIfAborted()
  const decoder = await openVerifiedPdf(bytes, signal)
  try {
    if (local && (decoder.context.originalFileSha256 !== local.contentHash || decoder.byteLength !== local.byteLength)) throw new Error('저장된 PDF 원본이 파일 식별자와 일치하지 않습니다.')
    const canonical = { paragraphs, paragraphHash: await paragraphHash(paragraphs) }; decoder.assertOpen()
    let decodedDisplay: Display | undefined, nativePageAnchors: ReadingAnchor[] | undefined, visitPageAnchors: ReadingAnchor[] | undefined
    // This opt-in belongs only to SharingApp composition, never to ZIP metadata. These are
    // the phone's two explicit physical-page projections, verified against the live decoder.
    if (phoneSharingVisit && !local && !documentSnapshot.serverProgress && paragraphs.length === decoder.context.pageCount) {
      const hash = decoder.context.originalFileSha256
      const blankProjection = paragraphs.every((p, index) => p.paragraphId === `pdf:${hash}:page:${index + 1}` && p.text === '')
      // Sharing replaces the document ID with an opaque session inventory ID. The
      // original page IDs, however, retain the exact PDF byte hash and page index.
      const importedProjection = paragraphs.every((p, index) => p.paragraphId === `local-sha256:${hash}:p${index}`)
      if (blankProjection || importedProjection) visitPageAnchors = paragraphs.map(p => ({ paragraphId: p.paragraphId, characterOffset: 0 }))
    }
    // Only native imports own this explicit pN extraction scheme. ZIP IDs or matching counts cannot create it.
    if (native && local && id === `local-sha256:${decoder.context.originalFileSha256}`) {
      try {
        const pages = await extractPdfPages(decoder.pdf, signal); decoder.assertOpen()
        const displayed = pages.texts.map((text, index) => ({ paragraphId: `${id}:p${index}`, text }))
        decodedDisplay = { paragraphs: displayed, paragraphHash: await paragraphHash(displayed), hasText: pages.hasText, textErrors: pages.textErrors }; decoder.assertOpen()
        if (paragraphs.length === decoder.context.pageCount && canonical.paragraphHash === decodedDisplay.paragraphHash &&
          JSON.stringify(local.pdfTextPages) === JSON.stringify(pages.hasText) && JSON.stringify(local.pdfTextErrorPages) === JSON.stringify(pages.textErrors)) {
          nativePageAnchors = paragraphs.map(p => ({ paragraphId: p.paragraphId, characterOffset: 0 }))
        }
      } catch { decoder.assertOpen(); /* Failed extraction must not prevent physical reading or claim matching text. */ }
    }
    decoder.assertOpen()
    if (!matchesInput()) throw new Error('PDF의 표시 본문을 현재 원본과 대응할 수 없습니다. 원본 읽기는 가능하며 읽기 도구를 사용하려면 다시 가져와 주세요.')
    return { decoder, canonical, decodedDisplay, nativePageAnchors, visitPageAnchors, documentSnapshot, matchesInput }
  } catch (error) { await decoder.close().catch(() => undefined); throw error }
}
