import { describe, it, expect } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { readFile } from 'node:fs/promises'
import { createExchangeLibrary, exchangeReadingDocument } from './exchangeLibrary'
import { readExchange, writeExchange } from './libraryExchange'
import { createReadingNotes } from './readingNotes'

const fixture = () => readFile(new URL('../../../contracts/fixtures/library-exchange-v1/portable-v1.zip', import.meta.url)).then(bytes => readExchange(new Uint8Array(bytes)))

describe('ZIP library integration', () => {
  it('imports and re-exports image-only books without inventing text anchors', async () => {
    const options = { indexedDB: new IDBFactory(), dbName: 'exchange-images' }, library = createExchangeLibrary('reader', options), input = await fixture()
    const image = input.assets.find(a => a.mimeType === 'image/png')!
    const document = { ...input.documents[0], paragraphs: [], outline: [], notes: [], position: undefined, assets: [{ path: image.path, role: 'image' as const }] }
    const value = { createdAt: input.createdAt, documents: [document], assets: [image] }
    expect(await library.importPackage(value)).toEqual({ added: 1, existing: 0 })
    const saved = (await library.list())[0]
    expect(exchangeReadingDocument(saved).paragraphs).toEqual([])
    const exported = await readExchange(await writeExchange(await library.exportDocument(saved)))
    expect(exported.documents[0].paragraphs).toEqual([])
    expect(exported.documents[0].assets).toEqual(document.assets)
    expect(exported.assets[0].bytes).toEqual(image.bytes)
  })

  it('imports all documents atomically, keeps account isolation and re-exports edited notes without dropping assets or native metadata', async () => {
    const options = { indexedDB: new IDBFactory(), dbName: 'exchange-flow' }, library = createExchangeLibrary('reader', options), input = await fixture()
    expect(await library.importPackage(input)).toEqual({ added: 3, existing: 0 })
    expect(await library.importPackage(input)).toEqual({ added: 0, existing: 3 })
    expect(await createExchangeLibrary('other-reader', options).list()).toEqual([])
    const saved = (await library.list()).find(b => b.document.id === 'fixture:original')!, document = exchangeReadingDocument(saved), notes = createReadingNotes('reader', options)
    const imported = await notes.list(document)
    expect(imported.items.length).toBeGreaterThan(0)
    const position = { paragraphId: document.paragraphs[1].paragraphId, characterOffset: 1 }
    await notes.setPosition(document, position)
    await notes.add(document, { kind: 'note', title: 'PC reading note', text: 'Keep this on Android.', anchor: position })
    const roundtrip = await readExchange(await writeExchange(await library.exportDocument(saved)))
    expect(roundtrip.documents[0].position).toEqual(position)
    expect(roundtrip.documents[0].notes.some(n => n.text === 'Keep this on Android.')).toBe(true)
    expect(roundtrip.documents[0].extensions).toEqual(saved.document.extensions)
    expect(roundtrip.documents[0].paragraphs).toEqual(input.documents[0].paragraphs)
    expect(roundtrip.assets.map(a => [...a.bytes])).toEqual(saved.assets.map(a => [...a.bytes]))
  })

  it('validates the entire import before writing and gives changed incoming metadata an independent copy', async () => {
    const options = { indexedDB: new IDBFactory(), dbName: 'exchange-atomic' }, library = createExchangeLibrary('reader', options), value = await fixture()
    const broken = structuredClone(value); broken.documents[2].position = { paragraphId: 'missing', characterOffset: 0 }
    await expect(library.importPackage(broken)).rejects.toThrow()
    expect(await library.list()).toEqual([])
    await library.importPackage(value)
    const changed = structuredClone(value); changed.documents[0].organization.folder = 'Changed on another device'
    expect(await library.importPackage(changed)).toEqual({ added: 1, existing: 2 })
    expect((await library.list()).filter(b => b.document.id === 'fixture:original').map(b => b.document.organization.folder)).toContain('Changed on another device')
  })

  it('retains native notes outside the web editor limits and does not resurrect deleted editable notes', async () => {
    const options = { indexedDB: new IDBFactory(), dbName: 'exchange-retain' }, library = createExchangeLibrary('reader', options), value = await fixture()
    value.documents[0].notes.push({ id: 'long-native-note', title: '', text: 'N'.repeat(9000), excerpt: '', kind: 'note', anchor: { paragraphId: 'p-1', characterOffset: 0 }, createdAt: value.createdAt })
    await library.importPackage(value)
    const saved = (await library.list()).find(b => b.document.id === 'fixture:original')!, reading = exchangeReadingDocument(saved), notes = createReadingNotes('reader', options)
    const editable = await notes.list(reading)
    expect(editable.damagedIds).toEqual([])
    for (const note of editable.items) await notes.remove(reading.id, note.id)
    const exported = await library.exportDocument(saved)
    expect(exported.documents[0].notes).toEqual([value.documents[0].notes.at(-1)])
  })
})
