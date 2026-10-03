import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SourceFavoriteError, sourceBookKey, type SourceFavoriteClient, type SourceFavoriteItem, type SourceFavoriteMutation, type SourceFavoriteBook, type SourceFavoriteQuery } from './sourceFavoriteApi'
import { createSourceFavoriteStore, type SourceFavoriteStore } from './sourceFavoriteStore'
import { createSourceFavoriteController, type SourceFavoriteController } from './sourceFavoriteSync'

const id = { providerId: 'wtr-lab', bookId: 'https://wtr-lab.com/en/novel/42/example' }
const book: SourceFavoriteBook = { title: 'Example', authors: [], language: 'auto', url: id.bookId }
const item = (version: number, changed = book, deleted = false): SourceFavoriteItem => ({ ...id, version, changeRevision: version, deleted, book: deleted ? null : changed, updatedAt: '2026-10-02T12:00:00Z' })
function deferred<T>() { let resolve!: (v: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }
const resources: { controller: SourceFavoriteController; store: SourceFavoriteStore }[] = []
function setup(api: SourceFavoriteClient | null, indexedDB = new IDBFactory(), username = 'alice', origin = 'http://localhost') {
  const store = createSourceFavoriteStore(username, { indexedDB, origin }), controller = createSourceFavoriteController(store, api); resources.push({ store, controller }); return { store, controller }
}
function transport(initial: SourceFavoriteItem[] = []) {
  const changes = [...initial]
  return { changes,
    list: vi.fn(async (query: SourceFavoriteQuery) => { const watermark = query.untilRevision ?? changes.length, items = changes.filter(i => i.changeRevision > query.afterRevision && i.changeRevision <= watermark); return { items, nextAfterRevision: watermark, watermark, hasMore: false } }),
    put: vi.fn(async (input: SourceFavoriteMutation) => { const previous = [...changes].reverse().find(i => sourceBookKey(i) === sourceBookKey(input)), version = previous?.version ?? 0
      if (version !== input.expectedVersion) throw new SourceFavoriteError('conflict', previous)
      const next: SourceFavoriteItem = { providerId: input.providerId, bookId: input.bookId, version: version + 1, changeRevision: changes.length + 1, deleted: input.deleted, book: input.book, updatedAt: '2026-10-02T12:00:00Z' }; changes.push(next); return next
    }), close: vi.fn(),
  }
}
afterEach(async () => { for (const r of resources.splice(0)) { await r.controller.close(); r.store.close() } vi.restoreAllMocks() })

describe('source favorite durable account synchronization', () => {
  it('downloads remote favorites without uploading device favorites and requires explicit adoption', async () => {
    const api = transport(), { controller: c } = setup(api); await c.refresh(); expect(api.put).not.toHaveBeenCalled(); expect(c.snapshot().ready).toBe(true)
    expect(await c.update(id, { deleted: false, book }, null)).toBe(true); expect(api.put).not.toHaveBeenCalled()
    await c.drain(); expect(api.put).toHaveBeenCalledTimes(1); expect(c.snapshot().rows[sourceBookKey(id)]).toMatchObject({ remote: item(1), pending: null })
  })
  it('never adopts before the initial feed is completely downloaded', async () => {
    const api = transport(), { controller: c } = setup(api); expect(await c.update(id, { deleted: false, book }, null)).toBe(false); expect(c.snapshot().errorCode).toBe('choice-stale'); expect(api.put).not.toHaveBeenCalled()
  })
  it('keeps exact opaque identities and isolates both accounts and backend origins', async () => {
    const db = new IDBFactory(), api = transport(), a = setup(api, db), b = setup(api, db, 'bob'), otherOrigin = setup(api, db, 'alice', 'http://another-host')
    await a.controller.refresh(); const first = { providerId: 'a:b', bookId: 'c' }, second = { providerId: 'a', bookId: 'b:c' }
    await a.controller.update(first, { deleted: false, book }, null); await a.controller.update(second, { deleted: false, book }, null)
    expect(Object.keys((await a.store.snapshot()).rows)).toHaveLength(2); expect((await b.store.snapshot()).rows).toEqual({}); expect((await otherOrigin.store.snapshot()).rows).toEqual({})
  })
  it('replays the identical mutation after lost response and journal reopening', async () => {
    const db = new IDBFactory(), api = transport(), first = setup(api, db); await first.controller.refresh(); await first.controller.update(id, { deleted: false, book }, null)
    api.put.mockRejectedValueOnce(new SourceFavoriteError('network')); await first.controller.drain(); const original = api.put.mock.calls[0][0]
    await first.controller.close(); first.store.close(); const reopened = setup(api, db); await reopened.controller.refresh()
    expect(api.put.mock.calls[1][0]).toEqual(original); expect(reopened.controller.snapshot().rows[sourceBookKey(id)].pending).toBeNull()
  })
  it('queues a deletion while an add is in flight without changing its idempotency identity', async () => {
    const api = transport(), { controller: c } = setup(api); await c.refresh(); await c.update(id, { deleted: false, book }, null)
    const ack = deferred<SourceFavoriteItem>(), originalPut = api.put.getMockImplementation()!; api.put.mockImplementationOnce(() => ack.promise)
    const drain = c.drain(); await vi.waitFor(() => expect(api.put).toHaveBeenCalledTimes(1)); const sent = api.put.mock.calls[0][0]
    await c.update(id, { deleted: true, book: null }, c.snapshot().rows[sourceBookKey(id)]); expect(c.snapshot().rows[sourceBookKey(id)].pending).toEqual(sent)
    ack.resolve(await originalPut(sent)); await drain
    expect(c.snapshot().errorCode).toBeUndefined(); expect(api.put).toHaveBeenCalledTimes(2)
    expect(api.put.mock.calls[1][0]).toMatchObject({ expectedVersion: 1, deleted: true, book: null }); expect(c.snapshot().rows[sourceBookKey(id)]).toMatchObject({ remote: { deleted: true, version: 2 }, pending: null })
  })
  it('keeps deletion conflicts until the displayed local or server value is explicitly chosen', async () => {
    const api = transport([item(1)]), { controller: c } = setup(api); await c.refresh(); await c.update(id, { deleted: true, book: null }, c.snapshot().rows[sourceBookKey(id)])
    api.changes.push(item(2, { ...book, title: 'New title' })); await c.drain(); const shown = c.snapshot().rows[sourceBookKey(id)]
    expect(shown).toMatchObject({ conflict: { version: 2 }, pending: { deleted: true } }); await c.choose(id, 'local', shown); await c.drain()
    expect(c.snapshot().rows[sourceBookKey(id)]).toMatchObject({ remote: { version: 3, deleted: true }, pending: null, conflict: null })
  })
  it('refuses stale decisions after another tab changes the account journal', async () => {
    const db = new IDBFactory(), api = transport([item(1)]), a = setup(api, db), b = setup(api, db); await a.controller.refresh(); await b.controller.refresh()
    const baseline = a.controller.snapshot().rows[sourceBookKey(id)]; await b.controller.update(id, { deleted: true, book: null }, baseline)
    expect(await a.controller.update(id, { deleted: false, book: { ...book, title: 'Stale edit' } }, baseline)).toBe(false)
    expect((await a.store.snapshot()).rows[sourceBookKey(id)].pending?.deleted).toBe(true)
  })
  it('pins a multipage feed and persists its resume cursor independently of PUT acknowledgments', async () => {
    const api = transport(), { controller: c } = setup(api)
    api.list.mockResolvedValueOnce({ items: [item(1)], nextAfterRevision: 1, watermark: 2, hasMore: true }).mockRejectedValueOnce(new SourceFavoriteError('network'))
    await c.refresh(); expect(c.snapshot()).toMatchObject({ cursor: 1, watermark: 2, ready: false }); expect(api.list.mock.calls[1][0]).toEqual({ afterRevision: 1, untilRevision: 2 })
    api.list.mockResolvedValueOnce({ items: [item(2, book, true)], nextAfterRevision: 2, watermark: 2, hasMore: false }); await c.refresh()
    expect(c.snapshot()).toMatchObject({ cursor: 2, watermark: null, ready: true }); expect(c.snapshot().rows[sourceBookKey(id)].remote?.deleted).toBe(true)
  })
  it('downloads another device change before an acknowledged PUT instead of skipping to its revision', async () => {
    const api = transport(), { controller: c } = setup(api); await c.refresh(); await c.update(id, { deleted: false, book }, null)
    const other = { ...item(1), bookId: 'https://wtr-lab.com/en/novel/43/other' }; api.changes.push(other)
    await c.drain()
    expect(api.list.mock.calls.at(-1)![0].afterRevision).toBe(0)
    expect(c.snapshot().rows[sourceBookKey(other)].remote).toEqual(other); expect(c.snapshot().cursor).toBe(2)
  })
  it('preserves cooldown across refreshes without extending it on every timer tick', async () => {
    let now = Date.parse('2026-10-02T12:00:00Z'); vi.spyOn(Date, 'now').mockImplementation(() => now)
    const api = transport(), { controller: c, store } = setup(api); await c.refresh(); await c.update(id, { deleted: false, book }, null)
    api.put.mockRejectedValueOnce(new SourceFavoriteError('limit', undefined, 60)); await c.drain(); const until = (await store.snapshot()).retryAfterUntil
    now += 30_000; await c.drain(); await c.refresh(); expect((await store.snapshot()).retryAfterUntil).toBe(until); expect(api.put).toHaveBeenCalledTimes(1)
    now += 30_001; await c.drain(); expect(api.put).toHaveBeenCalledTimes(2); expect(c.snapshot().rows[sourceBookKey(id)].pending).toBeNull()
  })
  it('finishes already submitted durable edits during logout and ignores late network responses', async () => {
    const api = transport(), { controller: c, store } = setup(api); await c.refresh(); const updating = c.update(id, { deleted: false, book }, null); await c.close(); await updating
    expect((await store.snapshot()).rows[sourceBookKey(id)].pending).not.toBeNull(); expect(api.put).not.toHaveBeenCalled()
    const delayed = deferred<SourceFavoriteItem>(), another = setup(api); await another.controller.refresh(); await another.controller.update(id, { deleted: false, book }, null); api.put.mockImplementationOnce(() => delayed.promise)
    const running = another.controller.drain(); await vi.waitFor(() => expect(api.put).toHaveBeenCalledTimes(1)); await another.controller.close(); delayed.resolve(item(1)); await running
    expect((await another.store.snapshot()).rows[sourceBookKey(id)].pending).not.toBeNull()
  })
})
