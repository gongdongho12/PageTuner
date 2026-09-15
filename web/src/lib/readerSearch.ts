import type { ReadingDocument } from './readingDocument'
import type { ReadingAnchor } from './offline'

export type ReaderSearchHit = { anchor: ReadingAnchor; length: number; excerpt: string; paragraphIndex: number }
export function searchReadingDocument(document: ReadingDocument, query: string, limit = 500): { hits: ReaderSearchHit[]; truncated: boolean } {
  const term = query.trim()
  if (!term) return { hits: [], truncated: false }
  if (term.length > 200) throw new Error('검색어는 200자 이하로 입력해 주세요.')
  // RegExp /iu preserves offsets in the original UTF-16 text even when Unicode
  // case folding changes string lengths (unlike searching a lowercased copy).
  const expression = new RegExp(term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'giu')
  const hits: ReaderSearchHit[] = []
  for (const [paragraphIndex, paragraph] of document.paragraphs.entries()) {
    if (document.local?.format === 'pdf' && !document.local.pdfTextPages?.[paragraphIndex]) continue
    expression.lastIndex = 0
    for (const match of paragraph.text.matchAll(expression)) {
      if (hits.length >= limit) return { hits, truncated: true }
      const start = match.index!, before = Math.max(0, start - 35), after = Math.min(paragraph.text.length, start + match[0].length + 70)
      hits.push({ anchor: { paragraphId: paragraph.paragraphId, characterOffset: start }, length: match[0].length,
        excerpt: `${before ? '…' : ''}${paragraph.text.slice(before, after)}${after < paragraph.text.length ? '…' : ''}`, paragraphIndex })
    }
  }
  return { hits, truncated: false }
}
