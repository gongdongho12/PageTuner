import { IDBFactory } from 'fake-indexeddb'
import { describe, expect, it, vi } from 'vitest'
import { createLibraryOrganizationStore, listLibraryOrganizationRecords } from './libraryOrganizationStore'
import type { LibraryOrganization, LibraryOrganizationScope } from './libraryOrganizationApi'

const local: LibraryOrganization = { folder: 'Shelf', tags: ['Novel'], favorite: true }
const scope: LibraryOrganizationScope = { kind: 'ORIGINAL', recordId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' }
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
})

