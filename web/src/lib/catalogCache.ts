import { ApiError } from './errors'
import { sha256 } from './validation'
import { validateNovelCatalog, validateNovelSource, type WorkflowClient } from './workflowApi'
import type { NovelCatalog, NovelSource, CatalogFilters } from './workflowTypes'

export const CATALOG_CACHE_TTL = 15 * 60_000
export const CATALOG_CACHE_MAX_PAGES = 24
export const CATALOG_CACHE_MAX_BYTES = 8 * 1024 * 1024
export type CatalogRequest = { source: NovelSource; url?: string; query: string; page: number; filters: CatalogFilters }
export type CachedCatalog = { request: CatalogRequest; catalog: NovelCatalog; savedAt: number; stale: boolean }
export type CatalogResult = CachedCatalog & { origin: 'network' | 'cache'; offline: boolean; refreshFailed: boolean; cacheUnavailable: boolean }
type CacheRow = { username: string; key: string; request: CatalogRequest; catalog: NovelCatalog; savedAt: number; touchedAt: number; bytes: number; digest: string }
type Options = { indexedDB?: IDBFactory; dbName?: string; now?: () => number; maxPages?: number; maxBytes?: number; ttl?: number }
export function catalogCacheKey(input: CatalogRequest) {
  if (!input.source.id || !Number.isSafeInteger(input.page) || input.page < 1) throw new Error('목록 페이지 정보를 확인할 수 없습니다.')
  return JSON.stringify([input.source.id, input.url ?? null, input.query, input.page,
    input.filters.genre ?? null, input.filters.orderBy ?? null, input.filters.order ?? null, input.filters.status ?? null])
}
function payload(row: Pick<CacheRow, 'request' | 'catalog' | 'savedAt'>) {
  return JSON.stringify([row.request, row.catalog, row.savedAt])
}
export function createCatalogCache(username: string, options: Options = {}) {
  if (!username.trim()) throw new Error('저장된 목록을 열 계정 이름을 입력해 주세요.')
  const now = options.now ?? Date.now, ttl = options.ttl ?? CATALOG_CACHE_TTL
  async function transaction<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore, done: (value: T) => void) => void): Promise<T> {
    const factory = options.indexedDB ?? globalThis.indexedDB
    if (!factory) throw new Error('기기 목록 캐시를 사용할 수 없습니다.')
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = factory.open(options.dbName ?? 'pageturner-catalog-cache', 1)
      let failed = false
      request.onupgradeneeded = () => { const store = request.result.createObjectStore('pages', { keyPath: ['username', 'key'] }); store.createIndex('username', 'username') }
      request.onerror = () => { failed = true; reject(request.error) }
      request.onblocked = () => { failed = true; reject(new Error('기기 목록 캐시를 사용할 수 없습니다.')) }
      request.onsuccess = () => { if (failed) request.result.close(); else { request.result.onversionchange = () => request.result.close(); resolve(request.result) } }
    })
    return new Promise<T>((resolve, reject) => {
      let result: T
      const tx = db.transaction('pages', mode)
      tx.oncomplete = () => { db.close(); resolve(result) }
      tx.onabort = () => { db.close(); reject(tx.error ?? new Error('기기 목록 캐시를 사용할 수 없습니다.')) }
      tx.onerror = () => {}
      try { work(tx.objectStore('pages'), value => { result = value }) } catch (error) { tx.abort(); db.close(); reject(error) }
    })
  }
  async function checked(row: CacheRow): Promise<CachedCatalog> {
    const source = validateNovelSource(row.request.source), catalog = validateNovelCatalog(row.catalog)
    if (row.username !== username || row.key !== catalogCacheKey(row.request) || !Number.isFinite(row.savedAt) || row.savedAt > now() + 60_000 ||
      typeof row.request.query !== 'string' || row.request.query.length > 2000 || catalog.sourceId !== source.id || catalog.currentPage !== row.request.page || catalog.items.length > 1000 ||
      !Number.isSafeInteger(row.bytes) || row.bytes !== new TextEncoder().encode(payload(row)).length || row.digest !== await sha256(payload(row))) throw new Error('저장된 목록을 확인할 수 없습니다.')
    return { request: structuredClone(row.request), catalog, savedAt: row.savedAt, stale: now() - row.savedAt >= ttl }
  }
  return {
    async get(input: CatalogRequest): Promise<CachedCatalog | undefined> {
      const key = catalogCacheKey(input)
      const row = await transaction<CacheRow | undefined>('readonly', (store, done) => { const request = store.get([username, key]); request.onsuccess = () => done(request.result) })
      if (!row) return
      const value = await checked(row)
      // A touch must not restore an entry evicted or replaced by a concurrent writer.
      await transaction<void>('readwrite', store => { const request = store.get([username, key]); request.onsuccess = () => { if (request.result?.digest === row.digest) store.put({ ...request.result, touchedAt: now() }) } })
      return value
    },
    async list(): Promise<CachedCatalog[]> {
      const rows = await transaction<CacheRow[]>('readonly', (store, done) => { const request = store.index('username').getAll(username); request.onsuccess = () => done(request.result) })
      const values: CachedCatalog[] = []
      for (const row of rows) { try { values.push(await checked(row)) } catch { /* A disposable cache never exposes a damaged response. */ } }
      return values.sort((a, b) => b.savedAt - a.savedAt)
    },
    async put(input: CatalogRequest, raw: NovelCatalog, signal?: AbortSignal): Promise<boolean> {
      signal?.throwIfAborted()
      const catalog = validateNovelCatalog(raw), request = structuredClone(input)
      const row: CacheRow = { username, key: catalogCacheKey(request), request, catalog, savedAt: now(), touchedAt: now(), bytes: 0, digest: '' }
      if (catalog.sourceId !== request.source.id || catalog.currentPage !== request.page || catalog.items.length > 1000) throw new Error('목록 페이지 정보를 확인할 수 없습니다.')
      const text = payload(row); row.bytes = new TextEncoder().encode(text).length; row.digest = await sha256(text)
      const maxBytes = options.maxBytes ?? CATALOG_CACHE_MAX_BYTES, maxPages = options.maxPages ?? CATALOG_CACHE_MAX_PAGES
      if (row.bytes > maxBytes) return false
      signal?.throwIfAborted()
      await transaction<void>('readwrite', store => {
        const request = store.index('username').getAll(username)
        request.onsuccess = () => {
          if (signal?.aborted) { store.transaction.abort(); return }
          const rows = (request.result as CacheRow[]).filter(value => value.key !== row.key).sort((a, b) => b.touchedAt - a.touchedAt)
          let bytes = row.bytes, count = 1
          for (const previous of rows) {
            if (count >= maxPages || !Number.isSafeInteger(previous.bytes) || previous.bytes < 0 || bytes + previous.bytes > maxBytes) store.delete([username, previous.key])
            else { count++; bytes += previous.bytes }
          }
          store.put(row)
        }
      })
      return true
    },
  }
}
export type CatalogCache = ReturnType<typeof createCatalogCache>

/** A controller prefetches one next page only. It never follows an unbounded chain. */
export function createCachedCatalogLoader(cache: CatalogCache, client: Pick<WorkflowClient, 'catalog'> | null, online = () => globalThis.navigator?.onLine !== false) {
  let prefetch: AbortController | undefined
  const cancel = () => { prefetch?.abort(); prefetch = undefined }
  async function load(input: CatalogRequest, signal: AbortSignal, force = false): Promise<CatalogResult> {
    let cached: CachedCatalog | undefined, cacheUnavailable = false
    try { cached = await cache.get(input) } catch { cacheUnavailable = true }
    signal.throwIfAborted()
    const offline = !client || !online()
    if (cached && ((!force && !cached.stale) || offline)) return { ...cached, origin: 'cache', offline, refreshFailed: false, cacheUnavailable }
    if (offline) throw new Error('연결 없이 사용할 저장된 목록이 없습니다. 서버에 연결해 주세요.')
    try {
      const catalog = await client!.catalog(input.source.id, input.url, input.query, input.page, signal, input.filters)
      signal.throwIfAborted()
      if (catalog.sourceId !== input.source.id || catalog.currentPage !== input.page) throw new ApiError('invalid-response', '목록 페이지 정보를 확인할 수 없습니다.')
      try { cacheUnavailable = !(await cache.put(input, catalog, signal)) } catch { signal.throwIfAborted(); cacheUnavailable = true }
      return { request: structuredClone(input), catalog, savedAt: Date.now(), stale: false, origin: 'network', offline: false, refreshFailed: false, cacheUnavailable }
    } catch (error) {
      signal.throwIfAborted()
      if (cached && error instanceof ApiError && ['network', 'timeout', 'server'].includes(error.kind)) return { ...cached, origin: 'cache', offline: !online() || error.kind !== 'server', refreshFailed: true, cacheUnavailable }
      throw error
    }
  }
  return {
    load, cancel,
    prefetchNext(result: CatalogResult) {
      cancel()
      if (!client || !online() || result.offline || !result.catalog.hasNextPage) return
      const controller = new AbortController(); prefetch = controller
      const input = { ...result.request, page: result.catalog.currentPage + 1 }
      void load(input, controller.signal).catch(() => { /* Optional preload failure does not replace the visible page. */ }).finally(() => { if (prefetch === controller) prefetch = undefined })
    },
  }
}
