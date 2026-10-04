import { SourceFavoriteError, sourceBookKey, sameSourceFavoriteChange, validateSourceFavoriteChange, validateSourceFavoriteItem, validateSourceFavoriteMutation, type SourceFavoriteChange, type SourceFavoriteItem, type SourceFavoriteMutation } from './sourceFavoriteApi'

export type SourceFavoriteRow = { remote: SourceFavoriteItem | null; pending: SourceFavoriteMutation | null; queued: SourceFavoriteChange | null; conflict: SourceFavoriteItem | null }
export type SourceFavoriteJournal = { cursor: number; watermark: number | null; ready: boolean; retryAfterUntil: number; rows: Record<string, SourceFavoriteRow> }
export type SourceFavoriteStoreOptions = { indexedDB?: IDBFactory; dbName?: string; origin?: string }
export const favoriteRowChange = (row: SourceFavoriteRow): SourceFavoriteChange | null => row.queued ?? row.pending ?? row.remote
export const emptyFavoriteJournal = (): SourceFavoriteJournal => ({ cursor: 0, watermark: null, ready: false, retryAfterUntil: 0, rows: {} })
const listeners = new Map<string, Set<() => void>>()
function checked(value: SourceFavoriteJournal): SourceFavoriteJournal {
  try {
    if (!value || !Number.isSafeInteger(value.cursor) || value.cursor < 0 || value.watermark !== null && (!Number.isSafeInteger(value.watermark) || value.watermark < value.cursor) || typeof value.ready !== 'boolean' || !Number.isSafeInteger(value.retryAfterUntil) || value.retryAfterUntil < 0 || !value.rows || typeof value.rows !== 'object' || Array.isArray(value.rows)) throw new Error()
    const rows: Record<string, SourceFavoriteRow> = {}
    for (const [key, row] of Object.entries(value.rows)) {
      const remote = row.remote === null ? null : validateSourceFavoriteItem(row.remote, true), pending = row.pending === null ? null : validateSourceFavoriteMutation(row.pending), queued = row.queued === null ? null : validateSourceFavoriteChange(row.queued), conflict = row.conflict === null ? null : validateSourceFavoriteItem(row.conflict, true)
      if ((!remote && !pending) || !pending && (queued || conflict) || pending && pending.expectedVersion > (remote?.version ?? 0) || [remote, pending, conflict].some(v => v && sourceBookKey(v) !== key) || conflict && JSON.stringify(conflict) !== JSON.stringify(remote) || queued && pending && sameSourceFavoriteChange(queued, pending)) throw new Error()
      rows[key] = { remote, pending, queued, conflict }
    }
    return { cursor: value.cursor, watermark: value.watermark, ready: value.ready, retryAfterUntil: value.retryAfterUntil, rows }
  } catch { throw new SourceFavoriteError('storage') }
}
/** One account journal is reduced atomically across tabs; origin and exact username isolate credentials. */
export function createSourceFavoriteStore(username: string, options: SourceFavoriteStoreOptions = {}) {
  if (!username.trim() || /[:\r\n]/.test(username)) throw new SourceFavoriteError('invalid-request')
  const factory = options.indexedDB ?? globalThis.indexedDB, dbName = options.dbName ?? 'pageturner-source-favorites-sync', origin = options.origin ?? globalThis.location?.origin ?? 'local-test', storageKey = [origin, username], key = JSON.stringify([dbName, ...storageKey])
  let database: Promise<IDBDatabase> | undefined, closed = false
  const own = new Set<() => void>(), channel = typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('pageturner-source-favorites-sync') : null
  const emit = () => listeners.get(key)?.forEach(listener => listener())
  if (channel) channel.onmessage = event => { if (event.data === key) emit() }
  function open(): Promise<IDBDatabase> {
    if (closed || !factory) return Promise.reject(new SourceFavoriteError('storage'))
    if (!database) database = new Promise<IDBDatabase>((resolve, reject) => {
      const request = factory.open(dbName, 1); let failed = false
      request.onupgradeneeded = () => request.result.createObjectStore('accounts')
      request.onerror = request.onblocked = () => { failed = true; reject(new SourceFavoriteError('storage')) }
      request.onsuccess = () => { const db = request.result; if (failed || closed) { db.close(); reject(new SourceFavoriteError('storage')); return } db.onversionchange = () => { db.close(); database = undefined }; resolve(db) }
    }).catch(error => { database = undefined; throw error })
    return database
  }
  async function transaction(reducer?: (journal: SourceFavoriteJournal) => SourceFavoriteJournal): Promise<SourceFavoriteJournal> {
    const db = await open(); let changed = false
    const result = await new Promise<SourceFavoriteJournal>((resolve, reject) => {
      const tx = db.transaction('accounts', reducer ? 'readwrite' : 'readonly'), store = tx.objectStore('accounts'), request = store.get(storageKey)
      let result = emptyFavoriteJournal(), failure: unknown
      request.onsuccess = () => { try { const current = request.result === undefined ? emptyFavoriteJournal() : checked(request.result); result = reducer ? checked(reducer(structuredClone(current))) : current; if (reducer && JSON.stringify(result) !== JSON.stringify(current)) { store.put(result, storageKey); changed = true } } catch (error) { failure = error; tx.abort() } }
      tx.oncomplete = () => resolve(result); tx.onerror = tx.onabort = () => reject(failure instanceof SourceFavoriteError ? failure : new SourceFavoriteError('storage'))
    })
    if (changed && !closed) { emit(); channel?.postMessage(key) } return result
  }
  return { username, snapshot: () => transaction(), transact: (reducer: (journal: SourceFavoriteJournal) => SourceFavoriteJournal) => transaction(reducer),
    subscribe(listener: () => void) { const group = listeners.get(key) ?? new Set(); group.add(listener); listeners.set(key, group); own.add(listener); return () => { own.delete(listener); group.delete(listener); if (!group.size) listeners.delete(key) } },
    close() { closed = true; channel?.close(); own.forEach(listener => listeners.get(key)?.delete(listener)); own.clear(); if (!listeners.get(key)?.size) listeners.delete(key); void database?.then(db => db.close()).catch(() => undefined) },
  }
}
export type SourceFavoriteStore = ReturnType<typeof createSourceFavoriteStore>
