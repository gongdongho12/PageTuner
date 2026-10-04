import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LibraryOrganizationError, type LibraryOrganizationClient, type LibraryOrganizationMutation, type LibraryOrganizationView, type LibraryOrganization } from './libraryOrganizationApi'
import { createLibraryOrganizationStore, type LibraryOrganizationStore } from './libraryOrganizationStore'
import { createLibraryOrganizationController, type LibraryOrganizationController } from './libraryOrganizationSync'

const scope = { kind: 'ORIGINAL' as const, recordId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' }
const local: LibraryOrganization = { folder: 'Shelf20', tags: [], favorite: false }
const empty: LibraryOrganizationView = { ...scope, version: 0, organization: null, updatedAt: null }
const view = (version: number, organization = local): LibraryOrganizationView => ({ ...scope, version, organization, updatedAt: `2026-09-30T12:00:${String(version).padStart(2, '0')}Z` })
const controllers: LibraryOrganizationController[] = [], stores: LibraryOrganizationStore[] = []
function store(indexedDB = new IDBFactory(), username = 'alice') { const s = createLibraryOrganizationStore(username, scope, { indexedDB }); stores.push(s); return s }
function controller(api: LibraryOrganizationClient | null, s = store()) { const c = createLibraryOrganizationController({ api, scope, store: s, debounceMs: 60_000 }); controllers.push(c); return c }
function api(initial = empty) {
  let remote = initial
  return { get: vi.fn(async () => structuredClone(remote)), put: vi.fn(async (_scope: typeof scope, input: LibraryOrganizationMutation) => { remote = view(input.expectedVersion + 1, input.organization); return remote }), close: vi.fn() }
}
function deferred<T>() { let resolve!: (value: T) => void, reject!: (reason: unknown) => void; const promise = new Promise<T>((r, e) => { resolve = r; reject = e }); return { promise, resolve, reject } }
async function link(c: LibraryOrganizationController) { await c.start(local); const s = c.snapshot(); await c.choose('local', { local: s.local, remote: s.remote }) }
afterEach(async () => { await Promise.all(controllers.splice(0).map(c => c.close())); stores.splice(0).forEach(s => s.close()); vi.restoreAllMocks() })

describe('durable organization controller', () => {
  it('refuses a stale editor baseline after remote polling and keeps the current journal untouched', async () => {
    const transport = api(), c = controller(transport); await link(c)
    const baseline = c.snapshot(), newer = view(2, { ...local, favorite: true })
    transport.get.mockResolvedValueOnce(newer); await c.refresh()
    expect(await c.update({ folder: 'Stale draft' }, baseline)).toBe(false)
    expect(c.snapshot()).toMatchObject({ errorCode: 'choice-stale', local: newer.organization, remote: newer })
    expect(transport.put).toHaveBeenCalledTimes(1)
    expect(await c.update({ folder: 'Reviewed draft' }, c.snapshot())).toBe(true)
    await c.flush(); expect(c.snapshot()).toMatchObject({ status: 'synced', local: { folder: 'Reviewed draft', favorite: true } })
  })
  it('refuses stale editor state after another tab edits even without a remote revision change', async () => {
    const indexedDB = new IDBFactory(), transport = api(), a = controller(transport, store(indexedDB)), b = controller(transport, store(indexedDB))
    await a.start(local); await b.start(local)
    const baseline = a.snapshot(); await b.update({ favorite: true })
    expect(await a.update({ folder: 'Stale' }, baseline)).toBe(false)
    expect(a.snapshot()).toMatchObject({ errorCode: 'choice-stale', local: { ...local, favorite: true } })
  })
  it('durably accepts explicit first choice before logout without waiting for network completion', async () => {
    const s = store(), transport = api(), c = controller(transport, s); await c.start(local)
    const choosing = c.choose('local', c.snapshot())
    await c.close(); await choosing
    expect(await s.snapshot()).toMatchObject({ enabled: true, local, pending: { expectedVersion: 0, organization: local } })
    expect(transport.put).not.toHaveBeenCalled()
  })
  it('requires an explicit initial choice and allows local edits while unlinked', async () => {
    const remote = view(3, { ...local, folder: 'Shelf30' }), transport = api(remote), c = controller(transport)
    await c.start(local); expect(c.snapshot()).toMatchObject({ status: 'unlinked', local, remote })
    await c.update({ tags: ['Tag24'] }); await c.flush(); expect(transport.put).not.toHaveBeenCalled()
    const current = c.snapshot(); await c.choose('server', current)
    expect(c.snapshot()).toMatchObject({ status: 'synced', enabled: true, local: remote.organization })
  })
  it('keeps an edit submitted during the initial GET and rejects choosing an empty server', async () => {
    const get = deferred<LibraryOrganizationView>(), transport = { ...api(), get: vi.fn(() => get.promise) }, c = controller(transport)
    const starting = c.start(local); await c.update({ folder: 'Shelf25' }); get.resolve(empty); await starting
    expect(c.snapshot().local.folder).toBe('Shelf25')
    await c.choose('server', c.snapshot()); expect(c.snapshot().errorCode).toBe('invalid-request')
    await c.choose('local', c.snapshot()); expect(c.snapshot()).toMatchObject({ status: 'synced', local: { folder: 'Shelf25' } })
  })
  it('replays the exact mutation after response loss and reopening', async () => {
    const indexedDB = new IDBFactory(), s = store(indexedDB), transport = api(), c = controller(transport, s)
    await link(c); await c.update({ folder: 'Shelf26' })
    transport.put.mockRejectedValueOnce(new LibraryOrganizationError('network')); await c.flush()
    const sent = transport.put.mock.calls.at(-1)![1]
    await c.close(); s.close()
    const reopened = controller(transport, store(indexedDB)); await reopened.start(local)
    expect(transport.put.mock.calls.at(-1)![1]).toEqual(sent)
    expect(reopened.snapshot()).toMatchObject({ status: 'synced', local: { folder: 'Shelf26' } })
  })
  it('preserves a newer queued edit while the previous request is in flight', async () => {
    const transport = api(), c = controller(transport); await link(c); await c.update({ folder: 'Shelf24' })
    const response = deferred<LibraryOrganizationView>(); transport.put.mockImplementationOnce(() => response.promise)
    const flushing = c.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(2))
    const sent = transport.put.mock.calls[1][1]; await c.update({ tags: ['Tag28'] }); response.resolve(view(2, sent.organization)); await flushing
    expect(transport.put).toHaveBeenCalledTimes(3)
    expect(transport.put.mock.calls[2][1]).toMatchObject({ expectedVersion: 2, organization: { folder: 'Shelf24', tags: ['Tag28'] } })
    expect(c.snapshot()).toMatchObject({ status: 'synced', local: { folder: 'Shelf24', tags: ['Tag28'] } })
  })
  it('persists conflicts and refuses choices from stale displayed local or remote snapshots', async () => {
    const indexedDB = new IDBFactory(), transport = api(), c = controller(transport, store(indexedDB)); await link(c); await c.update({ folder: 'Shelf24' })
    const remote = view(3, { ...local, folder: 'Shelf32' }); transport.put.mockRejectedValueOnce(new LibraryOrganizationError('conflict', remote)); await c.flush()
    const stale = c.snapshot(); expect(stale.status).toBe('conflict')
    await c.update({ tags: ['Tag32'] }); await c.choose('server', stale)
    expect(c.snapshot()).toMatchObject({ status: 'conflict', errorCode: 'choice-stale', local: { tags: ['Tag32'] } })
    await c.close()
    transport.get.mockResolvedValue(remote)
    const reopened = controller(transport, store(indexedDB)); await reopened.start(local)
    expect(reopened.snapshot()).toMatchObject({ status: 'conflict', local: { folder: 'Shelf24', tags: ['Tag32'] }, remote })
    await reopened.choose('local', reopened.snapshot())
    expect(transport.put.mock.calls.at(-1)![1]).toMatchObject({ expectedVersion: 3, organization: { tags: ['Tag32'] } })
  })
  it('atomically merges independent edits from two controllers', async () => {
    const indexedDB = new IDBFactory(), transport = api(), a = controller(transport, store(indexedDB)), b = controller(transport, store(indexedDB))
    await a.start(local); await b.start(local)
    await Promise.all([a.update({ folder: 'Shelf28' }), b.update({ tags: ['Tag30'] })])
    await vi.waitFor(() => expect(a.snapshot().local).toMatchObject({ folder: 'Shelf28', tags: ['Tag30'] }))
    await vi.waitFor(() => expect(b.snapshot().local).toMatchObject({ folder: 'Shelf28', tags: ['Tag30'] }))
  })
  it('never rolls a newer observed remote version back after a delayed acknowledgement', async () => {
    const s = store(), transport = api(), c = controller(transport, s); await link(c); await c.update({ folder: 'Shelf24' })
    const response = deferred<LibraryOrganizationView>(); transport.put.mockImplementationOnce(() => response.promise)
    const flushing = c.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(2))
    const sent = transport.put.mock.calls[1][1], newer = view(5, { ...local, folder: 'Shelf32' })
    await s.transact(row => ({ ...row, remote: newer })); response.resolve(view(2, sent.organization)); await flushing
    expect(c.snapshot()).toMatchObject({ status: 'conflict', local: { folder: 'Shelf24' }, remote: newer })
    expect((await s.snapshot())?.pending?.mutationId).toBe(sent.mutationId)
  })
  it('adopts newer fields atomically when a duplicate tab PUT receives a late conflict after acknowledgement', async () => {
    const indexedDB = new IDBFactory(), transport = api(), s = store(indexedDB), a = controller(transport, s), b = controller(transport, store(indexedDB))
    await link(a); await b.start(local); await a.update({ folder: 'Shelf24' })
    const first = deferred<LibraryOrganizationView>(), late = deferred<LibraryOrganizationView>()
    transport.put.mockImplementationOnce(() => first.promise).mockImplementationOnce(() => late.promise)
    const flushingA = a.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(2))
    const flushingB = b.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(3))
    const sent = transport.put.mock.calls[1][1]
    expect(transport.put.mock.calls[2][1]).toEqual(sent)
    first.resolve(view(2, sent.organization)); await flushingA
    const staleDraft = a.snapshot()
    expect((await s.snapshot())?.pending).toBeNull()
    const newer = view(3, { folder: 'Another device shelf', tags: ['Newest tag'], favorite: false })
    late.reject(new LibraryOrganizationError('conflict', newer)); await flushingB
    expect(await s.snapshot()).toMatchObject({ remote: newer, local: newer.organization, pending: null, conflict: null })
    expect(await a.update({ folder: 'Old draft' }, staleDraft)).toBe(false)
    expect(a.snapshot()).toMatchObject({ errorCode: 'choice-stale', local: newer.organization, remote: newer })
    expect(await a.update({ favorite: true }, a.snapshot())).toBe(true)
    await a.flush()
    expect(transport.put.mock.calls.at(-1)![1]).toMatchObject({ expectedVersion: 3, organization: { ...newer.organization, favorite: true } })
  })
  it('preserves a new pending edit when a previous duplicate request receives a late conflict', async () => {
    const indexedDB = new IDBFactory(), transport = api(), s = store(indexedDB), a = controller(transport, s), b = controller(transport, store(indexedDB))
    await link(a); await b.start(local); await a.update({ folder: 'Shelf24' })
    const first = deferred<LibraryOrganizationView>(), late = deferred<LibraryOrganizationView>()
    transport.put.mockImplementationOnce(() => first.promise).mockImplementationOnce(() => late.promise)
    const flushingA = a.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(2))
    const flushingB = b.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(3))
    first.resolve(view(2, transport.put.mock.calls[1][1].organization)); await flushingA
    await a.update({ tags: ['New local intent'] })
    const pending = (await s.snapshot())!.pending
    const newer = view(3, { ...local, folder: 'Remote shelf' })
    late.reject(new LibraryOrganizationError('conflict', newer)); await flushingB
    expect(await s.snapshot()).toMatchObject({ remote: newer, local: { folder: 'Shelf24', tags: ['New local intent'] }, pending, conflict: null })
  })
  it('keeps the latest synchronized fields when another tab observes a revision before a duplicate acknowledgement', async () => {
    const indexedDB = new IDBFactory(), transport = api(), s = store(indexedDB), a = controller(transport, s), b = controller(transport, store(indexedDB))
    await link(a); await b.start(local); await a.update({ folder: 'Shelf24' })
    const first = deferred<LibraryOrganizationView>(), late = deferred<LibraryOrganizationView>()
    transport.put.mockImplementationOnce(() => first.promise).mockImplementationOnce(() => late.promise)
    const flushingA = a.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(2))
    const flushingB = b.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(3))
    const accepted = view(2, transport.put.mock.calls[1][1].organization)
    first.resolve(accepted); await flushingA
    const newer = view(3, { ...local, folder: 'Latest shelf', tags: ['Latest tag'] })
    transport.get.mockResolvedValueOnce(newer); await a.refresh()
    late.resolve(accepted); await flushingB
    expect(await s.snapshot()).toMatchObject({ remote: newer, local: newer.organization, pending: null, conflict: null })
  })
  it('schedules the accepted outbox again after rejecting a stale draft', async () => {
    const s = store(), transport = api(), c = createLibraryOrganizationController({ api: transport, store: s, scope, debounceMs: 20 })
    controllers.push(c)
    await link(c); const staleDraft = c.snapshot()
    await c.update({ folder: 'Accepted offline change' }, staleDraft)
    transport.put.mockRejectedValueOnce(new LibraryOrganizationError('network')); await c.flush()
    const pending = (await s.snapshot())!.pending
    expect(await c.update({ tags: ['Rejected stale draft'] }, staleDraft)).toBe(false)
    await vi.waitFor(() => expect(c.snapshot()).toMatchObject({ status: 'synced', local: { folder: 'Accepted offline change', tags: [] } }))
    expect(transport.put.mock.calls.at(-1)![1]).toEqual(pending)
    expect((await s.snapshot())?.pending).toBeNull()
  })
  it('persists account rate limits across reloads and suppresses terminal automatic flush', async () => {
    const indexedDB = new IDBFactory(), s = store(indexedDB), transport = api(), c = controller(transport, s); await link(c); await c.update({ folder: 'Shelf24' })
    transport.put.mockRejectedValueOnce(new LibraryOrganizationError('limit', undefined, 60)); await c.flush(); expect(c.snapshot().errorCode).toBe('limit')
    await c.close(); const reopened = controller(transport, store(indexedDB)); await reopened.start(local)
    expect(transport.put).toHaveBeenCalledTimes(2); expect(reopened.snapshot().errorCode).toBe('limit')
    await s.transact(row => ({ ...row, retryAfterUntil: 0 })); transport.put.mockRejectedValueOnce(new LibraryOrganizationError('authentication'))
    await reopened.refresh(); await reopened.flush(); await reopened.flush(); expect(transport.put).toHaveBeenCalledTimes(3)
    await reopened.refresh(); expect(transport.put).toHaveBeenCalledTimes(4)
  })
  it('recovers an initial storage open failure only after explicit retry', async () => {
    const s = store(), initialize = vi.spyOn(s, 'initialize').mockRejectedValueOnce(new LibraryOrganizationError('storage')), transport = api(), c = controller(transport, s)
    await c.start(local); expect(c.snapshot().errorCode).toBe('storage'); expect(transport.get).not.toHaveBeenCalled()
    await c.refresh(); expect(initialize).toHaveBeenCalledTimes(2); expect(c.snapshot().status).toBe('unlinked')
  })
  it('commits accepted local edits before account cleanup and ignores late network replies', async () => {
    const indexedDB = new IDBFactory(), s = store(indexedDB), response = deferred<LibraryOrganizationView>(), transport = { ...api(), get: vi.fn(() => response.promise) }, c = controller(transport, s)
    const starting = c.start(local), editing = c.update({ folder: 'Shelf29' })
    await c.close(); await editing; s.close(); response.resolve(view(8, { ...local, folder: 'Shelf36' })); await starting
    const persisted = await store(indexedDB).snapshot(); expect(persisted?.local.folder).toBe('Shelf29'); expect(persisted?.remote).toBeNull()
  })
  it('keeps the newest conflict when a previous mutation responds after a new choice', async () => {
    const s = store(), transport = api(), c = controller(transport, s); await link(c); await c.update({ folder: 'Shelf24' })
    const response = deferred<LibraryOrganizationView>(); transport.put.mockImplementationOnce(() => response.promise)
    const flushing = c.flush(); await vi.waitFor(() => expect(transport.put).toHaveBeenCalledTimes(2))
    const current = view(5, { ...local, folder: 'Shelf30' })
    await s.transact(row => ({ ...row, remote: current, conflict: current, pending: { ...row.pending!, mutationId: crypto.randomUUID(), expectedVersion: 4 } }))
    // Another tab has already made and conflicted a newer choice while this request was outstanding.
    const newer = view(6, { ...local, folder: 'Shelf32' })
    response.reject(new LibraryOrganizationError('conflict', newer)); await flushing
    expect(c.snapshot()).toMatchObject({ status: 'conflict', remote: newer, local: { folder: 'Shelf24' } })
    expect((await s.snapshot())?.pending?.expectedVersion).toBe(4)
  })
})


