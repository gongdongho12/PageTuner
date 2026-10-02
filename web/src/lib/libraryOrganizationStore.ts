import { LibraryOrganizationError, sameLibraryOrganization, sameLibraryOrganizationScope, sameLibraryOrganizationView, validateLibraryOrganizationMutation, validateLibraryOrganization, validateLibraryOrganizationScope, validateLibraryOrganizationView, type LibraryOrganizationMutation, type LibraryOrganizationView, type LibraryOrganization, type LibraryOrganizationScope } from './libraryOrganizationApi'

export type LibraryOrganizationRecord = { enabled: boolean; local: LibraryOrganization; remote: LibraryOrganizationView | null; pending: LibraryOrganizationMutation | null; queued: LibraryOrganization | null; conflict: LibraryOrganizationView | null; retryAfterUntil: number | null }
export type LibraryOrganizationStoreOptions = { indexedDB?: IDBFactory; dbName?: string; databaseName?: string }
const listeners = new Map<string, Set<() => void>>()
const databaseDefault = 'pageturner-library-organization-sync'
function account(username: string) { if (!username.trim() || /[:\r\n]/.test(username)) throw new LibraryOrganizationError('invalid-request') }
function checked(value: unknown, scope: LibraryOrganizationScope): LibraryOrganizationRecord {
  try {
    const r = value as LibraryOrganizationRecord
    if (!r || typeof r !== 'object' || typeof r.enabled !== 'boolean' || r.retryAfterUntil !== null && (!Number.isSafeInteger(r.retryAfterUntil) || r.retryAfterUntil < 0)) throw new Error()
    const row = { enabled: r.enabled, local: validateLibraryOrganization(r.local), remote: r.remote === null ? null : validateLibraryOrganizationView(r.remote),
      pending: r.pending === null ? null : validateLibraryOrganizationMutation(r.pending), queued: r.queued === null ? null : validateLibraryOrganization(r.queued),
      conflict: r.conflict === null ? null : validateLibraryOrganizationView(r.conflict), retryAfterUntil: r.retryAfterUntil }
    if ((!row.enabled && (row.pending || row.queued || row.conflict)) || (!row.pending && (row.queued || row.conflict))) throw new Error()
    if (row.enabled && !row.remote || row.pending && (!sameLibraryOrganization(row.local, row.queued ?? row.pending.organization) || row.pending.expectedVersion > (row.remote?.version ?? 0)) ||
        row.conflict && !sameLibraryOrganizationView(row.conflict, row.remote) || row.remote && !sameLibraryOrganizationScope(scope, row.remote)) throw new Error()
    return row
  } catch { throw new LibraryOrganizationError('storage') }
}
function openDatabase(factory: IDBFactory | undefined, dbName: string): Promise<IDBDatabase> {
  if (!factory) return Promise.reject(new LibraryOrganizationError('storage'))
  return new Promise((resolve, reject) => {
    const request = factory.open(dbName, 1); let failed = false
    const fail = () => { failed = true; reject(new LibraryOrganizationError('storage')) }
    request.onupgradeneeded = () => { request.result.createObjectStore('records').createIndex('username', 'username') }
    request.onerror = fail; request.onblocked = fail
    request.onsuccess = () => {
      const db = request.result
      if (failed) { db.close(); return }
      db.onversionchange = () => db.close()
      resolve(db)
    }
  })
}
function stored(value: unknown, username: string, scope?: LibraryOrganizationScope): { scope: LibraryOrganizationScope; record: LibraryOrganizationRecord } {
  try {
    const entry = value as { username: string; scope: LibraryOrganizationScope; record: LibraryOrganizationRecord }
    const actualScope = validateLibraryOrganizationScope(entry.scope)
    if (entry.username !== username || scope && !sameLibraryOrganizationScope(actualScope, scope)) throw new Error()
    return { scope: actualScope, record: checked(entry.record, actualScope) }
  } catch { throw new LibraryOrganizationError('storage') }
}
/** Enumerates only this account's journals, so reconnect can recover outboxes whose editors are closed. */
export async function listLibraryOrganizationRecords(username: string, options: LibraryOrganizationStoreOptions = {}): Promise<{ scope: LibraryOrganizationScope; record: LibraryOrganizationRecord }[]> {
  account(username)
  const db = await openDatabase(options.indexedDB ?? globalThis.indexedDB, options.dbName ?? options.databaseName ?? databaseDefault)
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction('records'), request = tx.objectStore('records').index('username').getAll(username)
      let result: { scope: LibraryOrganizationScope; record: LibraryOrganizationRecord }[] = [], error: unknown
      request.onsuccess = () => { try { result = request.result.map(value => stored(value, username)) } catch (e) { error = e; tx.abort() } }
      tx.oncomplete = () => resolve(result)
      tx.onerror = tx.onabort = () => reject(error instanceof LibraryOrganizationError ? error : new LibraryOrganizationError('storage'))
    })
  } finally { db.close() }
}

/** Read/reduce/write is atomic across tabs; the key includes account, document kind and original UUID. */
export function createLibraryOrganizationStore(username: string, inputScope: LibraryOrganizationScope, options: LibraryOrganizationStoreOptions = {}) {
  account(username)
  const scope = validateLibraryOrganizationScope(inputScope), dbName = options.dbName ?? options.databaseName ?? databaseDefault
  const storageKey = [username, scope.kind, scope.recordId], key = JSON.stringify([dbName, ...storageKey])
  const factory = options.indexedDB ?? globalThis.indexedDB
  let database: Promise<IDBDatabase> | undefined, closed = false
  const ownListeners = new Set<() => void>()
  const channel = typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('pageturner-library-organization-changes') : null
  function emit() { listeners.get(key)?.forEach(listener => listener()) }
  if (channel) channel.onmessage = event => { if (event.data === key) emit() }
  function notify() { emit(); channel?.postMessage(key) }
  function open(): Promise<IDBDatabase> {
    if (closed) return Promise.reject(new LibraryOrganizationError('storage'))
    if (!database) database = openDatabase(factory, dbName).then(db => {
      if (closed) { db.close(); throw new LibraryOrganizationError('storage') }
      db.onversionchange = () => { db.close(); database = undefined }
      return db
    }).catch(error => { database = undefined; throw error })
    return database
  }
  async function transaction(reducer?: (record: LibraryOrganizationRecord | null) => LibraryOrganizationRecord): Promise<LibraryOrganizationRecord | null> {
    const db = await open()
    if (closed) throw new LibraryOrganizationError('storage')
    let changed = false
    const result = await new Promise<LibraryOrganizationRecord | null>((resolve, reject) => {
      const tx = db.transaction('records', reducer ? 'readwrite' : 'readonly'), store = tx.objectStore('records'), request = store.get(storageKey)
      let result: LibraryOrganizationRecord | null = null, error: unknown
      request.onsuccess = () => {
        try {
          const current = request.result === undefined ? null : stored(request.result, username, scope).record
          result = reducer ? checked(reducer(current ? structuredClone(current) : null), scope) : current
          if (reducer && JSON.stringify(current) !== JSON.stringify(result)) { store.put({ username, scope, record: result }, storageKey); changed = true }
        } catch (failure) { error = failure; tx.abort() }
      }
      tx.oncomplete = () => resolve(result)
      tx.onerror = tx.onabort = () => reject(error instanceof LibraryOrganizationError ? error : new LibraryOrganizationError('storage'))
    })
    if (changed && !closed) notify()
    return result
  }
  return {
    scope,
    async initialize(fallback: LibraryOrganization) {
      const local = validateLibraryOrganization(fallback)
      return (await transaction(current => current ?? { enabled: false, local, remote: null, pending: null, queued: null, conflict: null, retryAfterUntil: null }))!
    },
    snapshot: () => transaction(),
    async transact(reducer: (record: LibraryOrganizationRecord) => LibraryOrganizationRecord) {
      return (await transaction(current => { if (!current) throw new LibraryOrganizationError('storage'); return reducer(current) }))!
    },
    subscribe(listener: () => void) {
      if (!closed) { ownListeners.add(listener); const group = listeners.get(key) ?? new Set(); group.add(listener); listeners.set(key, group) }
      return () => { ownListeners.delete(listener); listeners.get(key)?.delete(listener); if (!listeners.get(key)?.size) listeners.delete(key) }
    },
    close() { closed = true; channel?.close(); ownListeners.forEach(listener => listeners.get(key)?.delete(listener)); ownListeners.clear(); if (!listeners.get(key)?.size) listeners.delete(key); void database?.then(db => db.close()).catch(() => undefined) },
  }
}
export type LibraryOrganizationStore = ReturnType<typeof createLibraryOrganizationStore>

