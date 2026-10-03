import { afterEach, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { validAnchor, validReadingPosition, type ReadingDocument } from './readingDocument'
import { createReadingNotes } from './readingNotes'
import { getWorkflowPosition, setWorkflowPosition } from './workflowPosition'
import { createExchangeLibrary, exchangeReadingDocument } from './exchangeLibrary'
import { readExchange, validateExchangeDocument, writeExchange } from './libraryExchange'

const document: ReadingDocument = { id: 'positions', kind: 'original', bookTitle: 'Book', chapterTitle: 'Chapter', language: 'en', paragraphs: [{ paragraphId: 'one', text: 'A🌏B' }, { paragraphId: 'empty', text: '' }, { paragraphId: 'last', text: 'Last' }] }
const terminal = { paragraphId: 'one', characterOffset: 4 }, empty = { paragraphId: 'empty', characterOffset: 0 }
afterEach(() => vi.unstubAllGlobals())
describe('inclusive reading positions remain distinct from note starts', () => {
  it('allows exact empty/terminal UTF16 positions but rejects malformed and split-surrogate offsets', () => {
    for (const anchor of [terminal, empty, { paragraphId: 'one', characterOffset: 1 }, { paragraphId: 'one', characterOffset: 3 }]) expect(validReadingPosition(document, anchor)).toBe(true)
    for (const anchor of [null, {}, { ...terminal, paragraphId: 'missing' }, { ...terminal, characterOffset: -1 }, { ...terminal, characterOffset: 1.5 }, { ...terminal, characterOffset: 5 }, { ...terminal, characterOffset: 2 }, { ...empty, characterOffset: 1 }, { ...terminal, extra: true }]) expect(validReadingPosition(document, anchor)).toBe(false)
    expect(validAnchor(document, terminal)).toBe(false); expect(validAnchor(document, empty)).toBe(false)
  })
  it('retains exact progress across reopen and legacy position migration without expanding notes', async () => {
    const options = { indexedDB: new IDBFactory() }, notes = createReadingNotes('alice', options)
    for (const anchor of [terminal, empty]) {
      await notes.setPosition(document, anchor)
      expect(await createReadingNotes('alice', options).getPosition(document)).toEqual(anchor)
      await expect(notes.add(document, { kind: 'bookmark', title: 'Outside note range', anchor })).rejects.toThrow()
    }
    const legacy = { ...document, id: 'legacy-position' }, canonical = { ...document, id: 'canonical-position' }
    await notes.setPosition(legacy, empty); await notes.migrateDocument(legacy, canonical)
    expect(await notes.getPosition(canonical)).toEqual(empty)
    expect(await notes.getPosition(legacy)).toBeUndefined()
    await expect(notes.setPosition(document, { ...terminal, characterOffset: 2 })).rejects.toThrow()
    expect(await notes.getPosition(document)).toEqual(empty)
    expect(await createReadingNotes('bob', options).getPosition(document)).toBeUndefined()
  })
  it('restores terminal and empty workflow positions without accepting corrupt legacy JSON', () => {
    const values = new Map<string, string>(); vi.stubGlobal('localStorage', { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value) })
    for (const anchor of [terminal, empty]) { setWorkflowPosition('alice', document, anchor); expect(getWorkflowPosition('alice', document)).toEqual(anchor) }
    setWorkflowPosition('alice', document, { ...terminal, characterOffset: 2 }); expect(getWorkflowPosition('alice', document)).toEqual(empty)
    for (const key of values.keys()) values.set(key, JSON.stringify({ ...terminal, characterOffset: 2 }))
    expect(getWorkflowPosition('alice', document)).toBeUndefined()
  })
  it('imports and re-exports end/empty ZIP positions while keeping unsupported note anchors portable only', async () => {
    const options = { indexedDB: new IDBFactory() }, library = createExchangeLibrary('alice', options), createdAt = '2026-10-03T00:00:00Z'
    const documents = [terminal, empty].map((position, index) => validateExchangeDocument({ ...document, id: `zip:${index}`, position, notes: [{ id: `native-note:${index}`, kind: 'bookmark', title: 'Native boundary', text: '', excerpt: '', anchor: position, createdAt }] }))
    await library.importPackage({ createdAt, documents, assets: [] })
    for (const saved of await library.list()) {
      expect(await createReadingNotes('alice', options).getPosition(exchangeReadingDocument(saved))).toEqual(saved.document.position)
      expect((await createReadingNotes('alice', options).list(exchangeReadingDocument(saved))).items).toEqual([])
      const result = await readExchange(await writeExchange(await library.exportDocument(saved)))
      expect(result.documents[0].position).toEqual(saved.document.position); expect(result.documents[0].notes).toEqual(saved.document.notes)
    }
  })
})
