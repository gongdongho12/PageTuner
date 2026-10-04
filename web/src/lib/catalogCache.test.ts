import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { createCachedCatalogLoader, createCatalogCache, catalogCacheKey, type CatalogRequest } from './catalogCache'
import type { NovelCatalog } from './workflowTypes'
import { ApiError } from './errors'
beforeAll(() => vi.stubGlobal('crypto', webcrypto))
const input: CatalogRequest = { source: { id: 'wtr-lab', displayName: 'WTR', defaultCatalogUrl: 'https://wtr-lab.com/en/novel-list', remoteSearch: true, requiresUrl: false,
  filters: { genres: [], status: [], sort: [], directions: [] } }, url: 'https://wtr-lab.com/en/novel-list', query: 'story', page: 1, filters: { genre: 'fantasy', orderBy: 'views', order: 'desc' } }
const page = (number = 1): NovelCatalog => ({ sourceId: 'wtr-lab', url: input.url!, currentPage: number, totalPages: 3, totalItems: 3,
  hasPreviousPage: number > 1, hasNextPage: number < 3, items: [{ bookId: 'https://wtr-lab.com/novel/1', title: `Original title ${number}`, url: 'https://wtr-lab.com/novel/1', authors: ['Author'], sourceLanguage: 'en', coverUrl: null, description: 'Original description', chapterCount: 10, tags: [] }] })
describe('account scoped source catalog cache', () => {
  it('keys every source, address, search, filter and page dimension and isolates users', async () => {
    const indexedDB = new IDBFactory(), cache = createCatalogCache('alice', { indexedDB })
    await cache.put(input, page())
    const variants: CatalogRequest[] = [{ ...input, source: { ...input.source, id: 'novelbuddy' } }, { ...input, url: input.url + '?different' }, { ...input, query: 'other' }, { ...input, page: 2 },
      ...(['genre', 'orderBy', 'order', 'status'] as const).map(key => ({ ...input, filters: { ...input.filters, [key]: 'other' } }))]
    expect(new Set([input, ...variants].map(catalogCacheKey)).size).toBe(9)
    for (const variant of variants) expect(await cache.get(variant)).toBeUndefined()
    expect(await createCatalogCache('bob', { indexedDB }).get(input)).toBeUndefined()
    const hit = await cache.get(input); hit!.catalog.items[0].title = 'Translated display only'
    expect((await cache.get(input))?.catalog.items[0].title).toBe('Original title 1')
  })
  it('expires with TTL, evicts least recently used pages and does not evict other accounts', async () => {
    let time = 1000
    const indexedDB = new IDBFactory(), options = { indexedDB, now: () => time, ttl: 100, maxPages: 2 }, cache = createCatalogCache('alice', options)
    await cache.put(input, page()); time++
    await cache.put({ ...input, page: 2 }, page(2)); time++
    await cache.get(input); time++
    await createCatalogCache('bob', options).put({ ...input, page: 2 }, page(2))
    await cache.put({ ...input, page: 3 }, page(3))
    expect(await cache.get({ ...input, page: 2 })).toBeUndefined()
    expect((await createCatalogCache('bob', options).list())).toHaveLength(1)
    time += 100
    expect((await cache.get(input))?.stale).toBe(true)
    expect(await createCatalogCache('small', { indexedDB, maxBytes: 10 }).put(input, page())).toBe(false)
    // The response validator omits nullable optional metadata before storage. Budget the
    // persisted representation, not the larger wire fixture containing explicit nulls.
    const firstPayload = (await cache.get(input))!.catalog
    const secondInput = { ...input, page: 2 }
    await createCatalogCache('budget-fixture', { indexedDB, now: () => time }).put(secondInput, page(2))
    const secondPayload = (await createCatalogCache('budget-fixture', { indexedDB }).get(secondInput))!.catalog
    const byteLimit = new TextEncoder().encode(JSON.stringify([input, firstPayload, time])).length +
      new TextEncoder().encode(JSON.stringify([secondInput, secondPayload, time])).length - 1
    const byteCache = createCatalogCache('byte-limit', { indexedDB, now: () => time, maxBytes: byteLimit })
    await byteCache.put(input, page()); await byteCache.put({ ...input, page: 2 }, page(2))
    expect(await byteCache.list()).toHaveLength(1)
    expect(await byteCache.get(input)).toBeUndefined()
    expect((await byteCache.get(secondInput))?.catalog.currentPage).toBe(2)
  })
  it('revalidates expired content, falls back explicitly on network failure and never hides authentication errors', async () => {
    let time = 1000
    const cache = createCatalogCache('alice', { indexedDB: new IDBFactory(), ttl: 100, now: () => time })
    await cache.put(input, page()); time += 101
    const catalog = vi.fn(async () => { throw new ApiError('network', 'offline') })
    const loader = createCachedCatalogLoader(cache, { catalog }, () => true), signal = new AbortController().signal
    expect(await loader.load(input, signal)).toMatchObject({ origin: 'cache', stale: true, offline: true, refreshFailed: true })
    catalog.mockRejectedValueOnce(new ApiError('authentication', 'denied'))
    await expect(loader.load(input, signal, true)).rejects.toMatchObject({ kind: 'authentication' })
    const offline = createCachedCatalogLoader(cache, null)
    expect(await offline.load(input, signal)).toMatchObject({ origin: 'cache', offline: true })
    await expect(offline.load({ ...input, page: 2 }, signal)).rejects.toThrow('저장된 목록이 없습니다')
  })
  it('returns fresh cache without a request and refreshes only when explicitly requested', async () => {
    const cache = createCatalogCache('alice', { indexedDB: new IDBFactory() }); await cache.put(input, page())
    const catalog = vi.fn(async () => ({ ...page(), items: [{ ...page().items[0], title: 'Changed at source' }] }))
    const loader = createCachedCatalogLoader(cache, { catalog }), signal = new AbortController().signal
    expect((await loader.load(input, signal)).origin).toBe('cache'); expect(catalog).not.toHaveBeenCalled()
    expect((await loader.load(input, signal, true)).catalog.items[0].title).toBe('Changed at source')
    expect(catalog).toHaveBeenCalledWith(input.source.id, input.url, input.query, 1, signal, input.filters)
  })
  it('preloads one exact next page and aborts an obsolete prefetch before it can write', async () => {
    const cache = createCatalogCache('alice', { indexedDB: new IDBFactory() }); await cache.put(input, page())
    let resolve: ((value: NovelCatalog) => void) | undefined, captured: AbortSignal | undefined
    const catalog = vi.fn(async (_source: string, _url?: string, _query?: string, _page?: number, signal?: AbortSignal) => { captured = signal; return await new Promise<NovelCatalog>(done => { resolve = done }) })
    const loader = createCachedCatalogLoader(cache, { catalog }), hit = await loader.load(input, new AbortController().signal)
    loader.prefetchNext(hit)
    await vi.waitFor(() => expect(catalog).toHaveBeenCalledTimes(1))
    expect(catalog.mock.calls[0][3]).toBe(2)
    loader.cancel(); expect(captured?.aborted).toBe(true); resolve?.(page(2))
    await new Promise(done => setTimeout(done, 10))
    expect(await cache.get({ ...input, page: 2 })).toBeUndefined()
    catalog.mockImplementation(async () => page(2))
    loader.prefetchNext(hit)
    await vi.waitFor(async () => expect((await cache.get({ ...input, page: 2 }))?.catalog.currentPage).toBe(2))
    expect(catalog).toHaveBeenCalledTimes(2)
    expect(await cache.get({ ...input, page: 3 })).toBeUndefined()
  })
  it('rejects a tampered persisted payload and mismatched source/page responses', async () => {
    const indexedDB = new IDBFactory(), dbName = 'tampered-cache', cache = createCatalogCache('alice', { indexedDB, dbName })
    await cache.put(input, page())
    const db = await new Promise<IDBDatabase>(resolve => { const request = indexedDB.open(dbName, 1); request.onsuccess = () => resolve(request.result) })
    await new Promise<void>(resolve => { const tx = db.transaction('pages', 'readwrite'), store = tx.objectStore('pages'), request = store.get(['alice', catalogCacheKey(input)]); request.onsuccess = () => { request.result.catalog.items[0].title = 'Tampered'; store.put(request.result) }; tx.oncomplete = () => resolve() }); db.close()
    await expect(cache.get(input)).rejects.toThrow('확인할 수 없습니다')
    expect(await cache.list()).toEqual([])
    const loader = createCachedCatalogLoader(cache, { catalog: async () => page(2) })
    await expect(loader.load(input, new AbortController().signal, true)).rejects.toMatchObject({ kind: 'invalid-response' })
  })
})
