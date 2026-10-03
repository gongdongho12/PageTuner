import { afterEach, describe, expect, it, vi } from 'vitest'
import { paginateReaderParagraphs } from './PagedReader'
import { reflowReaderLocation, turnReaderPage } from './readerPosition'

/** Deterministic line-height probe. Production reads the same paragraph DOM for actual font/viewport metrics. */
class MeasuredElement {
  className = ''; textContent = ''; children: MeasuredElement[] = []; parent?: MeasuredElement
  append(child: MeasuredElement) { child.parent = this; this.children.push(child) }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this) }
  replaceChildren() { this.children = [] }
  getBoundingClientRect() { return { height: this.children.reduce((total, child) => total + (child.className.includes('reader-paragraph-empty') ? 1 : Array.from(child.textContent).length), 0) } }
}
afterEach(() => vi.unstubAllGlobals())
describe('measured pagination retains empty canonical paragraphs', () => {
  it('keeps first/interior/last empty paragraphs and their anchors across changing page sizes', () => {
    vi.stubGlobal('document', { createElement: () => new MeasuredElement() })
    const paragraphs = [{ paragraphId: 'empty-first', text: '' }, { paragraphId: 'body', text: 'Text' }, { paragraphId: 'empty-middle', text: '' }, { paragraphId: 'second', text: 'Next' }, { paragraphId: 'empty-last', text: '' }]
    for (const height of [4, 7, 20, 4]) {
      const pages = paginateReaderParagraphs(paragraphs, new MeasuredElement() as unknown as HTMLDivElement, height, new Map())
      expect(pages.flat().filter(fragment => fragment.text === '').map(fragment => fragment.paragraphId)).toEqual(['empty-first', 'empty-middle', 'empty-last'])
      for (const id of ['empty-first', 'empty-middle', 'empty-last']) {
        const anchor = { paragraphId: id, characterOffset: 0 }, location = reflowReaderLocation(pages, anchor)
        expect(location.anchor).toEqual(anchor); expect(pages[location.page].some(fragment => fragment.paragraphId === id)).toBe(true)
      }
      expect(reflowReaderLocation(pages, { paragraphId: 'body', characterOffset: 4 }).anchor).toEqual({ paragraphId: 'body', characterOffset: 4 })
    }
  })
  it('paginates an entirely empty document without fabricating text or replacing the saved paragraph', () => {
    vi.stubGlobal('document', { createElement: () => new MeasuredElement() })
    const paragraphs = ['a', 'b', 'c'].map(paragraphId => ({ paragraphId, text: '' }))
    const pages = paginateReaderParagraphs(paragraphs, new MeasuredElement() as unknown as HTMLDivElement, 3, new Map())
    expect(pages).toEqual(paragraphs.map(paragraph => [{ ...paragraph, start: 0, end: 0 }]))
    const anchor = { paragraphId: 'b', characterOffset: 0 }, location = reflowReaderLocation(pages, anchor)
    expect(location).toEqual({ page: 1, anchor }); expect(turnReaderPage(pages, location, 1)).toEqual({ page: 2, anchor: { paragraphId: 'c', characterOffset: 0 } })
    expect(() => paginateReaderParagraphs(paragraphs, new MeasuredElement() as unknown as HTMLDivElement, 2, new Map())).toThrow()
  })
})
