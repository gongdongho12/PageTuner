import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createLocalDocuments, parseLocalDocument } from './localDocuments'
import { createReadingNotes } from './readingNotes'
import { createExchangeLibrary, exchangeExportChoices, exchangeReadingDocument } from './exchangeLibrary'
import { readExchange, writeExchange } from './libraryExchange'

const guest = 'pageturner:device-only'
const reader = 'reader'
const file = (name: string, text: string) => {
  const bytes = new TextEncoder().encode(text)
  return { name, size: bytes.length, arrayBuffer: async () => Uint8Array.from(bytes).buffer }
}

beforeEach(() => {
  vi.stubGlobal('crypto', webcrypto)
  // The actual export chooser opens the default databases; keep them isolated from every other test.
  vi.stubGlobal('indexedDB', new IDBFactory())
})
afterEach(() => vi.unstubAllGlobals())

describe('device-only library namespace', () => {
  it('reopens guest files, notes and positions without sharing them with a registered account', async () => {
    const document = await parseLocalDocument(file('Offline.txt', 'First 🌏 paragraph\n\nSecond paragraph'))
    const anchor = { paragraphId: document.paragraphs[1].paragraphId, characterOffset: 3 }
    await createLocalDocuments(guest).save(document)
    const note = await createReadingNotes(guest).add(document, { kind: 'note', title: 'Offline note', text: 'Saved without a server', anchor })
    await createReadingNotes(guest).setPosition(document, anchor)

    const reopened = await createLocalDocuments(guest).list()
    expect(reopened.damagedIds).toEqual([])
    expect(reopened.books.map(book => book.document)).toEqual([document])
    expect(await createReadingNotes(guest).list(document)).toEqual({ items: [note], damagedIds: [] })
    expect(await createReadingNotes(guest).getPosition(document)).toEqual(anchor)
    expect((await createLocalDocuments(reader).list()).books).toEqual([])
    expect((await createReadingNotes(reader).list(document)).items).toEqual([])
    expect(await createReadingNotes(reader).getPosition(document)).toBeUndefined()
    expect(await exchangeExportChoices(reader)).toEqual([])

    // The same file identity in a real account must remain independent in both directions.
    await createLocalDocuments(reader).save(document)
    const accountAnchor = { paragraphId: document.paragraphs[0].paragraphId, characterOffset: 0 }
    const accountNote = await createReadingNotes(reader).add(document, { kind: 'bookmark', title: 'Account bookmark', anchor: accountAnchor })
    await createReadingNotes(reader).setPosition(document, accountAnchor)
    await createLocalDocuments(guest).remove(document.id)
    expect((await createLocalDocuments(guest).list()).books).toEqual([])
    expect((await createLocalDocuments(reader).list()).books.map(book => book.document)).toEqual([document])
    expect((await createReadingNotes(reader).list(document)).items).toEqual([accountNote])
    expect(await createReadingNotes(reader).getPosition(document)).toEqual(accountAnchor)
  })

  it('exports a guest file through the real chooser and imports its exact reading content and records under a separate account', async () => {
    const document = await parseLocalDocument(file('Portable.md', '첫 문단  two spaces 🌏\n\n다음 문단 — second'))
    const organization = { folder: 'Travel', tags: ['offline', '한국어'], favorite: true }
    await createLocalDocuments(guest).save(document)
    await createLocalDocuments(guest).organize(document.id, organization)
    const position = { paragraphId: document.paragraphs[1].paragraphId, characterOffset: document.paragraphs[1].text.length }
    const start = { paragraphId: document.paragraphs[0].paragraphId, characterOffset: 0 }
    const end = { paragraphId: document.paragraphs[0].paragraphId, characterOffset: document.paragraphs[0].text.length }
    const notes = createReadingNotes(guest)
    await notes.add(document, { kind: 'highlight', title: 'Unicode highlight', anchor: start, range: { start, end } })
    await notes.add(document, { kind: 'note', title: 'Keep this note', text: '원래 메모 🌏', anchor: position })
    await notes.setPosition(document, position)
    const expectedNotes = (await notes.list(document)).items.map(({ documentId: _, ...note }) => note)

    const choices = await exchangeExportChoices(guest)
    expect(choices.map(choice => choice.key)).toEqual([`local:${document.id}`])
    const archive = await readExchange(await writeExchange(await choices[0].load()))
    expect(archive.documents).toHaveLength(1)
    expect(archive.documents[0]).toMatchObject({ id: document.id, bookTitle: document.bookTitle,
      chapterTitle: document.chapterTitle, language: document.language, kind: document.kind,
      paragraphs: document.paragraphs, position, notes: expectedNotes, organization })

    const destination = createExchangeLibrary(reader)
    expect(await destination.importPackage(archive)).toEqual({ added: 1, existing: 0 })
    const saved = (await createExchangeLibrary(reader).list())[0]
    expect(saved.document).toEqual(archive.documents[0])
    const reading = exchangeReadingDocument(saved)
    expect(reading.serverProgress).toBeUndefined()
    expect(reading.paragraphs).toEqual(document.paragraphs)
    expect(await createReadingNotes(reader).getPosition(reading)).toEqual(position)
    expect((await createReadingNotes(reader).list(reading)).items.map(({ documentId: _, ...note }) => note)).toEqual(expectedNotes)
    expect((await readExchange(await writeExchange(await destination.exportDocument(saved)))).documents).toEqual(archive.documents)
    expect(await createExchangeLibrary(guest).list()).toEqual([])
    expect((await createReadingNotes(guest).list(document)).items.map(({ documentId: _, ...note }) => note)).toEqual(expectedNotes)
  })
})
