import { createHash } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, expect, it, vi } from 'vitest'
import vector from '../../../contracts/fixtures/pdf-content-v1.json'
import { preparePdfContentFromExchange } from './exchangePdfContent'
import { createExchangeLibrary } from './exchangeLibrary'
import { readingTransaction } from './deviceReadingDatabase'
import { pdfContentLimits } from './pdfContentApi'
import type { ExchangeAsset, ExchangeDocument, ExchangePackage } from './libraryExchange'

const asset = (bytes: Uint8Array, mimeType = 'image/png'): ExchangeAsset => ({ bytes, mimeType, path: `assets/${createHash('sha256').update(bytes).digest('hex')}` })
function fixture(): ExchangePackage {
  const value = vector.upload.content
  const document: ExchangeDocument = { id: 'fixture:pdf', bookTitle: 'Original title', chapterTitle: 'Chapter', language: value.language, kind: 'local',
    paragraphs: structuredClone(value.paragraphs), outline: [], notes: [], organization: { folder: '', tags: [], favorite: false }, glossary: [],
    assets: value.assets.map(a => ({ path: a.path, role: a.role as 'pdf' | 'image', ...(a.paragraphId === null ? {} : { paragraphId: a.paragraphId }), ...(a.alt === null ? {} : { alt: a.alt }) })),
    extensions: { pdfTextPages: [true, false], pdfTextErrorPages: [false, true], sourceHint: 'not-provenance' } }
  return { createdAt: '2026-10-05T00:00:00Z', documents: [document], assets: value.payloads.map(p => ({ path: p.path, mimeType: p.mimeType, bytes: Uint8Array.from(Buffer.from(p.base64, 'base64')) })) }
}
type StoredRow = Awaited<ReturnType<ReturnType<typeof createExchangeLibrary>['list']>>[number] & { username: string }
const setup = () => { const options = { indexedDB: new IDBFactory(), dbName: 'pdf-exchange-prepare' }; return { options, library: createExchangeLibrary('reader', options) } }
const edit = async (options: Parameters<typeof createExchangeLibrary>[1], id: string, change: (row: StoredRow) => void) => readingTransaction(['exchanges'], 'readwrite', (tx, done) => {
  const store = tx.objectStore('exchanges'), request = store.get(['reader', id]); request.onsuccess = () => { change(request.result); store.put(request.result); done(undefined) }
}, options)

describe('read-only PDF preparation from exact ZIP content', () => {
  it('matches the shared storage fixture, ignoring titles, layout hints, and source claims', async () => {
    const value = fixture(), result = await preparePdfContentFromExchange(value, 0)
    expect(result).toEqual({ content: vector.upload.content, proof: vector.expected.proof })
    expect(Object.keys(result.content)).toEqual(['version', 'language', 'paragraphs', 'assets', 'payloads'])
    expect(result.content.paragraphs[1].text).toBe('')
  })
  it('uses first reference occurrence for unique payloads and keeps every repeated reference', async () => {
    const value = fixture(), doc = value.documents[0]; doc.assets = [doc.assets[1], doc.assets[0], doc.assets[2]]; value.assets.reverse()
    const result = await preparePdfContentFromExchange(value, 0)
    expect(result.content.payloads.map(p => p.path)).toEqual([doc.assets[0].path, doc.assets[1].path])
    expect(result.content.assets).toEqual(doc.assets.map(a => ({ ...a, paragraphId: a.paragraphId ?? null, alt: a.alt ?? null })))
    const reversed = structuredClone(value); reversed.assets.reverse()
    expect(await preparePdfContentFromExchange(reversed, 0)).toEqual(result)
  })
  it('selects one exact document and excludes other documents and their payloads', async () => {
    const value = fixture(), other = asset(new Uint8Array([1, 2, 3])); value.assets.unshift(other)
    value.documents.unshift({ ...structuredClone(value.documents[0]), id: 'other', assets: [{ path: other.path, role: 'image' }] })
    expect((await preparePdfContentFromExchange(value, 1)).content).toEqual(vector.upload.content)
    await expect(preparePdfContentFromExchange(value, 0)).rejects.toMatchObject({ code: 'invalid-request' })
  })
  it('snapshots original IDs, language, text, empty paragraphs and bytes before asynchronous hashing', async () => {
    const value = fixture(), pending = preparePdfContentFromExchange(value, 0)
    value.documents[0].language = 'EN'; value.documents[0].paragraphs[0].text = 'changed'; value.documents[0].assets[1].alt = 'changed'; value.assets[0].bytes.fill(0); value.assets.reverse()
    expect(await pending).toEqual({ content: vector.upload.content, proof: vector.expected.proof })
    const empty = fixture(); empty.documents[0].paragraphs = []; empty.documents[0].assets = [empty.documents[0].assets[0]]; empty.assets = [empty.assets[0]]
    expect((await preparePdfContentFromExchange(empty, 0)).content.paragraphs).toEqual([])
  })
  it('rejects wrong indices, wrong representation, duplicate/missing payloads and corrupt bytes', async () => {
    for (const index of [-1, 1, 0.5, NaN, Infinity]) await expect(preparePdfContentFromExchange(fixture(), index)).rejects.toMatchObject({ code: 'invalid-request' })
    const changes: ((value: ExchangePackage) => void)[] = [
      v => { v.documents[0].assets = v.documents[0].assets.filter(a => a.role === 'image') },
      v => { v.documents[0].assets.push(v.documents[0].assets[0]) },
      v => { v.assets.pop() }, v => { v.assets.push(v.assets[0]) },
      v => { v.assets[0].bytes[0] ^= 1 }, v => { v.assets[0].mimeType = 'image/png' },
      v => { v.documents = Array.from({ length: 101 }, () => v.documents[0]) },
      v => { v.documents[0].assets[1].paragraphId = 'missing' },
      v => { v.documents[0].paragraphs[0].text = '\ud800' },
    ]
    for (const mutate of changes) { const value = fixture(); mutate(value); await expect(preparePdfContentFromExchange(value, 0)).rejects.toMatchObject({ code: 'invalid-request' }) }
  })
  it('rejects storage-profile overflow before base64 encoding without truncating valid ZIP content', async () => {
    const changes: ((value: ExchangePackage) => void)[] = [
      v => { const large = new Uint8Array(pdfContentLimits.payloadBytes + 1); large.set(new TextEncoder().encode('%PDF-')); const pdf = asset(large, 'application/pdf'); v.assets[0] = pdf; v.documents[0].assets[0].path = pdf.path },
      v => { v.documents[0].paragraphs = Array.from({ length: 4097 }, (_, i) => ({ paragraphId: `p${i}`, text: '' })); v.documents[0].assets = [v.documents[0].assets[0]] },
      v => { v.documents[0].paragraphs[0].text = 'x'.repeat(pdfContentLimits.metadataCodeUnits) },
      v => { v.documents[0].assets = [v.documents[0].assets[0], ...Array.from({ length: 128 }, () => v.documents[0].assets[1])] },
      v => { v.assets.push({ path: `assets/${'0'.repeat(64)}`, mimeType: 'image/png', bytes: new Uint8Array(32 * 1024 * 1024 + 1) }) },
    ]
    const encode = vi.spyOn(globalThis, 'btoa')
    try { for (const mutate of changes) { const value = fixture(); mutate(value); await expect(preparePdfContentFromExchange(value, 0)).rejects.toMatchObject({ code: 'invalid-request' }) }; expect(encode).not.toHaveBeenCalled() }
    finally { encode.mockRestore() }
  })
  it('honors cancellation before reads and after asynchronous hash work', async () => {
    const controller = new AbortController(), pending = preparePdfContentFromExchange(fixture(), 0, controller.signal); controller.abort()
    await expect(pending).rejects.toMatchObject({ code: 'aborted' }); await expect(preparePdfContentFromExchange(fixture(), 0, controller.signal)).rejects.toMatchObject({ code: 'aborted' })
  })
})

describe('live IndexedDB ZIP PDF preparation', () => {
  it('reads one current canonical row instead of cached list/reader state, without changing any stored book', async () => {
    const { library } = setup(), value = fixture(), other = asset(new Uint8Array([4, 5, 6])); value.assets.unshift(other)
    value.documents.push({ ...structuredClone(value.documents[0]), id: 'other', assets: [{ path: other.path, role: 'image' }] })
    await library.importPackage(value)
    const before = await library.list(), cached = before.find(b => b.document.id === 'fixture:pdf')!, id = cached.id
    cached.document.language = 'EN'; cached.document.paragraphs[0].text = 'reader-normalized'; cached.assets[0].bytes.fill(0)
    const prepared = await library.preparePdfContent(id)
    expect(prepared).toEqual({ content: vector.upload.content, proof: vector.expected.proof })
    await expect(library.preparePdfContent(before.find(b => b.document.id === 'other')!.id)).rejects.toMatchObject({ code: 'invalid-request' })
    expect((await library.list()).find(b => b.id === id)!.document.paragraphs).toEqual(vector.upload.content.paragraphs)
    expect((await library.list()).length).toBe(2)
  })
  it('keeps account boundaries and notices deletion after a list snapshot', async () => {
    const { library, options } = setup(); await library.importPackage(fixture()); const saved = (await library.list())[0]
    await expect(createExchangeLibrary('other', options).preparePdfContent(saved.id)).rejects.toMatchObject({ code: 'not-found' })
    await readingTransaction(['exchanges'], 'readwrite', (tx, done) => { tx.objectStore('exchanges').delete(['reader', saved.id]); done(undefined) }, options)
    await expect(library.preparePdfContent(saved.id)).rejects.toMatchObject({ code: 'not-found' })
    await expect(library.preparePdfContent('fixture:pdf')).rejects.toMatchObject({ code: 'invalid-request' })
  })
  it('keeps a valid larger ZIP readable while refusing a PDF storage snapshot above its 4MiB profile', async () => {
    const { library } = setup(), value = fixture(), bytes = new Uint8Array(pdfContentLimits.payloadBytes + 1)
    bytes.set(new TextEncoder().encode('%PDF-')); const pdf = asset(bytes, 'application/pdf')
    value.assets = [pdf]; value.documents[0].assets = [{ path: pdf.path, role: 'pdf' }]
    await library.importPackage(value); const saved = (await library.list())[0]
    await expect(library.preparePdfContent(saved.id)).rejects.toMatchObject({ code: 'invalid-request' })
    expect(Buffer.from((await library.list())[0].assets[0].bytes).equals(Buffer.from(bytes))).toBe(true)
  })
  it('rejects live missing/changed bytes, wrong byte type, metadata changes and checksums', async () => {
    const changes: ((row: StoredRow) => void)[] = [r => { r.assets.pop() }, r => { r.assets[0].bytes[0] ^= 1 },
      r => { r.assets[0].bytes = {} as Uint8Array }, r => { r.document.language = 'EN' }, r => { r.checksum = '0'.repeat(64) },
      r => { r.assets.push(asset(new Uint8Array([1]))) }]
    for (const change of changes) { const { library, options } = setup(); await library.importPackage(fixture()); const id = (await library.list())[0].id; await edit(options, id, change); await expect(library.preparePdfContent(id)).rejects.toMatchObject({ code: 'invalid-request' }) }
  })
  it('prepares from one coherent database snapshot even if the stored row changes during hashing', async () => {
    const { library, options } = setup(); await library.importPackage(fixture()); const id = (await library.list())[0].id
    const original = crypto.subtle.digest.bind(crypto.subtle); let continueHash!: () => void
    const gate = new Promise<void>(resolve => { continueHash = resolve }), digest = vi.spyOn(crypto.subtle, 'digest').mockImplementationOnce(async (...args) => { await gate; return original(...args) })
    try {
      const pending = library.preparePdfContent(id); await vi.waitFor(() => expect(digest).toHaveBeenCalled())
      await edit(options, id, row => { row.document.language = 'EN'; row.assets[0].bytes.fill(0) }); continueHash()
      expect(await pending).toEqual({ content: vector.upload.content, proof: vector.expected.proof })
      await expect(library.preparePdfContent(id)).rejects.toMatchObject({ code: 'invalid-request' })
    } finally { continueHash(); digest.mockRestore() }
  })
})
