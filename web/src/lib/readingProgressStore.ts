import { readingNamespace, type DeviceDatabaseOptions } from './deviceReadingDatabase'
import { ReadingProgressError, validReadingProgressIdentity, validReadingProgressAnchor, validReadingProgressMutation, validateReadingProgressView,
  type ReadingProgressIdentity, type ReadingProgressAnchor, type ReadingProgressMutation, type ReadingProgressView } from './readingProgressApi'

export type ReadingProgressRecord = ReadingProgressIdentity & {
  remote: ReadingProgressView | null
  pending: ReadingProgressMutation | null
  queued: ReadingProgressAnchor | null
  conflict: ReadingProgressView | null
  retryAfterUntil?: number | null
}
type StoredRecord = ReadingProgressRecord & { username: string }
export function emptyReadingProgress(identity: ReadingProgressIdentity): ReadingProgressRecord {
  return { ...identity, remote: null, pending: null, queued: null, conflict: null }
}
function validate(value: ReadingProgressRecord, identity: ReadingProgressIdentity): ReadingProgressRecord {
  if (!value || value.kind !== identity.kind || value.recordId !== identity.recordId || !validReadingProgressIdentity(value) ||
      (value.pending !== null && !validReadingProgressMutation(value.pending)) ||
      (value.queued !== null && (!value.pending || !validReadingProgressAnchor(value.queued))) ||
      (value.conflict !== null && !value.pending) || (value.retryAfterUntil != null && (!Number.isSafeInteger(value.retryAfterUntil) || value.retryAfterUntil < 0))) throw new ReadingProgressError('storage')
  try {
    return { ...identity, remote: value.remote === null ? null : validateReadingProgressView(value.remote, identity),
      pending: value.pending ? { expectedVersion: value.pending.expectedVersion, mutationId: value.pending.mutationId,
        anchor: { paragraphId: value.pending.anchor.paragraphId, characterOffset: value.pending.anchor.characterOffset } } : null,
      queued: value.queued ? { paragraphId: value.queued.paragraphId, characterOffset: value.queued.characterOffset } : null,
      conflict: value.conflict === null ? null : validateReadingProgressView(value.conflict, identity), retryAfterUntil: value.retryAfterUntil ?? null }
  } catch { throw new ReadingProgressError('storage') }
}

/** Reducers execute inside one IndexedDB transaction, including across browser tabs. */
export function createReadingProgressStore(username: string, options: DeviceDatabaseOptions = {}) {
  const namespace = readingNamespace(username)
  async function transaction<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore, result: (value: T) => void) => void): Promise<T> {
    const factory = options.indexedDB ?? globalThis.indexedDB
    if (!factory) throw new ReadingProgressError('storage')
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      let settled = false
      const request = factory.open(options.dbName ?? 'pageturner-reading-progress', 1)
      request.onupgradeneeded = () => {
        const records = request.result.createObjectStore('records', { keyPath: ['username', 'kind', 'recordId'] })
        records.createIndex('username', 'username')
      }
      request.onsuccess = () => {
        if (settled) { request.result.close(); return }
        settled = true; request.result.onversionchange = () => request.result.close(); resolve(request.result)
      }
      request.onerror = request.onblocked = () => { settled = true; reject(new ReadingProgressError('storage')) }
    }).catch(() => { throw new ReadingProgressError('storage') })
    return new Promise<T>((resolve, reject) => {
      let value: T, failure: unknown
      const tx = db.transaction(['records'], mode)
      tx.oncomplete = () => { db.close(); resolve(value) }
      tx.onabort = () => { db.close(); reject(failure instanceof ReadingProgressError ? failure : new ReadingProgressError('storage')) }
      tx.onerror = () => { /* onabort reports the rolled-back result. */ }
      try {
        action(tx.objectStore('records'), result => { value = result })
      } catch (error) { failure = error; tx.abort() }
    })
  }
  function key(identity: ReadingProgressIdentity): IDBValidKey {
    if (!validReadingProgressIdentity(identity)) throw new ReadingProgressError('invalid-request')
    return [namespace, identity.kind, identity.recordId]
  }
  return {
    async get(identity: ReadingProgressIdentity): Promise<ReadingProgressRecord> {
      const id = key(identity)
      return transaction('readonly', (store, result) => {
        const request = store.get(id)
        request.onsuccess = () => {
          try { result(request.result ? validate(request.result, identity) : emptyReadingProgress(identity)) }
          catch { request.transaction?.abort() }
        }
      })
    },
    async update(identity: ReadingProgressIdentity, reducer: (current: ReadingProgressRecord) => ReadingProgressRecord): Promise<ReadingProgressRecord> {
      const id = key(identity)
      return transaction('readwrite', (store, result) => {
        const request = store.get(id)
        request.onsuccess = () => {
          try {
            const current = request.result ? validate(request.result, identity) : emptyReadingProgress(identity)
            const next = validate(reducer(current), identity)
            store.put({ ...next, username: namespace } satisfies StoredRecord); result(next)
          } catch { request.transaction?.abort() }
        }
      })
    },
    async listPending(): Promise<ReadingProgressIdentity[]> {
      return transaction('readonly', (store, result) => {
        const request = store.index('username').openCursor(namespace), identities: ReadingProgressIdentity[] = []
        request.onsuccess = () => {
          const cursor = request.result
          if (!cursor) { result(identities); return }
          const value = cursor.value as StoredRecord
          // One damaged document must not prevent other documents from resuming.
          try { if (validate(value, value).pending) identities.push({ kind: value.kind, recordId: value.recordId }) } catch { /* The document reader reports its own damaged entry. */ }
          cursor.continue()
        }
      })
    },
  }
}
export type ReadingProgressStore = ReturnType<typeof createReadingProgressStore>
