import { webcrypto } from 'node:crypto'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { readingRangeText, highlightedReaderParts, type ReadingRange } from './readingSelection'
import { createReadingNotes } from './readingNotes'
import { readingNotesExport, shareReadingExport } from './readingExport'
import type { ReadingDocument } from './readingDocument'

beforeAll(() => vi.stubGlobal('crypto', webcrypto))
const document: ReadingDocument = { id: 'immutable-document', bookTitle: 'A book', chapterTitle: 'Chapter', language: 'en', kind: 'original',
  paragraphs: [{ paragraphId: 'a', text: 'First 😀 paragraph' }, { paragraphId: 'b', text: 'Second paragraph' }] }
const range: ReadingRange = { start: { paragraphId: 'a', characterOffset: 6 }, end: { paragraphId: 'b', characterOffset: 6 } }

describe('stable reading selections and exports', () => {
  it('stores exact UTF-16 multi-paragraph quotes and preserves highlighting after reflow', () => {
    expect(readingRangeText(document, range)).toBe('😀 paragraph\n\nSecond')
    const parts = highlightedReaderParts(document, { paragraphId: 'a', start: 0, end: 8, text: 'First 😀' }, [range])
    expect(parts).toEqual([{ text: 'First ', highlighted: false }, { text: '😀', highlighted: true }])
    expect(highlightedReaderParts(document, { paragraphId: 'a', start: 8, end: 18, text: ' paragraph' }, [range])).toEqual([{ text: ' paragraph', highlighted: true }])
    expect(highlightedReaderParts(document, { paragraphId: 'b', start: 0, end: 16, text: 'Second paragraph' }, [range])).toEqual([{ text: 'Second', highlighted: true }, { text: ' paragraph', highlighted: false }])
    const overlap = { start: { paragraphId: 'a', characterOffset: 2 }, end: { paragraphId: 'a', characterOffset: 8 } }
    expect(highlightedReaderParts(document, { paragraphId: 'a', start: 0, end: 8, text: 'First 😀' }, [range, overlap]).map(p => p.text).join('')).toBe('First 😀')
  })

  it('rejects reversed, foreign, surrogate-splitting and excessive selections', () => {
    for (const changed of [{ start: range.end, end: range.start }, { ...range, start: { paragraphId: 'foreign', characterOffset: 0 } },
      { ...range, start: { paragraphId: 'a', characterOffset: 7 } }]) expect(() => readingRangeText(document, changed)).toThrow('범위')
    const huge = { ...document, paragraphs: [{ paragraphId: 'a', text: 'a'.repeat(4001) }] }
    expect(() => readingRangeText(huge, { start: { paragraphId: 'a', characterOffset: 0 }, end: { paragraphId: 'a', characterOffset: 4001 } })).toThrow('4,000')
  })

  it('persists highlights per account and refuses moving them onto altered text', async () => {
    const options = { indexedDB: new IDBFactory() }, alice = createReadingNotes('alice', options), bob = createReadingNotes('bob', options)
    const note = await alice.add(document, { kind: 'highlight', title: 'Quote', anchor: range.start, range })
    expect((await createReadingNotes('alice', options).list(document)).items[0]).toMatchObject({ range, excerpt: '😀 paragraph\n\nSecond' })
    expect((await bob.list(document)).items).toEqual([])
    const changed = { ...document, id: 'changed', paragraphs: [{ paragraphId: 'a', text: 'Totally different!' }, document.paragraphs[1]] }
    await alice.migrateDocument(document, changed)
    expect((await alice.list(changed)).items).toEqual([])
    expect((await alice.list(document)).items).toHaveLength(1)
    const renamed = { ...document, id: 'renamed' }
    await alice.migrateDocument(document, renamed)
    expect((await alice.list(renamed)).items[0].range).toEqual(range)
    await alice.remove(renamed.id, note.id)
    expect((await alice.list(renamed)).items).toEqual([])
  })

  it('exports document-order quotes and notes with no account or file payload', async () => {
    const notes = createReadingNotes('private-account', { indexedDB: new IDBFactory() })
    const highlight = await notes.add(document, { kind: 'highlight', title: 'Quote', anchor: range.start, range })
    const note = await notes.add(document, { kind: 'note', title: 'Memo', text: 'My thoughts', anchor: { paragraphId: 'b', characterOffset: 7 } })
    const value = readingNotesExport(document, [note, { ...highlight, username: 'must-not-export' } as typeof highlight], 'json')
    const parsed = JSON.parse(value.text)
    expect(parsed.items.map((item: { kind: string }) => item.kind)).toEqual(['highlight', 'note'])
    expect(parsed.items[0].range).toEqual(range)
    expect(value.text).not.toMatch(/private-account|must-not-export|username|assets/)
    const text = readingNotesExport(document, [note, highlight], 'txt').text
    expect(text).toContain('😀 paragraph\n\nSecond'); expect(text).toContain('My thoughts')
  })

  it('opens share only on the explicit call, uses supported files, and distinguishes cancellation', async () => {
    const share = vi.fn(async (_data: ShareData) => {}); vi.stubGlobal('navigator', { share, canShare: () => true })
    const value = readingNotesExport(document, [], 'txt')
    expect(share).not.toHaveBeenCalled()
    expect(await shareReadingExport(value)).toBe('opened')
    expect(share.mock.calls[0]?.[0]).toMatchObject({ title: 'A book-notes.txt' })
    vi.stubGlobal('navigator', { share: async () => { throw new DOMException('Cancelled', 'AbortError') } })
    expect(await shareReadingExport(value)).toBe('cancelled')
    vi.stubGlobal('navigator', {})
    await expect(shareReadingExport(value)).rejects.toThrow('TXT')
  })
})
