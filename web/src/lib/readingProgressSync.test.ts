import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { ReadingProgressError, type ReadingProgressClient, type ReadingProgressErrorCode, type ReadingProgressIdentity, type ReadingProgressView } from './readingProgressApi'
import { createReadingProgressStore } from './readingProgressStore'
import { createReadingProgressController, type ReadingProgressController } from './readingProgressSync'

beforeAll(() => { vi.stubGlobal('crypto', webcrypto) })
const identity: ReadingProgressIdentity = { kind: 'ORIGINAL', recordId: '11111111-1111-1111-1111-111111111111' }
const a = { paragraphId: 'p1', characterOffset: 2 }, b = { paragraphId: 'p2', characterOffset: 0 }, c = { paragraphId: 'p3', characterOffset: 0 }
const initial: ReadingProgressView = { ...identity, version: 0, anchor: null, updatedAt: null }
const view = (version: number, anchor = a): ReadingProgressView => ({ ...identity, version, anchor, updatedAt: '2026-09-16T01:00:00Z' })
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail }); return { promise, resolve, reject } }
const controllers: ReadingProgressController[] = []
afterEach(() => { controllers.splice(0).forEach(controller => controller.close()); vi.restoreAllMocks() })
function setup(options: { factory?: IDBFactory; username?: string; api?: ReadingProgressClient | null } = {}) {
  const store = createReadingProgressStore(options.username ?? 'alice', { indexedDB: options.factory ?? new IDBFactory() })
  const api = options.api === undefined ? {
    get: vi.fn<ReadingProgressClient['get']>().mockResolvedValue(initial),
    put: vi.fn<ReadingProgressClient['put']>().mockImplementation(async (_, input) => view(input.expectedVersion + 1, input.anchor)), close: vi.fn(),
  } : options.api
  const controller = createReadingProgressController({ api, store, identity, debounceMs: 100_000 })
  controllers.push(controller); return { store, api: api as NonNullable<typeof api>, controller }
}

describe('durable reading progress synchronization', () => {
  it('restores server position without uploading a stale fallback', async () => {
    const api = { get: vi.fn().mockResolvedValue(view(4, b)), put: vi.fn(), close: vi.fn() }
    const { controller, store } = setup({ api })
    expect(await controller.start(a)).toEqual(b)
    expect(api.put).not.toHaveBeenCalled()
    expect((await store.get(identity)).pending).toBeNull()
    expect(controller.snapshot()).toMatchObject({ status: 'synced', localAnchor: b, remote: { version: 4 } })
  })
  it('keeps offline movement per account and document and resumes the same mutation on reconnect', async () => {
    const factory = new IDBFactory(), offline = setup({ factory, api: null })
    await offline.controller.start(); await offline.controller.move(a)
    const pending = (await offline.store.get(identity)).pending!
    expect(await offline.store.listPending()).toEqual([identity])
    expect((await createReadingProgressStore('bob', { indexedDB: factory }).get(identity)).pending).toBeNull()
    expect((await offline.store.get({ ...identity, kind: 'TRANSLATION' })).pending).toBeNull()
    offline.controller.close()
    const online = setup({ factory }); expect(await online.controller.start()).toEqual(a)
    expect(online.api.get).not.toHaveBeenCalled()
    expect(online.api.put).toHaveBeenCalledWith(identity, pending, expect.any(AbortSignal))
    expect(await online.store.listPending()).toEqual([])
  })
  it('retries identical mutation after a lost successful response', async () => {
    const { api, controller, store } = setup()
    await controller.start(); await controller.move(a)
    const sent = (await store.get(identity)).pending!
    vi.mocked(api.put).mockRejectedValueOnce(new ReadingProgressError('network'))
    await controller.flush()
    expect(controller.snapshot()).toMatchObject({ status: 'error', errorCode: 'network', localAnchor: a })
    expect((await store.get(identity)).pending).toEqual(sent)
    await controller.flush()
    expect(api.put).toHaveBeenNthCalledWith(2, identity, sent, expect.any(AbortSignal))
    expect(controller.snapshot().status).toBe('synced')
  })
  it('queues a newer movement while the previous mutation is in flight', async () => {
    const { controller, api, store } = setup(), ack = deferred<ReadingProgressView>()
    await controller.start(); await controller.move(a)
    vi.mocked(api.put).mockImplementationOnce(() => ack.promise)
    const flushing = controller.flush()
    await vi.waitFor(() => expect(api.put).toHaveBeenCalledTimes(1))
    await controller.move(b); expect(controller.snapshot().localAnchor).toEqual(b)
    ack.resolve(view(1)); await flushing
    const calls = vi.mocked(api.put).mock.calls
    expect(calls).toHaveLength(2)
    expect(calls[1][1]).toMatchObject({ expectedVersion: 1, anchor: b })
    expect(calls[1][1].mutationId).not.toBe(calls[0][1].mutationId)
    expect((await store.get(identity)).remote).toEqual(view(2, b))
  })
  it('does not let an initial GET overwrite movement made while loading', async () => {
    const remote = deferred<ReadingProgressView>(), api = { get: vi.fn().mockReturnValue(remote.promise),
      put: vi.fn().mockRejectedValue(new ReadingProgressError('conflict', view(3, b))), close: vi.fn() }
    const { controller, store } = setup({ api }), starting = controller.start()
    await vi.waitFor(() => expect(api.get).toHaveBeenCalled())
    await controller.move(a); remote.resolve(view(3, b)); expect(await starting).toEqual(a)
    expect(controller.snapshot()).toMatchObject({ status: 'conflict', localAnchor: a, remote: { anchor: b } })
    expect(controller.snapshot().restoration).toBeUndefined()
    expect((await store.get(identity)).pending?.anchor).toEqual(a)
  })
  it('persists conflict until the user chooses local, then uses the conflicting version', async () => {
    const factory = new IDBFactory(), first = setup({ factory })
    await first.controller.start(); await first.controller.move(a)
    vi.mocked(first.api.put).mockRejectedValueOnce(new ReadingProgressError('conflict', view(5, b)))
    await first.controller.flush(); first.controller.close()
    const reopened = setup({ factory }); await reopened.controller.start()
    expect(reopened.controller.snapshot().status).toBe('conflict')
    expect(reopened.api.put).not.toHaveBeenCalled()
    await reopened.controller.move(c); await reopened.controller.chooseLocal()
    expect(reopened.api.put).toHaveBeenCalledWith(identity, expect.objectContaining({ expectedVersion: 5, anchor: c }), expect.any(AbortSignal))
    expect((await reopened.store.get(identity)).remote).toEqual(view(6, c))
  })
  it('chooses server explicitly without writing and permits another edit afterwards', async () => {
    const { controller, api, store } = setup()
    await controller.start(); await controller.move(a)
    vi.mocked(api.put).mockRejectedValueOnce(new ReadingProgressError('conflict', view(5, b)))
    await controller.flush(); expect(await controller.chooseServer()).toEqual(b)
    const restoration = controller.snapshot().restoration
    expect(restoration).toEqual({ sequence: 1, anchor: b })
    expect(api.put).toHaveBeenCalledTimes(1)
    expect((await store.get(identity)).pending).toBeNull()
    await controller.move(c); await controller.flush()
    expect((await store.get(identity)).remote).toEqual(view(6, c))
    expect(controller.snapshot().restoration).toEqual(restoration)
  })
  it('does not clear another tab’s successor after a late acknowledgement', async () => {
    const factory = new IDBFactory(), first = setup({ factory }), second = setup({ factory })
    await first.controller.start(); await second.controller.start(); await first.controller.move(a)
    const oldResponse = deferred<ReadingProgressView>()
    vi.mocked(first.api.put).mockImplementationOnce(() => oldResponse.promise)
    const oldFlush = first.controller.flush(); await vi.waitFor(() => expect(first.api.put).toHaveBeenCalled())
    await second.controller.flush(); await second.controller.move(b)
    const successor = (await second.store.get(identity)).pending!
    vi.mocked(first.api.put).mockRejectedValueOnce(new ReadingProgressError('network'))
    oldResponse.resolve(view(1)); await oldFlush
    expect((await first.store.get(identity)).pending).toEqual(successor)
    expect(first.controller.snapshot().localAnchor).toEqual(b)
  })
  it('retains accepted movement when account closes immediately and ignores late responses', async () => {
    const { controller, api, store } = setup(); await controller.start()
    const moving = controller.move(a); controller.close(); await moving
    expect((await store.get(identity)).pending?.anchor).toEqual(a)
    expect(api.put).not.toHaveBeenCalled()
    const second = setup(), response = deferred<ReadingProgressView>()
    await second.controller.start(); await second.controller.move(a)
    vi.mocked(second.api.put).mockImplementationOnce(() => response.promise)
    const flushing = second.controller.flush(); await vi.waitFor(() => expect(second.api.put).toHaveBeenCalled())
    second.controller.close(); response.resolve(view(1)); await flushing
    expect((await second.store.get(identity)).pending?.anchor).toEqual(a)
  })
  it('does not publish an old position when a second tab edited during a failed request', async () => {
    const factory = new IDBFactory(), first = setup({ factory }), second = setup({ factory }), failed = deferred<ReadingProgressView>()
    await first.controller.start(); await first.controller.move(a)
    vi.mocked(first.api.put).mockImplementationOnce(() => failed.promise)
    const flushing = first.controller.flush(); await vi.waitFor(() => expect(first.api.put).toHaveBeenCalled())
    await second.controller.move(b)
    failed.reject(new ReadingProgressError('network')); await flushing
    expect(first.controller.snapshot()).toMatchObject({ localAnchor: b, errorCode: 'network' })
  })
  it('serializes simultaneous local edits without losing the first pending operation', async () => {
    const factory = new IDBFactory(), one = setup({ factory, api: null }), two = setup({ factory, api: null })
    await Promise.all([one.controller.move(a), two.controller.move(b)])
    const entry = await one.store.get(identity)
    expect([entry.pending?.anchor, entry.queued]).toEqual(expect.arrayContaining([a, b]))
    expect(await one.store.listPending()).toHaveLength(1)
  })
  it('refreshes clean cached data on reopening but never writes fallback', async () => {
    const { controller, api } = setup(); await controller.start(a)
    vi.mocked(api.get).mockResolvedValueOnce(view(3, b))
    expect(await controller.refresh()).toEqual(b)
    expect(api.put).not.toHaveBeenCalled()
    expect(await controller.start(a)).toEqual(b)
  })
  it('reports a storage failure without sending an undurable mutation', async () => {
    const factory = new IDBFactory(), { controller, api } = setup({ factory }); await controller.start()
    vi.spyOn(factory, 'open').mockImplementationOnce(() => { throw new DOMException('secret internal error', 'QuotaExceededError') })
    await controller.move(a)
    expect(controller.snapshot()).toMatchObject({ status: 'error', errorCode: 'storage' })
    expect(api.put).not.toHaveBeenCalled()
  })
  it('persists Retry-After across reopen and keeps the exact pending mutation', async () => {
    const factory = new IDBFactory(), first = setup({ factory })
    const clock = vi.spyOn(Date, 'now').mockReturnValue(1_000_000)
    await first.controller.start(); await first.controller.move(a)
    const pending = (await first.store.get(identity)).pending
    vi.mocked(first.api.put).mockRejectedValueOnce(new ReadingProgressError('limit', undefined, 60))
    await first.controller.flush(); first.controller.close()
    const second = setup({ factory }); await second.controller.start(); await second.controller.flush()
    expect(second.api.put).not.toHaveBeenCalled()
    expect(second.controller.snapshot()).toMatchObject({ status: 'error', errorCode: 'limit' })
    expect((await second.store.get(identity)).pending).toEqual(pending)
    clock.mockReturnValue(1_060_001); await second.controller.flush()
    expect(second.api.put).toHaveBeenCalledWith(identity, pending, expect.any(AbortSignal))
    expect((await second.store.get(identity)).retryAfterUntil).toBeNull()
  })
  it('never turns slow local persistence or PUT acknowledgements into reader restoration commands', async () => {
    const { controller, store } = setup()
    await controller.start(a)
    let displayed = a, lastSequence = controller.snapshot().restoration?.sequence ?? 0
    const restoration = controller.snapshot().restoration
    controller.subscribe(state => {
      if (state.restoration && state.restoration.sequence !== lastSequence) {
        displayed = state.restoration.anchor; lastSequence = state.restoration.sequence
      }
    })
    const gate = deferred<void>(), firstPersisted = deferred<void>(), update = store.update.bind(store)
    vi.spyOn(store, 'update').mockImplementationOnce(async (...args) => {
      const value = await update(...args); firstPersisted.resolve(); await gate.promise; return value
    })
    displayed = b; const first = controller.move(b)
    await firstPersisted.promise
    displayed = c; const second = controller.move(c)
    gate.resolve(); await Promise.all([first, second]); await controller.flush()
    expect(displayed).toEqual(c)
    expect(controller.snapshot().restoration).toEqual(restoration)
    expect(controller.snapshot().localAnchor).toEqual(c)
  })
  it('suppresses a stored restoration when the user moves before an initial IndexedDB read resolves', async () => {
    const { controller, store, api } = setup()
    await store.update(identity, current => ({ ...current, remote: view(1, a) }))
    vi.mocked(api.get).mockResolvedValue(view(1, a))
    const gate = deferred<void>(), snapshotReady = deferred<void>(), get = store.get.bind(store)
    vi.spyOn(store, 'get').mockImplementationOnce(async (...args) => {
      const value = await get(...args); snapshotReady.resolve(); await gate.promise; return value
    })
    const starting = controller.start(); await snapshotReady.promise
    await controller.move(b); gate.resolve(); await starting
    expect(controller.snapshot().restoration).toBeUndefined()
    expect(controller.snapshot().localAnchor).toEqual(b)
  })
  it('restores pre-existing offline pending positions on opening and issues a new command on explicit clean refresh', async () => {
    const factory = new IDBFactory(), first = setup({ factory, api: null })
    await first.controller.move(b); first.controller.close()
    const second = setup({ factory }); await second.controller.start(a)
    expect(second.controller.snapshot().restoration).toEqual({ sequence: 1, anchor: b })
    vi.mocked(second.api.get).mockResolvedValue(view(2, c))
    await second.controller.refresh()
    expect(second.controller.snapshot().restoration).toEqual({ sequence: 3, anchor: c })
  })
  it.each<ReadingProgressErrorCode>(['authentication', 'forbidden', 'not-found', 'invalid-request', 'mutation-reused', 'invalid-response', 'storage'])(
    'halts automatic retries after %s while preserving subsequent movement until an explicit recheck', async code => {
      const { controller, store, api } = setup()
      await controller.start(); await controller.move(a)
      vi.mocked(api.put).mockRejectedValueOnce(new ReadingProgressError(code))
      await controller.flush()
      await controller.move(b); await controller.flush(); await controller.flush()
      expect(api.put).toHaveBeenCalledTimes(1)
      expect(controller.snapshot()).toMatchObject({ status: 'error', errorCode: code, localAnchor: b })
      expect((await store.get(identity)).queued).toEqual(b)
      await controller.refresh()
      expect(api.put).toHaveBeenCalledTimes(3)
      expect(controller.snapshot()).toMatchObject({ status: 'synced', localAnchor: b })
    },
  )
  it('holds an initial authentication failure until a new account controller resumes its durable pending position', async () => {
    const factory = new IDBFactory(), first = setup({ factory })
    vi.mocked(first.api.get).mockRejectedValueOnce(new ReadingProgressError('authentication'))
    await first.controller.start(); await first.controller.move(a); await first.controller.flush()
    expect(first.api.put).not.toHaveBeenCalled()
    expect(first.controller.snapshot()).toMatchObject({ status: 'error', errorCode: 'authentication', localAnchor: a })
    first.controller.close()
    const next = setup({ factory }); await next.controller.start()
    expect(next.api.put).toHaveBeenCalledTimes(1)
    expect(next.controller.snapshot()).toMatchObject({ status: 'synced', localAnchor: a })
  })
})
