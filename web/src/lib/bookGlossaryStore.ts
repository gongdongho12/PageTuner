import { BookGlossaryError, sameBookGlossary, sameBookGlossaryScope, sameBookGlossaryView, validateBookGlossaryMutation, validateBookGlossary, validateBookGlossaryScope, validateBookGlossaryView, type BookGlossaryMutation, type BookGlossaryView, type BookGlossary, type BookGlossaryScope } from './bookGlossaryApi'

export type BookGlossaryRecord = { enabled: boolean; selected: boolean; local: BookGlossary; remote: BookGlossaryView | null; pending: BookGlossaryMutation | null; legacyIds: Record<string, string>; conflict: BookGlossaryView | null; retryAfterUntil: number | null }
export type BookGlossaryStoreOptions = { indexedDB?: IDBFactory; dbName?: string; databaseName?: string }
const listeners = new Map<string, Set<() => void>>()
const databaseDefault = 'pageturner-book-glossary-sync'
function validateLegacyIds(value: unknown): Record<string, string> { if (!value || typeof value !== 'object' || Array.isArray(value) || Object.values(value).some(id => typeof id !== 'string' || !/^[a-f0-9-]{36}$/.test(id))) throw new BookGlossaryError('storage'); return { ...value as Record<string, string> } }
function account(username: string) { if (!username.trim() || /[:\r\n]/.test(username)) throw new BookGlossaryError('invalid-request') }
function checked(value: unknown, scope: BookGlossaryScope): BookGlossaryRecord {
  try {
    const r = value as BookGlossaryRecord
    if (!r || typeof r !== 'object' || typeof r.enabled !== 'boolean' || r.selected !== undefined && typeof r.selected !== 'boolean' || r.retryAfterUntil !== null && (!Number.isSafeInteger(r.retryAfterUntil) || r.retryAfterUntil < 0)) throw new Error()
    const row = { enabled: r.enabled, selected: r.selected ?? r.enabled, local: validateBookGlossary(r.local), remote: r.remote === null ? null : validateBookGlossaryView(r.remote),
      pending: r.pending === null ? null : validateBookGlossaryMutation(r.pending), legacyIds: validateLegacyIds(r.legacyIds),
      conflict: r.conflict === null ? null : validateBookGlossaryView(r.conflict), retryAfterUntil: r.retryAfterUntil }
    if (row.selected && !row.enabled) throw new Error()
    if ((!row.enabled && (row.pending || row.conflict)) || (!row.pending && row.conflict)) throw new Error()
    if (row.enabled && !row.remote || row.pending && (row.pending.expectedVersion > (row.remote?.version ?? 0)) ||
        row.conflict && !sameBookGlossaryView(row.conflict, row.remote) || row.remote && !sameBookGlossaryScope(scope, row.remote)) throw new Error()
    return row
  } catch { throw new BookGlossaryError('storage') }
}
function openDatabase(factory: IDBFactory | undefined, dbName: string): Promise<IDBDatabase> {
  if (!factory) return Promise.reject(new BookGlossaryError('storage'))
  return new Promise((resolve, reject) => {
    const request = factory.open(dbName, 1); let failed = false
    const fail = () => { failed = true; reject(new BookGlossaryError('storage')) }
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
function stored(value: unknown, username: string, scope?: BookGlossaryScope): { scope: BookGlossaryScope; record: BookGlossaryRecord } {
  try {
    const entry = value as { username: string; scope: BookGlossaryScope; record: BookGlossaryRecord }
    const actualScope = validateBookGlossaryScope(entry.scope)
    if (entry.username !== username || scope && !sameBookGlossaryScope(actualScope, scope)) throw new Error()
    return { scope: actualScope, record: checked(entry.record, actualScope) }
  } catch { throw new BookGlossaryError('storage') }
}
/** Enumerates only this account's journals, so reconnect can recover outboxes whose editors are closed. */
export async function listBookGlossaryRecords(username: string, options: BookGlossaryStoreOptions = {}): Promise<{ scope: BookGlossaryScope; record: BookGlossaryRecord }[]> {
  account(username)
  const db = await openDatabase(options.indexedDB ?? globalThis.indexedDB, options.dbName ?? options.databaseName ?? databaseDefault)
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction('records'), request = tx.objectStore('records').index('username').getAll(username)
      let result: { scope: BookGlossaryScope; record: BookGlossaryRecord }[] = [], error: unknown
      request.onsuccess = () => { try { result = request.result.map(value => stored(value, username)) } catch (e) { error = e; tx.abort() } }
      tx.oncomplete = () => resolve(result)
      tx.onerror = tx.onabort = () => reject(error instanceof BookGlossaryError ? error : new BookGlossaryError('storage'))
    })
  } finally { db.close() }
}

/** Read/reduce/write is atomic across tabs; the key includes account and exact provider/book/language tuple. */
export function createBookGlossaryStore(username: string, inputScope: BookGlossaryScope, options: BookGlossaryStoreOptions = {}) {
  account(username)
  const scope = validateBookGlossaryScope(inputScope), dbName = options.dbName ?? options.databaseName ?? databaseDefault
  const storageKey = [username, scope.providerId, scope.bookId, scope.targetLanguage], key = JSON.stringify([dbName, ...storageKey])
  const factory = options.indexedDB ?? globalThis.indexedDB
  let database: Promise<IDBDatabase> | undefined, closed = false
  const ownListeners = new Set<() => void>()
  const channel = typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('pageturner-book-glossary-changes') : null
  function emit() { listeners.get(key)?.forEach(listener => listener()) }
  if (channel) channel.onmessage = event => { if (event.data === key) emit() }
  function notify() { emit(); channel?.postMessage(key) }
  function open(): Promise<IDBDatabase> {
    if (closed) return Promise.reject(new BookGlossaryError('storage'))
    if (!database) database = openDatabase(factory, dbName).then(db => {
      if (closed) { db.close(); throw new BookGlossaryError('storage') }
      db.onversionchange = () => { db.close(); database = undefined }
      return db
    }).catch(error => { database = undefined; throw error })
    return database
  }
  async function transaction(reducer?: (record: BookGlossaryRecord | null) => BookGlossaryRecord): Promise<BookGlossaryRecord | null> {
    const db = await open()
    if (closed) throw new BookGlossaryError('storage')
    let changed = false
    const result = await new Promise<BookGlossaryRecord | null>((resolve, reject) => {
      const tx = db.transaction('records', reducer ? 'readwrite' : 'readonly'), store = tx.objectStore('records'), request = store.get(storageKey)
      let result: BookGlossaryRecord | null = null, error: unknown
      request.onsuccess = () => {
        try {
          const current = request.result === undefined ? null : stored(request.result, username, scope).record
          result = reducer ? checked(reducer(current ? structuredClone(current) : null), scope) : current
          if (reducer && JSON.stringify(current) !== JSON.stringify(result)) { store.put({ username, scope, record: result }, storageKey); changed = true }
        } catch (failure) { error = failure; tx.abort() }
      }
      tx.oncomplete = () => resolve(result)
      tx.onerror = tx.onabort = () => reject(error instanceof BookGlossaryError ? error : new BookGlossaryError('storage'))
    })
    if (changed && !closed) notify()
    return result
  }
  return {
    scope,
    async initialize(fallback: BookGlossary) {
      const local = validateBookGlossary(fallback)
      return (await transaction(current => current ?? { enabled: false, selected: false, local, remote: null, pending: null, legacyIds: {}, conflict: null, retryAfterUntil: null }))!
    },
    snapshot: () => transaction(),
    async transact(reducer: (record: BookGlossaryRecord) => BookGlossaryRecord) {
      return (await transaction(current => { if (!current) throw new BookGlossaryError('storage'); return reducer(current) }))!
    },
    subscribe(listener: () => void) {
      if (!closed) { ownListeners.add(listener); const group = listeners.get(key) ?? new Set(); group.add(listener); listeners.set(key, group) }
      return () => { ownListeners.delete(listener); listeners.get(key)?.delete(listener); if (!listeners.get(key)?.size) listeners.delete(key) }
    },
    close() { closed = true; channel?.close(); ownListeners.forEach(listener => listeners.get(key)?.delete(listener)); ownListeners.clear(); if (!listeners.get(key)?.size) listeners.delete(key); void database?.then(db => db.close()).catch(() => undefined) },
  }
}
export type BookGlossaryStore = ReturnType<typeof createBookGlossaryStore>

