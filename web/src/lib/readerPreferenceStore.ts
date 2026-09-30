import { ReaderPreferenceError, sameReaderPreferences, sameReaderPreferenceView, validateReaderPreferenceMutation, validateReaderPreferences, validateReaderPreferenceView, type ReaderPreferenceMutation, type ReaderPreferenceView, type SharedReaderPreferences } from './readerPreferenceApi'

export type ReaderPreferenceRecord = { enabled: boolean; local: SharedReaderPreferences; remote: ReaderPreferenceView | null; pending: ReaderPreferenceMutation | null; queued: SharedReaderPreferences | null; conflict: ReaderPreferenceView | null; retryAfterUntil: number | null }
export type ReaderPreferenceStoreOptions = { indexedDB?: IDBFactory; dbName?: string; databaseName?: string }
const listeners = new Map<string, Set<() => void>>()
const databaseDefault = 'pageturner-reader-preference-sync'
function checked(value: unknown): ReaderPreferenceRecord {
  try {
    const r = value as ReaderPreferenceRecord
    if (!r || typeof r !== 'object' || typeof r.enabled !== 'boolean' || r.retryAfterUntil !== null && (!Number.isSafeInteger(r.retryAfterUntil) || r.retryAfterUntil < 0)) throw new Error()
    const row = { enabled: r.enabled, local: validateReaderPreferences(r.local), remote: r.remote === null ? null : validateReaderPreferenceView(r.remote),
      pending: r.pending === null ? null : validateReaderPreferenceMutation(r.pending), queued: r.queued === null ? null : validateReaderPreferences(r.queued),
      conflict: r.conflict === null ? null : validateReaderPreferenceView(r.conflict), retryAfterUntil: r.retryAfterUntil }
    if ((!row.enabled && (row.pending || row.queued || row.conflict)) || (!row.pending && (row.queued || row.conflict))) throw new Error()
    if (row.enabled && !row.remote || row.pending && (!sameReaderPreferences(row.local, row.queued ?? row.pending.preferences) || row.pending.expectedVersion > (row.remote?.version ?? 0)) ||
        row.conflict && !sameReaderPreferenceView(row.conflict, row.remote)) throw new Error()
    return row
  } catch { throw new ReaderPreferenceError('storage') }
}

/** Read/reduce/write takes place in one transaction, including when another tab owns the current outbox. */
export function createReaderPreferenceStore(username: string, options: ReaderPreferenceStoreOptions = {}) {
  if (!username.trim() || /[:\r\n]/.test(username)) throw new ReaderPreferenceError('invalid-request')
  const dbName = options.dbName ?? options.databaseName ?? databaseDefault, key = JSON.stringify([dbName, username])
  const factory = options.indexedDB ?? globalThis.indexedDB
  let database: Promise<IDBDatabase> | undefined, closed = false
  const ownListeners = new Set<() => void>()
  const channel = typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('pageturner-reader-preference-changes') : null
  function emit() { listeners.get(key)?.forEach(listener => listener()) }
  if (channel) channel.onmessage = event => { if (event.data === key) emit() }
  function notify() { emit(); channel?.postMessage(key) }
  function open(): Promise<IDBDatabase> {
    if (closed || !factory) return Promise.reject(new ReaderPreferenceError('storage'))
    if (!database) database = new Promise<IDBDatabase>((resolve, reject) => {
      const request = factory.open(dbName, 1); let failed = false
      const fail = () => { failed = true; reject(new ReaderPreferenceError('storage')) }
      request.onupgradeneeded = () => { request.result.createObjectStore('accounts') }
      request.onerror = fail; request.onblocked = fail
      request.onsuccess = () => {
        const db = request.result
        if (failed || closed) { db.close(); fail(); return }
        db.onversionchange = () => { db.close(); database = undefined }
        resolve(db)
      }
    }).catch(error => { database = undefined; throw error })
    return database
  }
  async function transaction(reducer?: (record: ReaderPreferenceRecord | null) => ReaderPreferenceRecord): Promise<ReaderPreferenceRecord | null> {
    const db = await open()
    if (closed) throw new ReaderPreferenceError('storage')
    let changed = false
    const result = await new Promise<ReaderPreferenceRecord | null>((resolve, reject) => {
      const tx = db.transaction('accounts', reducer ? 'readwrite' : 'readonly'), store = tx.objectStore('accounts'), request = store.get(username)
      let result: ReaderPreferenceRecord | null = null, error: unknown
      request.onsuccess = () => {
        try {
          const current = request.result === undefined ? null : checked(request.result)
          result = reducer ? checked(reducer(current ? structuredClone(current) : null)) : current
          if (reducer && JSON.stringify(current) !== JSON.stringify(result)) { store.put(result, username); changed = true }
        } catch (failure) { error = failure; tx.abort() }
      }
      tx.oncomplete = () => resolve(result)
      tx.onerror = tx.onabort = () => reject(error instanceof ReaderPreferenceError ? error : new ReaderPreferenceError('storage'))
    })
    if (changed && !closed) notify()
    return result
  }
  return {
    async initialize(fallback: SharedReaderPreferences) {
      const local = validateReaderPreferences(fallback)
      return (await transaction(current => current ?? { enabled: false, local, remote: null, pending: null, queued: null, conflict: null, retryAfterUntil: null }))!
    },
    snapshot: () => transaction(),
    async transact(reducer: (record: ReaderPreferenceRecord) => ReaderPreferenceRecord) {
      return (await transaction(current => { if (!current) throw new ReaderPreferenceError('storage'); return reducer(current) }))!
    },
    subscribe(listener: () => void) {
      if (!closed) { ownListeners.add(listener); const group = listeners.get(key) ?? new Set(); group.add(listener); listeners.set(key, group) }
      return () => { ownListeners.delete(listener); listeners.get(key)?.delete(listener); if (!listeners.get(key)?.size) listeners.delete(key) }
    },
    close() { closed = true; channel?.close(); ownListeners.forEach(listener => listeners.get(key)?.delete(listener)); ownListeners.clear(); if (!listeners.get(key)?.size) listeners.delete(key); void database?.then(db => db.close()).catch(() => undefined) },
  }
}
export type ReaderPreferenceStore = ReturnType<typeof createReaderPreferenceStore>
