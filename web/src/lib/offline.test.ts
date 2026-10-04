import { webcrypto } from 'node:crypto'
import { IDBFactory, IDBObjectStore } from 'fake-indexeddb'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import fixture from '../../../contracts/fixtures/translation-v1/stored-response.json'
import { createOfflineLibrary, type OfflineLibrary } from './offline'
import { sha256, validateTranslation } from './validation'
import type { TranslationResponse } from './types'

beforeAll(() => { vi.stubGlobal('crypto', webcrypto) })
afterEach(() => { vi.restoreAllMocks() })
const record = (): TranslationResponse => JSON.parse(JSON.stringify(fixture)) as TranslationResponse
const libraries: OfflineLibrary[] = []
afterEach(() => { libraries.splice(0).forEach(library => library.close()) })

function library(username: string, indexedDB: IDBFactory, name = 'test-books') {
  const value = createOfflineLibrary(username, { indexedDB, dbName: name, now: () => new Date('2026-09-14T01:00:00Z') })
  libraries.push(value)
  return value
}

async function rawDatabase(factory: IDBFactory, name = 'test-books'): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = factory.open(name, 1)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

async function tamper(factory: IDBFactory, update: (value: Record<string, unknown>) => void): Promise<void> {
  const db = await rawDatabase(factory)
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction('books', 'readwrite')
    const store = tx.objectStore('books')
    const request = store.get(['alice', fixture.recordId])
    request.onsuccess = () => { update(request.result as Record<string, unknown>); store.put(request.result) }
    tx.oncomplete = () => resolve()
    tx.onabort = () => reject(tx.error)
  })
  db.close()
}

describe('validated account-scoped offline library', () => {
  it('persists a complete validated book and paragraph anchor across reopening', async () => {
    const factory = new IDBFactory()
    const first = library('alice', factory)
    const saved = await first.save(record())
    expect(saved.savedAt).toBe('2026-09-14T01:00:00.000Z')
    await first.setAnchor(fixture.recordId, { paragraphId: 'p-2', characterOffset: 3 })
    first.close()
    const reopened = library('alice', factory)
    expect(await reopened.get(fixture.recordId)).toMatchObject({ translation: fixture, anchor: { paragraphId: 'p-2', characterOffset: 3 } })
    expect((await reopened.list()).books).toHaveLength(1)
    await reopened.save({ ...record(), created: false })
    expect((await reopened.get(fixture.recordId))?.anchor).toEqual({ paragraphId: 'p-2', characterOffset: 3 })
  })

  it('isolates identical record IDs and progress between account names', async () => {
    const factory = new IDBFactory()
    const alice = library('alice', factory)
    const bob = library('bob', factory)
    await alice.save(record())
    expect(await bob.list()).toEqual({ books: [], corruptRecords: [] })
    expect(await bob.get(fixture.recordId)).toBeUndefined()
    await bob.save(record())
    await alice.setAnchor(fixture.recordId, { paragraphId: 'p-1', characterOffset: 4 })
    expect((await bob.get(fixture.recordId))?.anchor).toBeUndefined()
    await alice.remove(fixture.recordId)
    expect(await alice.list()).toEqual({ books: [], corruptRecords: [] })
    expect((await bob.list()).books).toHaveLength(1)
  })

  it('stores only whitelisted translation, progress and account namespace fields', async () => {
    const factory = new IDBFactory()
    const books = library('alice', factory)
    await expect(books.save({ ...record(), password: 'private-secret' } as TranslationResponse)).rejects.toMatchObject({ kind: 'corrupt' })
    await books.save(record())
    const db = await rawDatabase(factory)
    const all = await new Promise<unknown[]>(resolve => {
      const request = db.transaction('books', 'readonly').objectStore('books').getAll()
      request.onsuccess = () => resolve(request.result as unknown[])
    })
    db.close()
    expect(Object.keys(all[0] as object).sort()).toEqual(['book', 'recordId', 'revision', 'username'])
    expect(JSON.stringify(all)).not.toMatch(/password|Authorization|private-secret/)
  })

  it('rejects a different valid revision for an existing immutable record ID', async () => {
    const factory = new IDBFactory()
    const books = library('alice', factory)
    await books.save(record())
    const changed = record()
    changed.paragraphs[0].text = '다른 번역'
    changed.payloadHash = await sha256(changed.paragraphs.map(item => `${item.paragraphId}:${item.text}`).join('\n'))
    changed.revision = await sha256(`${changed.artifactId}|${changed.payloadHash}`)
    await validateTranslation(changed)
    await expect(books.save(changed)).rejects.toMatchObject({ kind: 'conflict' })
    expect((await books.get(fixture.recordId))?.translation).toEqual(fixture)
  })

  it('detects corrupted payloads and mismatched revision tags on read', async () => {
    for (const mutation of [
      (raw: Record<string, unknown>) => { ((raw.book as { translation: TranslationResponse }).translation.paragraphs[0]).text = 'tampered' },
      (raw: Record<string, unknown>) => { raw.revision = 'f'.repeat(64) },
    ]) {
      const factory = new IDBFactory()
      const books = library('alice', factory)
      await books.save(record())
      await tamper(factory, mutation)
      await expect(books.get(fixture.recordId)).rejects.toMatchObject({ kind: 'corrupt' })
      const snapshot = await books.list()
      expect(snapshot.books).toEqual([])
      expect(snapshot.corruptRecords).toMatchObject([{ recordId: fixture.recordId, reason: 'corrupt' }])
      await books.removeCorrupt(snapshot.corruptRecords[0].recoveryId)
      expect(await books.list()).toEqual({ books: [], corruptRecords: [] })
    }
  })

  it('validates paragraph identity and offset without any page number dependency', async () => {
    const factory = new IDBFactory()
    const books = library('alice', factory)
    await books.save(record())
    for (const anchor of [
      { paragraphId: 'missing', characterOffset: 0 },
      { paragraphId: 'p-1', characterOffset: -1 },
      { paragraphId: 'p-1', characterOffset: 1000 },
      { paragraphId: 'p-1', characterOffset: 1.5 },
    ]) await expect(books.setAnchor(fixture.recordId, anchor)).rejects.toMatchObject({ kind: 'invalid-anchor' })
    await books.setAnchor(fixture.recordId, { paragraphId: 'p-1', characterOffset: fixture.paragraphs[0].text.length })
    expect((await books.get(fixture.recordId))?.anchor?.paragraphId).toBe('p-1')
  })

  it('reports quota and unavailable storage without falsely reporting a successful save', async () => {
    const factory = new IDBFactory()
    const books = library('alice', factory)
    await books.list()
    const put = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(() => { throw new DOMException('No room', 'QuotaExceededError') })
    await expect(books.save(record())).rejects.toMatchObject({ kind: 'quota' })
    put.mockRestore()
    expect(await books.list()).toEqual({ books: [], corruptRecords: [] })
    const broken = library('alice', { open: () => { throw new DOMException('Denied', 'SecurityError') } } as unknown as IDBFactory, 'broken')
    await expect(broken.list()).rejects.toMatchObject({ kind: 'unavailable' })
  })

  it('serializes concurrent anchor writes and re-saves without losing saved progress', async () => {
    const factory = new IDBFactory()
    const first = library('alice', factory)
    const second = library('alice', factory)
    await first.save(record())
    await first.setAnchor(fixture.recordId, { paragraphId: 'p-1', characterOffset: 2 })
    await Promise.all([second.save(record()), first.setAnchor(fixture.recordId, { paragraphId: 'p-2', characterOffset: 1 })])
    expect((await second.get(fixture.recordId))?.anchor).toEqual({ paragraphId: 'p-2', characterOffset: 1 })
  })

  it('keeps healthy books visible and preserves damaged records until explicit recovery', async () => {
    const factory = new IDBFactory()
    const books = library('alice', factory)
    const healthy = { ...record(), recordId: '00000000-0000-0000-0000-000000000002' }
    await books.save(record())
    await books.save(healthy)
    await tamper(factory, raw => { (raw.book as { translation: TranslationResponse }).translation.paragraphs[0].text = 'damaged' })

    const snapshot = await books.list()
    expect(snapshot.books.map(book => book.translation.recordId)).toEqual([healthy.recordId])
    expect(snapshot.corruptRecords).toMatchObject([{ recordId: fixture.recordId, reason: 'corrupt' }])
    await expect(books.get(fixture.recordId)).rejects.toMatchObject({ kind: 'corrupt' })
    await expect(books.save(record())).rejects.toMatchObject({ kind: 'conflict' })
    // Listing does not silently delete, overwrite or relabel the damaged data.
    expect((await books.list()).corruptRecords).toHaveLength(1)
    await books.replaceCorrupt(snapshot.corruptRecords[0].recoveryId, record())
    expect((await books.list()).books).toHaveLength(2)
    expect((await books.list()).corruptRecords).toEqual([])
  })

  it('uses the real scoped storage key to remove a damaged identifier without touching other accounts', async () => {
    const factory = new IDBFactory()
    const alice = library('alice', factory)
    const bob = library('bob', factory)
    await alice.save(record())
    await bob.save(record())
    const db = await rawDatabase(factory)
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction('books', 'readwrite')
      const store = tx.objectStore('books')
      const request = store.get(['alice', fixture.recordId])
      request.onsuccess = () => {
        const damaged = request.result as Record<string, unknown>
        store.delete(['alice', fixture.recordId])
        damaged.recordId = 42
        store.put(damaged)
      }
      tx.oncomplete = () => resolve()
      tx.onabort = () => reject(tx.error)
    })
    db.close()
    const snapshot = await alice.list()
    expect(snapshot.books).toEqual([])
    expect(snapshot.corruptRecords).toMatchObject([{ recordId: null, reason: 'corrupt' }])
    await expect(bob.removeCorrupt(snapshot.corruptRecords[0].recoveryId)).rejects.toMatchObject({ kind: 'not-found' })
    expect((await bob.list()).books).toHaveLength(1)
    await alice.removeCorrupt(snapshot.corruptRecords[0].recoveryId)
    expect(await alice.list()).toEqual({ books: [], corruptRecords: [] })
    expect((await bob.list()).books).toHaveLength(1)
  })

  it('retries opening after a temporary storage failure with the same library instance', async () => {
    const factory = new IDBFactory()
    let attempts = 0
    const flaky = {
      open(name: string, version?: number) {
        if (++attempts === 1) throw new DOMException('Temporarily denied', 'SecurityError')
        return factory.open(name, version)
      },
    } as unknown as IDBFactory
    const books = library('alice', flaky)
    await expect(books.list()).rejects.toMatchObject({ kind: 'unavailable' })
    expect(await books.list()).toEqual({ books: [], corruptRecords: [] })
    expect(attempts).toBe(2)
  })

  it('keeps damaged data after a failed repair and rejects replacing a concurrently repaired record', async () => {
    const factory = new IDBFactory()
    const books = library('alice', factory)
    await books.save(record())
    await tamper(factory, raw => { raw.revision = 'damaged-revision' })
    const original = (await books.list()).corruptRecords[0]
    const put = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(() => { throw new DOMException('No room', 'QuotaExceededError') })
    await expect(books.replaceCorrupt(original.recoveryId, record())).rejects.toMatchObject({ kind: 'quota' })
    put.mockRestore()
    expect((await books.list()).corruptRecords).toHaveLength(1)

    const other = library('alice', factory)
    const second = (await other.list()).corruptRecords[0]
    await other.replaceCorrupt(second.recoveryId, record())
    await expect(books.replaceCorrupt(original.recoveryId, record())).rejects.toMatchObject({ kind: 'conflict' })
    expect((await books.get(fixture.recordId))?.translation).toEqual(fixture)
    expect((await books.list()).corruptRecords).toEqual([])
  })

  it('rejects deleting a damaged record that another library instance has repaired', async () => {
    const factory = new IDBFactory()
    const books = library('alice', factory)
    await books.save(record())
    await tamper(factory, raw => { raw.revision = 'damaged-revision' })
    const original = (await books.list()).corruptRecords[0]

    const other = library('alice', factory)
    const second = (await other.list()).corruptRecords[0]
    await other.replaceCorrupt(second.recoveryId, record())

    await expect(books.removeCorrupt(original.recoveryId)).rejects.toMatchObject({ kind: 'conflict' })
    expect((await books.get(fixture.recordId))?.translation).toEqual(fixture)
    expect((await books.list()).corruptRecords).toEqual([])
  })
})
