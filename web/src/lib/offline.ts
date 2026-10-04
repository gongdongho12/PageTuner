import { ApiError, OfflineError } from './errors'
import { exactKeys, objectValue, parseTranslationShape, validAnchor, validRecordId, validTimestamp, validateTranslation } from './validation'
import type { ReadingAnchor, StoredBook, TranslationResponse } from './types'

export { OfflineError } from './errors'
export type { OfflineErrorKind } from './errors'
export type { ReadingAnchor, StoredBook } from './types'

export interface CorruptOfflineRecord {
  /** A server record can be downloaded again only when its primary-key UUID is intact. */
  recordId: string | null
  /** Opaque, library-instance-scoped identifier for explicitly deleting the damaged entry. */
  recoveryId: string
  reason: 'corrupt'
}

export interface OfflineLibrarySnapshot {
  books: StoredBook[]
  corruptRecords: CorruptOfflineRecord[]
}

export interface OfflineLibrary {
  save(translation: TranslationResponse): Promise<StoredBook>
  list(): Promise<OfflineLibrarySnapshot>
  get(recordId: string): Promise<StoredBook | undefined>
  remove(recordId: string): Promise<void>
  removeCorrupt(recoveryId: string): Promise<void>
  replaceCorrupt(recoveryId: string, translation: TranslationResponse): Promise<StoredBook>
  setAnchor(recordId: string, anchor: ReadingAnchor): Promise<void>
  close(): void
}

export interface OfflineLibraryOptions {
  indexedDB?: IDBFactory
  dbName?: string
  now?: () => Date
}

interface DatabaseBook {
  username: string
  recordId: string
  revision: string
  book: StoredBook
}

const storeName = 'books'
let nextLibraryInstance = 0

function storageError(error: unknown): OfflineError {
  if (error instanceof OfflineError) return error
  if (error instanceof ApiError) return new OfflineError(error.kind === 'unsupported' ? 'unavailable' : 'corrupt', error.kind === 'unsupported' ? error.message : '저장된 번역이 손상되었습니다. 서버에서 다시 받아 주세요.')
  if (error && typeof error === 'object' && 'name' in error && error.name === 'QuotaExceededError') {
    return new OfflineError('quota', '기기의 저장 공간이 부족합니다. 보관한 번역을 일부 삭제해 주세요.')
  }
  return new OfflineError('unavailable', '기기 저장소를 사용할 수 없습니다. 브라우저 저장 설정을 확인해 주세요.')
}

function corrupt(): never {
  throw new OfflineError('corrupt', '저장된 번역이 손상되었습니다. 서버에서 다시 받아 주세요.')
}

function databaseShape(value: unknown, username: string, recordId?: string): DatabaseBook {
  try {
    const object = objectValue(value)
    exactKeys(object, ['username', 'recordId', 'revision', 'book'])
    const rawBook = objectValue(object.book)
    exactKeys(rawBook, ['translation', 'savedAt'], ['anchor'])
    const translation = parseTranslationShape(rawBook.translation)
    if (object.username !== username || object.recordId !== translation.recordId || object.revision !== translation.revision ||
        (recordId !== undefined && object.recordId !== recordId) || !validTimestamp(rawBook.savedAt)) corrupt()
    if (Object.prototype.hasOwnProperty.call(rawBook, 'anchor') && !validAnchor(rawBook.anchor, translation)) corrupt()
    const book: StoredBook = { translation, savedAt: rawBook.savedAt }
    if (rawBook.anchor !== undefined) book.anchor = { ...(rawBook.anchor as ReadingAnchor) }
    return { username, recordId: translation.recordId, revision: translation.revision, book }
  } catch (error) { throw storageError(error) }
}

function sameTranslation(first: TranslationResponse, second: TranslationResponse): boolean {
  // `created` describes the POST result, not immutable stored content; GET returns false.
  return JSON.stringify({ ...first, created: false }) === JSON.stringify({ ...second, created: false })
}

async function validatedBook(value: unknown, username: string, recordId?: string): Promise<StoredBook> {
  try {
    const parsed = databaseShape(value, username, recordId)
    parsed.book.translation = await validateTranslation(parsed.book.translation)
    return parsed.book
  } catch (error) { throw storageError(error) }
}

function transaction<T>(
  db: IDBDatabase,
  mode: IDBTransactionMode,
  work: (store: IDBObjectStore, result: (value: T) => void, fail: (error: unknown) => void) => void,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let tx: IDBTransaction
    try { tx = db.transaction(storeName, mode) } catch (error) { reject(storageError(error)); return }
    let result: T
    let failure: unknown
    tx.oncomplete = () => resolve(result)
    tx.onerror = event => { failure ??= (event.target as IDBRequest).error ?? tx.error }
    tx.onabort = () => reject(storageError(failure ?? tx.error))
    const fail = (error: unknown) => {
      failure = error
      try { tx.abort() } catch { reject(storageError(error)) }
    }
    try { work(tx.objectStore(storeName), value => { result = value }, fail) } catch (error) { fail(error) }
  })
}

/** Username scopes records, not credentials. Passwords/Authorization never enter this API or IndexedDB. */
export function createOfflineLibrary(username: string, options: OfflineLibraryOptions = {}): OfflineLibrary {
  if (!username.trim()) throw new OfflineError('unavailable', '보관함을 열 계정 이름을 입력해 주세요.')
  const factory = options.indexedDB ?? globalThis.indexedDB
  const name = options.dbName ?? 'pageturner-translations-v1'
  const now = options.now ?? (() => new Date())
  let opening: Promise<IDBDatabase> | undefined
  let closed = false
  const libraryInstance = ++nextLibraryInstance
  let nextRecoveryId = 0
  const recoveries = new Map<string, { key: IDBValidKey; value: unknown }>()

  function database(): Promise<IDBDatabase> {
    if (closed || !factory) return Promise.reject(new OfflineError('unavailable', '기기 저장소를 사용할 수 없습니다.'))
    if (!opening) {
      const pending = new Promise<IDBDatabase>((resolve, reject) => {
        let request: IDBOpenDBRequest
        try { request = factory.open(name, 1) } catch (error) { reject(storageError(error)); return }
        let failed = false
        request.onupgradeneeded = () => {
          const db = request.result
          if (!db.objectStoreNames.contains(storeName)) {
            const books = db.createObjectStore(storeName, { keyPath: ['username', 'recordId'] })
            books.createIndex('byUsername', 'username')
          }
        }
        request.onerror = () => { failed = true; reject(storageError(request.error)) }
        request.onblocked = () => { failed = true; reject(new OfflineError('blocked', '다른 탭이 저장소를 사용 중입니다. 다른 탭을 닫고 다시 열어 주세요.')) }
        request.onsuccess = () => {
          const db = request.result
          if (closed || failed) { db.close(); reject(new OfflineError('unavailable', '보관함이 닫혔습니다.')); return }
          db.onversionchange = () => { closed = true; db.close() }
          resolve(db)
        }
      })
      opening = pending
      // Retry after a temporary permission/open/blocked failure instead of caching
      // the rejected promise for the lifetime of this library instance.
      void pending.catch(() => { if (opening === pending) opening = undefined })
    }
    return opening
  }

  function checkedId(recordId: string): void {
    if (!validRecordId(recordId)) throw new OfflineError('not-found', '번역 식별자가 올바르지 않습니다.')
  }

  async function get(recordId: string): Promise<StoredBook | undefined> {
    checkedId(recordId)
    const db = await database()
    const raw = await transaction<unknown>(db, 'readonly', (store, result) => {
      const request = store.get([username, recordId])
      request.onsuccess = () => result(request.result)
    })
    return raw === undefined ? undefined : validatedBook(raw, username, recordId)
  }

  return {
    async save(value) {
      let translation: TranslationResponse
      try { translation = await validateTranslation(value) } catch (error) { throw storageError(error) }
      const db = await database()
      const savedAt = now().toISOString()
      return transaction<StoredBook>(db, 'readwrite', (store, result, fail) => {
        const request = store.get([username, translation.recordId])
        request.onsuccess = () => {
          try {
            let anchor: ReadingAnchor | undefined
            if (request.result !== undefined) {
              const previous = databaseShape(request.result, username, translation.recordId)
              if (!sameTranslation(previous.book.translation, translation)) {
                throw new OfflineError('conflict', '같은 번역 식별자에 다른 내용이 저장되어 있습니다. 기존 저장본을 확인해 주세요.')
              }
              anchor = previous.book.anchor
            }
            const book: StoredBook = { translation, savedAt, ...(anchor ? { anchor } : {}) }
            const stored: DatabaseBook = { username, recordId: translation.recordId, revision: translation.revision, book }
            store.put(stored)
            result(book)
          } catch (error) { fail(error) }
        }
      })
    },
    async list() {
      const db = await database()
      const raw = await transaction<Array<{ key: IDBValidKey; value: unknown }>>(db, 'readonly', (store, result) => {
        const entries: Array<{ key: IDBValidKey; value: unknown }> = []
        const request = store.index('byUsername').openCursor(username)
        request.onsuccess = () => {
          const cursor = request.result
          if (!cursor) { result(entries); return }
          entries.push({ key: cursor.primaryKey, value: cursor.value as unknown })
          cursor.continue()
        }
      })
      const books: StoredBook[] = []
      const corruptRecords: CorruptOfflineRecord[] = []
      await Promise.all(raw.map(async ({ key, value }) => {
        // Inspect the real IndexedDB key independently of the potentially damaged
        // payload. Never expose a recovery operation for another account's key.
        if (!Array.isArray(key) || key.length !== 2 || key[0] !== username) corrupt()
        try {
          if (!validRecordId(key[1])) corrupt()
          books.push(await validatedBook(value, username, key[1]))
        } catch (error) {
          const failure = storageError(error)
          if (failure.kind !== 'corrupt') throw failure
          const recoveryId = `${libraryInstance}:corrupt-${++nextRecoveryId}`
          recoveries.set(recoveryId, { key, value })
          corruptRecords.push({ recordId: validRecordId(key[1]) ? key[1] : null, recoveryId, reason: 'corrupt' })
        }
      }))
      books.sort((first, second) => second.savedAt.localeCompare(first.savedAt) || first.translation.recordId.localeCompare(second.translation.recordId))
      return { books, corruptRecords }
    },
    get,
    async remove(recordId) {
      checkedId(recordId)
      const db = await database()
      await transaction<void>(db, 'readwrite', (store, result) => { store.delete([username, recordId]); result(undefined) })
    },
    async removeCorrupt(recoveryId) {
      const recovery = recoveries.get(recoveryId)
      const key = recovery?.key
      if (!recovery || !Array.isArray(key) || key.length !== 2 || key[0] !== username) {
        throw new OfflineError('not-found', '삭제할 손상 항목을 찾을 수 없습니다. 보관함을 다시 열어 주세요.')
      }
      const db = await database()
      await transaction<void>(db, 'readwrite', (store, result, fail) => {
        const request = store.get(key)
        request.onsuccess = () => {
          try {
            if (request.result === undefined || JSON.stringify(request.result) !== JSON.stringify(recovery.value)) {
              throw new OfflineError('conflict', '저장 항목이 변경되었습니다. 보관함을 새로고침한 뒤 다시 시도해 주세요.')
            }
            store.delete(key)
            result(undefined)
          } catch (error) { fail(error) }
        }
      })
      recoveries.delete(recoveryId)
    },
    async replaceCorrupt(recoveryId, value) {
      const recovery = recoveries.get(recoveryId)
      const key = recovery?.key
      if (!recovery || !Array.isArray(key) || key.length !== 2 || key[0] !== username || !validRecordId(key[1])) {
        throw new OfflineError('not-found', '다시 보관할 손상 항목을 찾을 수 없습니다. 보관함을 다시 열어 주세요.')
      }
      let translation: TranslationResponse
      try { translation = await validateTranslation(value) } catch (error) { throw storageError(error) }
      if (translation.recordId !== key[1]) throw new OfflineError('conflict', '복구할 번역 식별자가 원래 항목과 다릅니다.')
      const db = await database()
      const savedAt = now().toISOString()
      const repaired = await transaction<StoredBook>(db, 'readwrite', (store, result, fail) => {
        const request = store.get(key)
        request.onsuccess = () => {
          try {
            if (request.result === undefined || JSON.stringify(request.result) !== JSON.stringify(recovery.value)) {
              throw new OfflineError('conflict', '저장 항목이 변경되었습니다. 보관함을 새로고침한 뒤 다시 시도해 주세요.')
            }
            const book: StoredBook = { translation, savedAt }
            // Replace in one transaction. A quota/put/commit failure leaves the old
            // damaged entry in place for another recovery attempt or explicit deletion.
            store.put({ username, recordId: translation.recordId, revision: translation.revision, book } satisfies DatabaseBook)
            result(book)
          } catch (error) { fail(error) }
        }
      })
      recoveries.delete(recoveryId)
      return repaired
    },
    async setAnchor(recordId, anchor) {
      const snapshot = { ...anchor }
      const existing = await get(recordId)
      if (!existing) throw new OfflineError('not-found', '저장된 번역을 찾을 수 없습니다.')
      if (!validAnchor(snapshot, existing.translation)) throw new OfflineError('invalid-anchor', '읽기 위치가 번역 본문 범위를 벗어났습니다.')
      const db = await database()
      await transaction<void>(db, 'readwrite', (store, result, fail) => {
        const request = store.get([username, recordId])
        request.onsuccess = () => {
          try {
            if (request.result === undefined) throw new OfflineError('not-found', '저장된 번역을 찾을 수 없습니다.')
            const current = databaseShape(request.result, username, recordId)
            if (!sameTranslation(current.book.translation, existing.translation)) throw new OfflineError('conflict', '번역이 변경되어 읽기 위치를 저장하지 못했습니다.')
            current.book.anchor = snapshot
            store.put(current)
            result(undefined)
          } catch (error) { fail(error) }
        }
      })
    },
    close() {
      closed = true
      recoveries.clear()
      if (opening) void opening.then(db => db.close(), () => undefined)
    },
  }
}
