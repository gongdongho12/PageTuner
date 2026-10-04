import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ReaderPreferenceError, type ReaderPreferenceClient, type ReaderPreferenceMutation, type ReaderPreferenceView, type SharedReaderPreferences } from './readerPreferenceApi'
import { createReaderPreferenceStore, type ReaderPreferenceStore } from './readerPreferenceStore'
import { createReaderPreferenceController, type ReaderPreferenceController } from './readerPreferenceSync'

const local: SharedReaderPreferences = { fontSize: 20, lineHeightPercent: 160, pageMargin: 16, touchDirection: 'left-previous', listMode: 'paged' }
const empty: ReaderPreferenceView = { version: 0, preferences: null, updatedAt: null }
const view = (version: number, preferences = local): ReaderPreferenceView => ({ version, preferences, updatedAt: `2026-09-30T12:00:${String(version).padStart(2, '0')}Z` })
const controllers: ReaderPreferenceController[] = [], stores: ReaderPreferenceStore[] = []
function store(indexedDB = new IDBFactory(), username = 'alice') { const s = createReaderPreferenceStore(username, { indexedDB }); stores.push(s); return s }
function controller(api: ReaderPreferenceClient | null, s = store()) { const c = createReaderPreferenceController({ api, store: s, debounceMs: 60_000 }); controllers.push(c); return c }
function api(initial = empty) {
  let remote = initial
  return { get: vi.fn(async () => structuredClone(remote)), put: vi.fn(async (input: ReaderPreferenceMutation) => { remote = view(input.expectedVersion + 1, input.preferences); return remote }), close: vi.fn() }
}
function deferred<T>() { let resolve!: (value: T) => void, reject!: (reason: unknown) => void; const promise = new Promise<T>((r, e) => { resolve = r; reject = e }); return { promise, resolve, reject } }
async function link(c: ReaderPreferenceController) { await c.start(local); const s = c.snapshot(); await c.choose('local', { local: s.local, remote: s.remote }) }
afterEach(async () => { await Promise.all(controllers.splice(0).map(c => c.close())); stores.splice(0).forEach(s => s.close()); vi.restoreAllMocks() })

describe('durable preference controller', () => {
  it('requires an explicit initial choice and allows local edits while unlinked', async () => {
    const remote = view(3, { ...local, fontSize: 30 }), transport = api(remote), c = controller(transport)
    await c.start(local); expect(c.snapshot()).toMatchObject({ status: 'unlinked', local, remote })
    await c.update({ pageMargin: 24 }); await c.flush(); expect(transport.put).not.toHaveBeenCalled()
    const current = c.snapshot(); await c.choose('server', current)
    expect(c.snapshot()).toMatchObject({ status: 'synced', enabled: true, local: remote.preferences })
  })
  it('keeps an edit submitted during the initial GET and rejects choosing an empty server', async () => {
    const get = deferred<ReaderPreferenceView>(), transport = { ...api(), get: vi.fn(() => get.promise) }, c = controller(transport)
    const starting = c.start(local); await c.update({ fontSize: 25 }); get.resolve(empty); await starting
    expect(c.snapshot().local.fontSize).toBe(25)
    await c.choose('server', c.snapshot()); expect(c.snapshot().errorCode).toBe('invalid-request')
    await c.choose('local', c.snapshot()); expect(c.snapshot()).toMatchObject({ status: 'synced', local: { fontSize: 25 } })
  })
  it('replays the exact mutation after response loss and reopening', async () => {
    const indexedDB = new IDBFactory(), s = store(indexedDB), transport = api(), c = controller(transport, s)
    await link(c); await c.update({ fontSize: 26 })
    transport.put.mockRejectedValueOnce(new ReaderPreferenceError('network')); await c.flush()
    const sent = transport.put.mock.calls.at(-1)![0]
    await c.close(); s.close()
    const reopened = controller(transport, store(indexedDB)); await reopened.start(local)
    expect(transport.put.mock.calls.at(-1)![0]).toEqual(sent)
    expect(reopened.snapshot()).toMatchObject({ status: 'synced', local: { fontSize: 26 } })
  })
  it('preserves a newer queued edit while the previous request is in flight', async () => {
    const transport = api(), c = controller(transport); await link(c); await c.update({ fontSize: 24 })
    const response = deferred<ReaderPreferenceView>(); transport.put.mockImplementationOnce(() => response.promise)
    const flushing = c.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(2))
    const sent = transport.put.mock.calls[1][0]; await c.update({ pageMargin: 28 }); response.resolve(view(2, sent.preferences)); await flushing
    expect(transport.put).toHaveBeenCalledTimes(3)
    expect(transport.put.mock.calls[2][0]).toMatchObject({ expectedVersion: 2, preferences: { fontSize: 24, pageMargin: 28 } })
    expect(c.snapshot()).toMatchObject({ status: 'synced', local: { fontSize: 24, pageMargin: 28 } })
  })
  it('persists conflicts and refuses choices from stale displayed local or remote snapshots', async () => {
    const indexedDB = new IDBFactory(), transport = api(), c = controller(transport, store(indexedDB)); await link(c); await c.update({ fontSize: 24 })
    const remote = view(3, { ...local, fontSize: 32 }); transport.put.mockRejectedValueOnce(new ReaderPreferenceError('conflict', remote)); await c.flush()
    const stale = c.snapshot(); expect(stale.status).toBe('conflict')
    await c.update({ pageMargin: 32 }); await c.choose('server', stale)
    expect(c.snapshot()).toMatchObject({ status: 'conflict', errorCode: 'choice-stale', local: { pageMargin: 32 } })
    await c.close()
    transport.get.mockResolvedValue(remote)
    const reopened = controller(transport, store(indexedDB)); await reopened.start(local)
    expect(reopened.snapshot()).toMatchObject({ status: 'conflict', local: { fontSize: 24, pageMargin: 32 }, remote })
    await reopened.choose('local', reopened.snapshot())
    expect(transport.put.mock.calls.at(-1)![0]).toMatchObject({ expectedVersion: 3, preferences: { pageMargin: 32 } })
  })
  it('atomically merges independent edits from two controllers', async () => {
    const indexedDB = new IDBFactory(), transport = api(), a = controller(transport, store(indexedDB)), b = controller(transport, store(indexedDB))
    await a.start(local); await b.start(local)
    await Promise.all([a.update({ fontSize: 28 }), b.update({ pageMargin: 30 })])
    await vi.waitFor(() => expect(a.snapshot().local).toMatchObject({ fontSize: 28, pageMargin: 30 }))
    await vi.waitFor(() => expect(b.snapshot().local).toMatchObject({ fontSize: 28, pageMargin: 30 }))
  })
  it('never rolls a newer observed remote version back after a delayed acknowledgement', async () => {
    const s = store(), transport = api(), c = controller(transport, s); await link(c); await c.update({ fontSize: 24 })
    const response = deferred<ReaderPreferenceView>(); transport.put.mockImplementationOnce(() => response.promise)
    const flushing = c.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(2))
    const sent = transport.put.mock.calls[1][0], newer = view(5, { ...local, fontSize: 32 })
    await s.transact(row => ({ ...row, remote: newer })); response.resolve(view(2, sent.preferences)); await flushing
    expect(c.snapshot()).toMatchObject({ status: 'conflict', local: { fontSize: 24 }, remote: newer })
    expect((await s.snapshot())?.pending?.mutationId).toBe(sent.mutationId)
  })
  it('persists account rate limits across reloads and suppresses terminal automatic flush', async () => {
    const indexedDB = new IDBFactory(), s = store(indexedDB), transport = api(), c = controller(transport, s); await link(c); await c.update({ fontSize: 24 })
    transport.put.mockRejectedValueOnce(new ReaderPreferenceError('limit', undefined, 60)); await c.flush(); expect(c.snapshot().errorCode).toBe('limit')
    await c.close(); const reopened = controller(transport, store(indexedDB)); await reopened.start(local)
    expect(transport.put).toHaveBeenCalledTimes(2); expect(reopened.snapshot().errorCode).toBe('limit')
    await s.transact(row => ({ ...row, retryAfterUntil: 0 })); transport.put.mockRejectedValueOnce(new ReaderPreferenceError('authentication'))
    await reopened.refresh(); await reopened.flush(); await reopened.flush(); expect(transport.put).toHaveBeenCalledTimes(3)
    await reopened.refresh(); expect(transport.put).toHaveBeenCalledTimes(4)
  })
  it('recovers an initial storage open failure only after explicit retry', async () => {
    const s = store(), initialize = vi.spyOn(s, 'initialize').mockRejectedValueOnce(new ReaderPreferenceError('storage')), transport = api(), c = controller(transport, s)
    await c.start(local); expect(c.snapshot().errorCode).toBe('storage'); expect(transport.get).not.toHaveBeenCalled()
    await c.refresh(); expect(initialize).toHaveBeenCalledTimes(2); expect(c.snapshot().status).toBe('unlinked')
  })
  it('commits accepted local edits before account cleanup and ignores late network replies', async () => {
    const indexedDB = new IDBFactory(), s = store(indexedDB), response = deferred<ReaderPreferenceView>(), transport = { ...api(), get: vi.fn(() => response.promise) }, c = controller(transport, s)
    const starting = c.start(local), editing = c.update({ fontSize: 29 })
    await c.close(); await editing; s.close(); response.resolve(view(8, { ...local, fontSize: 36 })); await starting
    const persisted = await store(indexedDB).snapshot(); expect(persisted?.local.fontSize).toBe(29); expect(persisted?.remote).toBeNull()
  })
  it('keeps the newest conflict when a previous mutation responds after a new choice', async () => {
    const s = store(), transport = api(), c = controller(transport, s); await link(c); await c.update({ fontSize: 24 })
    const response = deferred<ReaderPreferenceView>(); transport.put.mockImplementationOnce(() => response.promise)
    const flushing = c.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(2))
    const current = view(5, { ...local, fontSize: 30 })
    await s.transact(row => ({ ...row, remote: current, conflict: current, pending: { ...row.pending!, mutationId: crypto.randomUUID(), expectedVersion: 4 } }))
    // Another tab has already made and conflicted a newer choice while this request was outstanding.
    const newer = view(6, { ...local, fontSize: 32 })
    response.reject(new ReaderPreferenceError('conflict', newer)); await flushing
    expect(c.snapshot()).toMatchObject({ status: 'conflict', remote: newer, local: { fontSize: 24 } })
    expect((await s.snapshot())?.pending?.expectedVersion).toBe(4)
  })
})
