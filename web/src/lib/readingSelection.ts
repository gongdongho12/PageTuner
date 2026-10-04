import type { ReadingAnchor } from './offline'
import type { ReadingDocument } from './readingDocument'

export type ReadingRange = { start: ReadingAnchor; end: ReadingAnchor }
export type ReadingSelection = { range: ReadingRange; quote: string }
const invalid = () => new Error('강조할 본문 범위를 확인할 수 없습니다. 본문에서 다시 선택해 주세요.')
function boundary(text: string, offset: number) {
  return Number.isSafeInteger(offset) && offset >= 0 && offset <= text.length &&
    !(offset > 0 && offset < text.length && /[\uD800-\uDBFF]/.test(text[offset - 1]) && /[\uDC00-\uDFFF]/.test(text[offset]))
}

/** End is exclusive; UTF-16 positions match the existing reader pagination and anchors. */
export function readingRangeText(document: ReadingDocument, range: ReadingRange): string {
  if (!range?.start || !range.end) throw invalid()
  const start = document.paragraphs.findIndex(p => p.paragraphId === range.start.paragraphId)
  const end = document.paragraphs.findIndex(p => p.paragraphId === range.end.paragraphId)
  if (start < 0 || end < start || !boundary(document.paragraphs[start].text, range.start.characterOffset) ||
      !boundary(document.paragraphs[end].text, range.end.characterOffset) || range.start.characterOffset >= document.paragraphs[start].text.length ||
      (start === end && range.start.characterOffset >= range.end.characterOffset)) throw invalid()
  let text = ''
  for (let index = start; index <= end; index++) {
    const paragraph = document.paragraphs[index]
    text += (index > start ? '\n\n' : '') + paragraph.text.slice(index === start ? range.start.characterOffset : 0, index === end ? range.end.characterOffset : undefined)
    if (text.length > 4000) throw new Error('한 번에 4,000자 이하를 선택해 주세요.')
  }
  if (!text.trim()) throw invalid()
  return text
}

/** Read DOM text positions, including existing <mark> children, without relying on screen coordinates. */
export function captureReadingSelection(document: ReadingDocument, root: HTMLElement, selection: Selection | null,
  sourceAnchor: (anchor: ReadingAnchor, edge: 'start' | 'end') => ReadingAnchor = anchor => anchor): ReadingSelection | undefined {
  if (!selection || selection.isCollapsed || selection.rangeCount !== 1) return undefined
  const selected = selection.getRangeAt(0)
  if (!root.contains(selected.startContainer) || !root.contains(selected.endContainer)) return undefined
  const captured: { start: ReadingAnchor; end: ReadingAnchor }[] = []
  for (const paragraph of root.querySelectorAll<HTMLElement>('[data-paragraph-id][data-character-offset]')) {
    if (!selected.intersectsNode(paragraph)) continue
    const part = root.ownerDocument.createRange(); part.selectNodeContents(paragraph)
    // Range constants are numeric to avoid global DOM constructors in non-browser module imports.
    if (selected.compareBoundaryPoints(0, part) > 0) part.setStart(selected.startContainer, selected.startOffset)
    if (selected.compareBoundaryPoints(2, part) < 0) part.setEnd(selected.endContainer, selected.endOffset)
    if (part.collapsed || !part.toString()) continue
    const prefix = root.ownerDocument.createRange(); prefix.selectNodeContents(paragraph); prefix.setEnd(part.startContainer, part.startOffset)
    const start = Number(paragraph.dataset.characterOffset) + prefix.toString().length
    const paragraphId = paragraph.dataset.paragraphId!
    captured.push({ start: sourceAnchor({ paragraphId, characterOffset: start }, 'start'), end: sourceAnchor({ paragraphId, characterOffset: start + part.toString().length }, 'end') })
  }
  if (!captured.length) return undefined
  const range = { start: captured[0].start, end: captured[captured.length - 1].end }
  return { range, quote: readingRangeText(document, range) }
}

export function highlightedReaderParts(document: ReadingDocument, fragment: { paragraphId: string; start: number; end: number; text: string }, ranges: ReadingRange[], indices: ReadonlyMap<string, number> = new Map(document.paragraphs.map((p, index) => [p.paragraphId, index]))): { text: string; highlighted: boolean }[] {
  const index = indices.get(fragment.paragraphId)
  if (index === undefined) return [{ text: fragment.text, highlighted: false }]
  const spans = ranges.flatMap(range => {
    const start = indices.get(range.start.paragraphId), end = indices.get(range.end.paragraphId)
    if (start === undefined || end === undefined || index < start || index > end) return []
    const from = Math.max(fragment.start, index === start ? range.start.characterOffset : 0)
    const to = Math.min(fragment.end, index === end ? range.end.characterOffset : fragment.end)
    return to > from ? [[from - fragment.start, to - fragment.start]] : []
  }).sort((a, b) => a[0] - b[0])
  const merged: number[][] = []
  for (const span of spans) {
    const previous = merged[merged.length - 1]
    if (previous && span[0] <= previous[1]) previous[1] = Math.max(previous[1], span[1]); else merged.push([...span])
  }
  const parts: { text: string; highlighted: boolean }[] = []; let at = 0
  for (const [from, to] of merged) {
    if (from > at) parts.push({ text: fragment.text.slice(at, from), highlighted: false })
    parts.push({ text: fragment.text.slice(from, to), highlighted: true }); at = to
  }
  if (at < fragment.text.length) parts.push({ text: fragment.text.slice(at), highlighted: false })
  return parts
}
