export type DeviceStore = 'documents' | 'notes' | 'positions' | 'exchanges'
export type DeviceDatabaseOptions = { indexedDB?: IDBFactory; dbName?: string }

export function readingNamespace(username: string): string {
  const value = username.trim()
  if (!value || value.length > 200) throw new Error('기기 보관함에 사용할 계정 이름을 입력해 주세요.')
  return value
}

/** A fresh, short-lived connection also permits retry after denied/failed opens. */
export async function readingTransaction<T>(
  stores: DeviceStore[], mode: IDBTransactionMode,
  action: (transaction: IDBTransaction, result: (value: T) => void, fail: (error: Error) => void) => void,
  options: DeviceDatabaseOptions = {},
): Promise<T> {
  const factory = options.indexedDB ?? globalThis.indexedDB
  if (!factory) throw new Error('이 브라우저에서는 기기 저장소를 사용할 수 없습니다.')
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    const request = factory.open(options.dbName ?? 'pageturner-device-reading', 2)
    let settled = false
    request.onupgradeneeded = () => {
      const database = request.result
      if (!database.objectStoreNames.contains('documents')) {
      const documents = database.createObjectStore('documents', { keyPath: ['username', 'id'] })
      documents.createIndex('username', 'username')
      const notes = database.createObjectStore('notes', { keyPath: ['username', 'documentId', 'id'] })
      notes.createIndex('document', ['username', 'documentId'])
      const positions = database.createObjectStore('positions', { keyPath: ['username', 'documentId'] })
      positions.createIndex('username', 'username')
      }
      if (!database.objectStoreNames.contains('exchanges')) {
        const exchanges = database.createObjectStore('exchanges', { keyPath: ['username', 'id'] })
        exchanges.createIndex('username', 'username')
      }
    }
    request.onerror = () => { settled = true; reject(request.error ?? new Error('기기 저장소를 열지 못했습니다.')) }
    request.onblocked = () => { settled = true; reject(new Error('다른 창이 저장소를 사용 중입니다. 창을 닫고 다시 시도해 주세요.')) }
    request.onsuccess = () => {
      if (settled) { request.result.close(); return }
      request.result.onversionchange = () => request.result.close()
      resolve(request.result)
    }
  })
  return new Promise<T>((resolve, reject) => {
    let value: T
    let failure: Error | undefined
    const tx = db.transaction(stores, mode)
    tx.oncomplete = () => { db.close(); resolve(value) }
    tx.onabort = () => { db.close(); reject(failure ?? tx.error ?? new Error('기기 저장을 완료하지 못했습니다.')) }
    tx.onerror = () => { /* onabort reports failure after the transaction rolls back. */ }
    try { action(tx, result => { value = result }, error => { failure = error; tx.abort() }) } catch (error) { tx.abort(); db.close(); reject(error) }
  })
}

export function deviceStorageMessage(error: unknown): string {
  if (error instanceof DOMException && error.name === 'QuotaExceededError') return '이 기기의 저장 공간이 부족합니다. 보관한 책을 정리한 뒤 다시 시도해 주세요.'
  return error instanceof Error ? error.message : '기기 저장소를 사용할 수 없습니다.'
}
