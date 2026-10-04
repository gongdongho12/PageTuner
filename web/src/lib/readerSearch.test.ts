import { describe, expect, it } from 'vitest'
import { searchReadingDocument } from './readerSearch'
import { reflowReaderLocation } from '../components/readerPosition'
import type { ReadingDocument } from './readingDocument'

const document: ReadingDocument = { id: 'book', kind: 'original', bookTitle: 'Book', chapterTitle: 'One', language: 'en', paragraphs: [{ paragraphId: 'p0', text: 'First chapter' }, { paragraphId: 'p9', text: 'İ😀 literal.* Target and target' }] }
describe('reader search and logical page navigation', () => {
  it('finds literal case-insensitive matches using original UTF-16 offsets', () => {
    const result = searchReadingDocument(document, 'target')
    expect(result.hits.map(hit => hit.anchor.characterOffset)).toEqual([document.paragraphs[1].text.indexOf('Target'), document.paragraphs[1].text.indexOf('target')])
    expect(searchReadingDocument(document, '.*').hits).toHaveLength(1)
    expect(searchReadingDocument(document, 'missing').hits).toEqual([])
    expect(searchReadingDocument(document, 'target', 1)).toMatchObject({ truncated: true })
  })
  it('jumps to a search hit and preserves it across font, margin and line-height page changes', () => {
    const anchor = searchReadingDocument(document, 'Target').hits[0].anchor
    const fragment = (start: number, end: number) => ({ paragraphId: 'p9', start, end, text: document.paragraphs[1].text.slice(start, end) })
    const largeFont = [[fragment(0, 12)], [fragment(12, 22)], [fragment(22, 32)]]
    const widePage = [[fragment(0, 32)]]
    let location = reflowReaderLocation(largeFont, anchor)
    expect(location.page).toBe(1); expect(location.anchor).toEqual(anchor)
    location = reflowReaderLocation(widePage, location.anchor); expect(location.page).toBe(0)
    expect(reflowReaderLocation(largeFont, location.anchor)).toEqual({ page: 1, anchor })
  })
  it('does not search synthetic labels belonging to image-only PDF pages', () => {
    const pdf: ReadingDocument = { ...document, local: { format: 'pdf', byteLength: 1, contentHash: 'hash', pdfTextPages: [false, true] } }
    expect(searchReadingDocument(pdf, 'First').hits).toEqual([])
    expect(searchReadingDocument(pdf, 'target').hits).toHaveLength(2)
  })
})
