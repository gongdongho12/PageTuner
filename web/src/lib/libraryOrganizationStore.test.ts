import { IDBFactory } from 'fake-indexeddb'
import { describe, expect, it, vi } from 'vitest'
import { createLibraryOrganizationStore, listLibraryOrganizationRecords, type LibraryOrganizationRecord } from './libraryOrganizationStore'
import type { LibraryOrganization, LibraryOrganizationScope } from './libraryOrganizationApi'

const local: LibraryOrganization = { folder: 'Shelf', tags: ['Novel'], favorite: true }
const scope: LibraryOrganizationScope = { kind: 'ORIGINAL', recordId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' }
const journal = (): LibraryOrganizationRecord => ({ enabled: false, local: structuredClone(local), remote: null, pending: null, queued: null, conflict: null, retryAfterUntil: null })
describe('atomic organization storage', () => {
  it('isolates accounts, kinds and UUIDs while enumerating only the signed-in account', async () => {
    const indexedDB = new IDBFactory()
    const a = createLibraryOrganizationStore('alice', scope, { indexedDB }), b = createLibraryOrganizationStore('bob', scope, { indexedDB })
    const translation = createLibraryOrganizationStore('alice', { ...scope, kind: 'TRANSLATION' }, { indexedDB })
    const other = createLibraryOrganizationStore('alice', { ...scope, recordId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' }, { indexedDB })
    await a.initialize(local); await a.transact(row => ({ ...row, local: { ...local, folder: 'Changed' } }))
    await b.initialize(local); await translation.initialize({ ...local, folder: 'Translation' }); await other.initialize({ ...local, folder: 'Other' })
    const rows = await listLibraryOrganizationRecords('alice', { indexedDB })
    expect(rows).toHaveLength(3); expect(rows.map(row => row.record.local.folder).sort()).toEqual(['Changed', 'Other', 'Translation'])
    expect((await b.snapshot())?.local.folder).toBe('Shelf')
    a.close(); b.close(); translation.close(); other.close()
    const reopened = createLibraryOrganizationStore('alice', { ...scope, recordId: scope.recordId.toUpperCase() }, { indexedDB })
    expect((await reopened.initialize(local)).local.folder).toBe('Changed'); reopened.close()
  })
  it('serializes independent tab reducers and notifies only the matching scope', async () => {
    const indexedDB = new IDBFactory(), a = createLibraryOrganizationStore('alice', scope, { indexedDB }), b = createLibraryOrganizationStore('alice', scope, { indexedDB })
    const other = createLibraryOrganizationStore('alice', { ...scope, kind: 'TRANSLATION' }, { indexedDB })
    await a.initialize(local); await b.initialize(local); await other.initialize(local)
    const listener = vi.fn(), unrelated = vi.fn(), unsubscribe = b.subscribe(listener), stop = other.subscribe(unrelated)
    await Promise.all([a.transact(row => ({ ...row, local: { ...row.local, folder: 'New' } })), b.transact(row => ({ ...row, local: { ...row.local, favorite: false } }))])
    expect((await a.snapshot())?.local).toEqual({ ...local, folder: 'New', favorite: false }); expect(listener).toHaveBeenCalled(); expect(unrelated).not.toHaveBeenCalled()
    unsubscribe(); stop(); a.close(); b.close(); other.close()
  })
  it('enumerates durable outboxes after an editor closes without creating defaults for other documents', async () => {
    const indexedDB = new IDBFactory(), a = createLibraryOrganizationStore('alice', scope, { indexedDB })
    await a.initialize(local)
    await a.transact(row => ({ ...row, enabled: true, remote: { ...scope, version: 0, organization: null, updatedAt: null }, pending: { expectedVersion: 0, mutationId: crypto.randomUUID(), organization: local } }))
    const before = await a.snapshot(); a.close()
    expect(await listLibraryOrganizationRecords('alice', { indexedDB })).toEqual([{ scope, record: before }])
    expect(await listLibraryOrganizationRecords('bob', { indexedDB })).toEqual([])
  })
  it('refuses damaged journal data rather than silently resetting it', async () => {
    const indexedDB = new IDBFactory(), dbName = 'damaged', store = createLibraryOrganizationStore('alice', scope, { indexedDB, dbName })
    await store.initialize(local); store.close()
    const db = await new Promise<IDBDatabase>((resolve, reject) => { const r = indexedDB.open(dbName); r.onsuccess = () => resolve(r.result); r.onerror = reject })
    const damaged = { username: 'alice', scope, record: { damaged: true } }, key = ['alice', scope.kind, scope.recordId]
    await new Promise<void>((resolve, reject) => { const tx = db.transaction('records', 'readwrite'); tx.objectStore('records').put(damaged, key); tx.oncomplete = () => resolve(); tx.onerror = reject })
    const reopened = createLibraryOrganizationStore('alice', scope, { indexedDB, dbName })
    await expect(reopened.initialize(local)).rejects.toMatchObject({ code: 'storage' })
    await expect(listLibraryOrganizationRecords('alice', { indexedDB, dbName })).rejects.toMatchObject({ code: 'storage' })
    const raw = await new Promise<unknown>((resolve, reject) => { const r = db.transaction('records').objectStore('records').get(key); r.onsuccess = () => resolve(r.result); r.onerror = reject })
    expect(raw).toEqual(damaged); reopened.close(); db.close()
  })
  it('rejects a remote view belonging to a different document and leaves the current journal intact', async () => {
    const indexedDB = new IDBFactory(), store = createLibraryOrganizationStore('alice', scope, { indexedDB })
    const before = await store.initialize(local)
    await expect(store.transact(row => ({ ...row, remote: { ...scope, kind: 'TRANSLATION', version: 0, organization: null, updatedAt: null } }))).rejects.toMatchObject({ code: 'storage' })
    expect(await store.snapshot()).toEqual(before); store.close()
  })
  it('keeps readonly missing journals absent, then atomically compares null across independent tabs', async () => {
    const indexedDB = new IDBFactory(), a = createLibraryOrganizationStore('alice', scope, { indexedDB }), b = createLibraryOrganizationStore('alice', scope, { indexedDB })
    expect(await a.snapshot()).toBeNull(); expect(await b.snapshot()).toBeNull()
    expect(await listLibraryOrganizationRecords('alice', { indexedDB })).toEqual([])
    const stale = new Error('Reviewed journal changed')
    const adopt = (folder: string) => (current: LibraryOrganizationRecord | null) => {
      if (current !== null) throw stale
      return { ...journal(), local: { ...local, folder } }
    }
    const outcomes = await Promise.allSettled([a.transactNullable(adopt('First')), b.transactNullable(adopt('Second'))])
    expect(outcomes.filter(result => result.status === 'fulfilled')).toHaveLength(1)
    expect(outcomes.find(result => result.status === 'rejected')).toMatchObject({ reason: stale })
    const winner = outcomes.find(result => result.status === 'fulfilled') as PromiseFulfilledResult<LibraryOrganizationRecord>
    expect(await a.snapshot()).toEqual(winner.value); expect(await b.snapshot()).toEqual(winner.value)
    a.close(); b.close()
  })
  it('preserves caller rejections and rolls back reducer changes to a detached record', async () => {
    const indexedDB = new IDBFactory(), store = createLibraryOrganizationStore('alice', scope, { indexedDB })
    const before = await store.initialize(local), rejection = new Error('Explicit adoption was cancelled'), listener = vi.fn()
    store.subscribe(listener)
    await expect(store.transactNullable(current => { current!.local.tags.push('Temporary'); throw rejection })).rejects.toBe(rejection)
    expect(await store.snapshot()).toEqual(before); expect(listener).not.toHaveBeenCalled()
    const candidate = journal(), saved = await store.transactNullable(() => candidate)
    candidate.local.tags.push('Input alias'); saved.local.tags.push('Output alias')
    expect((await store.snapshot())?.local.tags).toEqual(['Novel']); store.close()
  })
  it('rejects an async or invalid reducer without initializing the missing journal', async () => {
    const indexedDB = new IDBFactory(), store = createLibraryOrganizationStore('alice', scope, { indexedDB })
    const asynchronous = (() => Promise.resolve(journal())) as unknown as (current: LibraryOrganizationRecord | null) => LibraryOrganizationRecord
    await expect(store.transactNullable(asynchronous)).rejects.toMatchObject({ code: 'storage' })
    expect(await store.snapshot()).toBeNull()
    await expect(store.transactNullable(() => ({ ...journal(), remote: { ...scope, kind: 'TRANSLATION', version: 0, organization: null, updatedAt: null } }))).rejects.toMatchObject({ code: 'storage' })
    expect(await store.snapshot()).toBeNull(); store.close()
  })
  it('guards the nullable review before opening and after the synchronous reducer, rolling back on session change', async () => {
    const indexedDB = new IDBFactory(), store = createLibraryOrganizationStore('alice', scope, { indexedDB })
    const stale = new Error('Session changed'), reducer = vi.fn(journal)
    await expect(store.transactNullable(reducer, () => { throw stale })).rejects.toBe(stale)
    expect(reducer).not.toHaveBeenCalled(); expect(await store.snapshot()).toBeNull()
    let current = true
    await expect(store.transactNullable(() => { current = false; return journal() }, () => { if (!current) throw stale })).rejects.toBe(stale)
    expect(await store.snapshot()).toBeNull(); store.close()
  })
  it('can still abort after put succeeds, before the transaction commits', async () => {
    const indexedDB = new IDBFactory(), store = createLibraryOrganizationStore('alice', scope, { indexedDB })
    const stale = new Error('Session expired at final request'), listener = vi.fn()
    let checks = 0
    store.subscribe(listener)
    await expect(store.transactNullable(journal, () => { if (++checks === 5) throw stale })).rejects.toBe(stale)
    expect(checks).toBe(5); expect(await store.snapshot()).toBeNull(); expect(listener).not.toHaveBeenCalled(); store.close()
  })
  it('aborts an opening write on close and never invokes its reducer', async () => {
    const indexedDB = new IDBFactory(), store = createLibraryOrganizationStore('alice', scope, { indexedDB }), reducer = vi.fn(journal)
    const update = store.transactNullable(reducer); store.close()
    await expect(update).rejects.toMatchObject({ code: 'storage' }); expect(reducer).not.toHaveBeenCalled()
    const reopened = createLibraryOrganizationStore('alice', scope, { indexedDB })
    expect(await reopened.snapshot()).toBeNull(); reopened.close()
  })
  it('aborts an already queued write on close before its get callback can run', async () => {
    const indexedDB = new IDBFactory(), store = createLibraryOrganizationStore('alice', scope, { indexedDB }), reducer = vi.fn(journal)
    await store.snapshot()
    let checks = 0
    const update = store.transactNullable(reducer, () => { if (++checks === 2) queueMicrotask(() => store.close()) })
    await expect(update).rejects.toMatchObject({ code: 'storage' }); expect(reducer).not.toHaveBeenCalled()
    const reopened = createLibraryOrganizationStore('alice', scope, { indexedDB })
    expect(await reopened.snapshot()).toBeNull(); reopened.close()
  })
  it('aborts an active write when its reducer closes the store', async () => {
    const indexedDB = new IDBFactory(), store = createLibraryOrganizationStore('alice', scope, { indexedDB })
    const before = await store.initialize(local)
    await expect(store.transactNullable(row => { store.close(); return { ...row!, local: { ...local, folder: 'Must roll back' } } })).rejects.toMatchObject({ code: 'storage' })
    const reopened = createLibraryOrganizationStore('alice', scope, { indexedDB })
    expect(await reopened.snapshot()).toEqual(before); reopened.close()
  })
  it('reports committed success even if a notification listener throws or closes its store', async () => {
    const indexedDB = new IDBFactory(), store = createLibraryOrganizationStore('alice', scope, { indexedDB })
    store.subscribe(() => { throw new Error('Subscriber failure') }); store.subscribe(() => store.close())
    await expect(store.transactNullable(journal)).resolves.toEqual(journal())
    const reopened = createLibraryOrganizationStore('alice', scope, { indexedDB })
    expect(await reopened.snapshot()).toEqual(journal()); reopened.close()
  })
})

