import { IDBFactory, IDBObjectStore, IDBDatabase } from 'fake-indexeddb'
import { describe, expect, it, vi } from 'vitest'
import vector from '../../../contracts/fixtures/pdf-content-v1.json'
import { createExchangeLibrary, exchangeReadingDocument } from './exchangeLibrary'
import { readingTransaction } from './deviceReadingDatabase'
import { PdfContentError, type PdfContentClient, type PdfContentDocument, type PdfContentRecord } from './pdfContentApi'
import { createPortablePdfSession, pdfBindingErrors } from './portablePdfBinding'
import type { ExchangePackage } from './libraryExchange'

const recordId = '11111111-1111-4111-8111-111111111111'
const record = (): PdfContentRecord => ({ recordId, createdAt: '2026-10-05T00:00:00Z', content: structuredClone(vector.upload.content) as PdfContentDocument, proof: structuredClone(vector.expected.proof) as PdfContentRecord['proof'] })
function fixture(): ExchangePackage {
  const value = vector.upload.content
  return { createdAt: '2026-10-05T00:00:00Z', documents: [{ id: 'fixture', bookTitle: 'PDF', chapterTitle: 'Chapter', kind: 'local', language: value.language,
    paragraphs: structuredClone(value.paragraphs), assets: value.assets.map(a => ({ path: a.path, role: a.role as 'pdf' | 'image', ...(a.paragraphId === null ? {} : { paragraphId: a.paragraphId }), ...(a.alt === null ? {} : { alt: a.alt }) })),
    outline: [], notes: [], glossary: [], organization: { folder: '', tags: [], favorite: false } }], assets: value.payloads.map(p => ({ path: p.path, mimeType: p.mimeType, bytes: Uint8Array.from(Buffer.from(p.base64, 'base64')) })) }
}
async function setup() {
  const options = { indexedDB: new IDBFactory(), dbName: 'pdf-binding' }, library = createExchangeLibrary('reader', options)
  await library.importPackage(fixture()); const saved = (await library.list())[0]
  const client: PdfContentClient = { upload: vi.fn(async () => record()), get: vi.fn(async () => record()), verify: vi.fn(), close: vi.fn() }
  let generation = 0
  const session = () => { const ticket = generation; return createPortablePdfSession({ username: 'reader', origin: 'https://example.test', client, current: () => generation === ticket, ...options }) }
  const rows = () => readingTransaction<unknown[]>(['pdfContentBindings'], 'readonly', (tx, done) => { const r = tx.objectStore('pdfContentBindings').getAll(); r.onsuccess = () => done(r.result) }, options)
  return { options, library, saved, client, session, rows, changeSession: () => { generation++ } }
}
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }

describe('explicit ZIP PDF binding', () => {
  it('prepares read-only, uploads separately, rehashes through GET then binds only after explicit confirmation', async () => {
    const s = await setup(), review = await s.session().prepare(s.saved.id)
    expect(review.proof).toEqual(vector.expected.proof); expect(await s.rows()).toEqual([]); expect(s.client.upload).not.toHaveBeenCalled(); expect(s.client.get).not.toHaveBeenCalled()
    const receipt = await review.upload(); expect(receipt.recordId).toBe(recordId); expect(await s.rows()).toEqual([])
    expect(s.client.upload).toHaveBeenCalledWith(expect.any(String), vector.upload.content, expect.any(AbortSignal))
    await review.confirm(); expect(s.client.get).toHaveBeenCalledTimes(2)
    expect(await s.rows()).toMatchObject([{ username: 'reader', origin: 'https://example.test', copyId: s.saved.id, link: { recordId, proof: vector.expected.proof } }])
    expect(s.client.verify).not.toHaveBeenCalled()
    const opened = await (await s.session().prepare(s.saved.id)).open()
    expect(opened.document).toEqual(s.saved.document)
    expect(exchangeReadingDocument(opened).serverProgress).toBeUndefined(); expect(exchangeReadingDocument(opened).glossaryIdentity).toBeUndefined()
    expect((await s.library.exportDocument(opened)).documents[0]).toEqual(s.saved.document)
  })
  it('retains one upload UUID and immutable exact input after uncertain responses, despite caller mutations', async () => {
    const s = await setup(), review = await s.session().prepare(s.saved.id)
    review.content.language = 'en'; review.proof.sha256 = '0'.repeat(64)
    vi.mocked(s.client.upload).mockRejectedValueOnce(new PdfContentError('timeout'))
    await expect(review.upload()).rejects.toMatchObject({ code: 'timeout' }); await review.upload()
    const calls = vi.mocked(s.client.upload).mock.calls; expect(calls[0][0]).toBe(calls[1][0]); expect(calls[0][1]).toEqual(vector.upload.content)
    await review.confirm(); expect(await s.rows()).toMatchObject([{ link: { proof: vector.expected.proof } }])
  })
  it('uses an explicit existing record comparison without uploading', async () => {
    const s = await setup(), review = await s.session().prepare(s.saved.id)
    await review.compare(recordId); expect(await s.rows()).toEqual([]); await review.confirm()
    expect(s.client.upload).not.toHaveBeenCalled(); expect(await s.rows()).toHaveLength(1)
    await expect(review.confirm()).rejects.toThrow(pdfBindingErrors.stale)
  })
  it('invalidates an old successful candidate when a later compare or upload fails', async () => {
    for (const upload of [false, true]) {
      const s = await setup(), review = await s.session().prepare(s.saved.id); await review.compare(recordId)
      if (upload) { vi.mocked(s.client.upload).mockRejectedValueOnce(new PdfContentError('timeout')); await expect(review.upload()).rejects.toMatchObject({ code: 'timeout' }) }
      else { vi.mocked(s.client.get).mockRejectedValueOnce(new PdfContentError('not-found')); await expect(review.compare('22222222-2222-4222-8222-222222222222')).rejects.toMatchObject({ code: 'not-found' }) }
      await expect(review.confirm()).rejects.toThrow(pdfBindingErrors.stale); expect(await s.rows()).toEqual([])
    }
  })
  it('rejects sparse array length changes in the exact local snapshot', async () => {
    const s = await setup(), review = await s.session().prepare(s.saved.id); await review.compare(recordId)
    await readingTransaction(['exchanges'], 'readwrite', (tx, done) => { const store = tx.objectStore('exchanges'), r = store.get(['reader', s.saved.id]); r.onsuccess = () => { r.result.document.paragraphs.length++; store.put(r.result); done(undefined) } }, s.options)
    await expect(review.confirm()).rejects.toThrow(pdfBindingErrors.stale); expect(await s.rows()).toEqual([])
  })
  it('rejects duplicate actions while the first is pending, and cannot confirm without a reviewed server record', async () => {
    const s = await setup(), review = await s.session().prepare(s.saved.id), gate = deferred<PdfContentRecord>()
    vi.mocked(s.client.upload).mockReturnValueOnce(gate.promise)
    const uploading = review.upload(); await vi.waitFor(() => expect(s.client.upload).toHaveBeenCalledTimes(1))
    await expect(review.upload()).rejects.toThrow(pdfBindingErrors.busy); await expect(review.confirm()).rejects.toThrow(pdfBindingErrors.busy)
    gate.resolve(record()); await uploading; await review.confirm()
    const other = await s.session().prepare(s.saved.id); await expect(other.confirm()).rejects.toThrow(pdfBindingErrors.stale)
  })
  it('rejects foreign, missing, corrupt and mismatched server records without binding', async () => {
    for (const failure of [new PdfContentError('not-found'), new PdfContentError('unavailable')]) {
      const s = await setup(), review = await s.session().prepare(s.saved.id); vi.mocked(s.client.get).mockRejectedValue(failure)
      await expect(review.compare(recordId)).rejects.toBe(failure); expect(await s.rows()).toEqual([])
    }
    const s = await setup(), review = await s.session().prepare(s.saved.id), changed = record(); changed.proof.language = 'EN'
    vi.mocked(s.client.get).mockResolvedValue(changed); await expect(review.compare(recordId)).rejects.toMatchObject({ code: 'mismatch' }); expect(await s.rows()).toEqual([])
  })
  it('rejects source changes and deletion during the server GET and final confirmation', async () => {
    for (const remove of [false, true]) {
      const s = await setup(), review = await s.session().prepare(s.saved.id); await review.compare(recordId)
      const gate = deferred<PdfContentRecord>(); vi.mocked(s.client.get).mockReturnValueOnce(gate.promise)
      const confirming = review.confirm(); await vi.waitFor(() => expect(s.client.get).toHaveBeenCalledTimes(2))
      await readingTransaction(['exchanges'], 'readwrite', (tx, done) => { const store = tx.objectStore('exchanges'), r = store.get(['reader', s.saved.id]); r.onsuccess = () => { if (remove) store.delete(['reader', s.saved.id]); else { r.result.assets[0].bytes[0] ^= 1; store.put(r.result) }; done(undefined) } }, s.options)
      gate.resolve(record()); await expect(confirming).rejects.toThrow(pdfBindingErrors.stale); expect(await s.rows()).toEqual([])
    }
  })
  it('aborts an in-flight binding transaction when the panel closes after put but before commit', async () => {
    const s = await setup(), session = s.session(), review = await session.prepare(s.saved.id); await review.compare(recordId)
    const original = IDBObjectStore.prototype.put
    const put = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, ...args) {
      const request = original.apply(this, args); if (this.name === 'pdfContentBindings') session.close(); return request
    })
    try { await expect(review.confirm()).rejects.toThrow(); expect(await s.rows()).toEqual([]) } finally { put.mockRestore() }
  })
  it('refuses an earlier review after another session has linked the copy', async () => {
    const s = await setup(), review = await s.session().prepare(s.saved.id); await review.compare(recordId)
    // The second session wins after both reviewed absence. The first must never overwrite its binding.
    const other = await s.session().prepare(s.saved.id); await other.compare(recordId); await other.confirm()
    const expected = await s.rows(); await expect(review.confirm()).rejects.toThrow(pdfBindingErrors.stale); expect(await s.rows()).toEqual(expected)
  })
  it('rejects a binding writer queued between the final readonly check and the atomic commit', async () => {
    const s = await setup(), review = await s.session().prepare(s.saved.id); await review.compare(recordId)
    const tombstone = { version: 1, username: 'reader', origin: 'https://example.test', copyId: s.saved.id, nonce: crypto.randomUUID(), link: null }
    const original = IDBDatabase.prototype.transaction; let injected = false
    const transactions = vi.spyOn(IDBDatabase.prototype, 'transaction').mockImplementation(function (this: IDBDatabase, ...args) {
      if (!injected && args[1] === 'readwrite' && Array.from(args[0]).includes('pdfContentBindings')) {
        injected = true
        const writer = original.call(this, ['pdfContentBindings'], 'readwrite'); writer.objectStore('pdfContentBindings').put(tombstone)
      }
      return original.apply(this, args)
    })
    try { await expect(review.confirm()).rejects.toThrow(pdfBindingErrors.stale); expect(injected).toBe(true); expect(await s.rows()).toEqual([tombstone]) } finally { transactions.mockRestore() }
  })
  it('keeps an unlink tombstone so absent → linked → unlinked cannot authorize an old review', async () => {
    const s = await setup(), first = await s.session().prepare(s.saved.id), second = await s.session().prepare(s.saved.id)
    await first.compare(recordId); await second.compare(recordId); await second.confirm()
    await (await s.session().prepare(s.saved.id)).unlink(); expect(await s.rows()).toMatchObject([{ link: null, nonce: expect.any(String) }])
    await expect(first.confirm()).rejects.toThrow(pdfBindingErrors.stale)
    await expect((await s.session().prepare(s.saved.id)).open()).rejects.toThrow(pdfBindingErrors.unlinked)
    expect((await s.library.list())[0]).toEqual(s.saved)
  })
  it('separates accounts and origins and requires a current client only for server actions', async () => {
    const s = await setup(), review = await s.session().prepare(s.saved.id); await review.compare(recordId); await review.confirm()
    const elsewhere = createPortablePdfSession({ username: 'reader', origin: 'https://other.test', client: s.client, current: () => true, ...s.options })
    expect((await elsewhere.prepare(s.saved.id)).binding).toBeNull()
    const otherAccount = createPortablePdfSession({ username: 'other', origin: 'https://example.test', client: s.client, current: () => true, ...s.options })
    await expect(otherAccount.prepare(s.saved.id)).rejects.toMatchObject({ code: 'not-found' })
    const offline = createPortablePdfSession({ username: 'reader', origin: 'https://example.test', client: null, current: () => true, ...s.options })
    await expect((await offline.prepare(s.saved.id)).open()).rejects.toThrow(pdfBindingErrors.offline)
    await (await offline.prepare(s.saved.id)).unlink(); expect(await s.rows()).toMatchObject([{ link: null }])
  })
  it('discards late responses after A → B → A, client replacement or panel close', async () => {
    for (const close of [false, true]) {
      const s = await setup(), session = s.session(), review = await session.prepare(s.saved.id), gate = deferred<PdfContentRecord>()
      vi.mocked(s.client.get).mockReturnValueOnce(gate.promise); const pending = review.compare(recordId)
      await vi.waitFor(() => expect(s.client.get).toHaveBeenCalledTimes(1)); if (close) session.close(); else { s.changeSession(); s.changeSession() }
      gate.resolve(record()); await expect(pending).rejects.toMatchObject({ code: 'aborted' }); expect(await s.rows()).toEqual([])
      await expect(review.confirm()).rejects.toMatchObject({ code: 'aborted' })
    }
  })
  it('rechecks server bytes before linked reads and rejects later remote corruption', async () => {
    const s = await setup(), review = await s.session().prepare(s.saved.id); await review.compare(recordId); await review.confirm()
    vi.mocked(s.client.get).mockRejectedValue(new PdfContentError('unavailable'))
    await expect((await s.session().prepare(s.saved.id)).open()).rejects.toMatchObject({ code: 'unavailable' })
    expect((await s.library.list())[0]).toEqual(s.saved)
  })
  it('retains a received upload record for recovery if verification fails and allows retrying a failed linked read', async () => {
    const s = await setup(), review = await s.session().prepare(s.saved.id)
    vi.mocked(s.client.get).mockRejectedValueOnce(new PdfContentError('network'))
    await expect(review.upload()).rejects.toMatchObject({ code: 'network' }); expect(review.uploadedReceipt?.recordId).toBe(recordId)
    await review.upload(); await review.confirm()
    const linked = await s.session().prepare(s.saved.id); vi.mocked(s.client.get).mockRejectedValueOnce(new PdfContentError('network'))
    await expect(linked.open()).rejects.toMatchObject({ code: 'network' }); expect((await linked.open()).id).toBe(s.saved.id)
  })
})
