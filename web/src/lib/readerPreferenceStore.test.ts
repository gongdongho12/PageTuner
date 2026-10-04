import { IDBFactory } from 'fake-indexeddb'
import { describe, expect, it, vi } from 'vitest'
import { createReaderPreferenceStore } from './readerPreferenceStore'
import type { SharedReaderPreferences } from './readerPreferenceApi'

const preferences: SharedReaderPreferences = { fontSize: 20, lineHeightPercent: 160, pageMargin: 16, touchDirection: 'left-previous', listMode: 'paged' }
describe('atomic preference storage', () => {
  it('isolates accounts and restores existing data without applying another fallback', async () => {
    const indexedDB = new IDBFactory(), a = createReaderPreferenceStore('alice', { indexedDB }), b = createReaderPreferenceStore('bob', { indexedDB })
    await a.initialize(preferences); await a.transact(row => ({ ...row, local: { ...row.local, fontSize: 30 } })); a.close()
    const reopened = createReaderPreferenceStore('alice', { indexedDB })
    expect((await reopened.initialize(preferences)).local.fontSize).toBe(30)
    expect((await b.initialize(preferences)).local.fontSize).toBe(20)
    reopened.close(); b.close()
  })
  it('serializes two independent tab reducers and notifies sibling controllers', async () => {
    const indexedDB = new IDBFactory(), a = createReaderPreferenceStore('alice', { indexedDB }), b = createReaderPreferenceStore('alice', { indexedDB })
    await a.initialize(preferences); await b.initialize(preferences)
    const listener = vi.fn(), unsubscribe = b.subscribe(listener)
    await Promise.all([a.transact(row => ({ ...row, local: { ...row.local, fontSize: 24 } })), b.transact(row => ({ ...row, local: { ...row.local, pageMargin: 30 } }))])
    expect((await a.snapshot())?.local).toMatchObject({ fontSize: 24, pageMargin: 30 }); expect(listener).toHaveBeenCalled()
    unsubscribe(); a.close(); b.close()
  })
  it('does not reset damaged persisted data', async () => {
    const indexedDB = new IDBFactory(), dbName = 'damaged', store = createReaderPreferenceStore('alice', { indexedDB, dbName })
    await store.initialize(preferences); store.close()
    const db = await new Promise<IDBDatabase>((resolve, reject) => { const r = indexedDB.open(dbName); r.onsuccess = () => resolve(r.result); r.onerror = reject })
    await new Promise<void>((resolve, reject) => { const tx = db.transaction('accounts', 'readwrite'); tx.objectStore('accounts').put({ damaged: true }, 'alice'); tx.oncomplete = () => resolve(); tx.onerror = reject })
    const reopened = createReaderPreferenceStore('alice', { indexedDB, dbName })
    await expect(reopened.initialize(preferences)).rejects.toMatchObject({ code: 'storage' })
    const raw = await new Promise<unknown>((resolve, reject) => { const r = db.transaction('accounts').objectStore('accounts').get('alice'); r.onsuccess = () => resolve(r.result); r.onerror = reject })
    expect(raw).toEqual({ damaged: true }); reopened.close(); db.close()
  })
})
