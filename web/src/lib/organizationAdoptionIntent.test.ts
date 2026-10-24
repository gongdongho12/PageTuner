import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LibraryOrganizationError, type LibraryOrganization, type LibraryOrganizationView } from './libraryOrganizationApi'
import { createLibraryOrganizationStore, type LibraryOrganizationRecord, type LibraryOrganizationStore } from './libraryOrganizationStore'
import { createLibraryOrganizationController, type LibraryOrganizationController } from './libraryOrganizationSync'

const scope = { kind: 'ORIGINAL' as const, recordId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' }
const empty: LibraryOrganization = { folder: '', tags: [], favorite: false }
const adopted: LibraryOrganization = { folder: '읽을  책', tags: ['é', 'e\u0301', 'Tag', 'tag'], favorite: true }
const absent: LibraryOrganizationView = { ...scope, version: 0, organization: null, updatedAt: null }
const view = (version: number, organization = adopted): LibraryOrganizationView => ({ ...scope, version, organization: structuredClone(organization), updatedAt: `2026-10-22T12:00:${String(version).padStart(2, '0')}Z` })
const stores: LibraryOrganizationStore[] = [], controllers: LibraryOrganizationController[] = []
function device(indexedDB = new IDBFactory()) {
  const store = createLibraryOrganizationStore('adoption-qa', scope, { indexedDB }); stores.push(store); return store
}
function transport(remote = absent) { return { get: vi.fn(async () => structuredClone(remote)), put: vi.fn(async () => view(remote.version + 1)), close: vi.fn() } }
function controller(store: LibraryOrganizationStore, api = transport(), debounceMs = 60_000) {
  const result = createLibraryOrganizationController({ store, api, scope, debounceMs }); controllers.push(result); return result
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }
async function seed(store: LibraryOrganizationStore, remote = view(1), retryAfterUntil: number | null = null) {
  return store.transactNullable(() => ({ enabled: true, local: structuredClone(remote.organization ?? empty), remote, pending: null, queued: null, conflict: null, retryAfterUntil }))
}
function dirty(record: LibraryOrganizationRecord, kind: 'pending' | 'queued' | 'conflict'): LibraryOrganizationRecord {
  const pending = { mutationId: crypto.randomUUID(), expectedVersion: record.remote!.version, organization: structuredClone(record.local) }
  return { ...record, pending, queued: kind === 'queued' ? structuredClone(record.local) : null, conflict: kind === 'conflict' ? structuredClone(record.remote) : null }
}
afterEach(async () => { await Promise.all(controllers.splice(0).map(c => c.close())); stores.splice(0).forEach(store => store.close()); vi.restoreAllMocks() })

describe('explicit organization snapshot CAS intent foundation', () => {
  it('creates a fresh empty PUT intent for an absent server and missing nullable device row, without initialize or PUT', async () => {
    const store = device(), api = transport(), c = controller(store, api), initialize = vi.spyOn(store, 'initialize'), guard = vi.fn()
    expect(await c.adoptSnapshot(empty, { record: null, remote: absent }, guard)).toBe(true)
    expect(await store.snapshot()).toMatchObject({ enabled: true, local: empty, remote: absent, pending: { expectedVersion: 0, organization: empty }, queued: null, conflict: null })
    expect((await store.snapshot())!.pending!.mutationId).toMatch(/^[0-9a-f-]{36}$/)
    expect(initialize).not.toHaveBeenCalled(); expect(api.get).toHaveBeenCalledTimes(1); expect(api.put).not.toHaveBeenCalled(); expect(guard).toHaveBeenCalled()
  })

  it('makes a new mutation even for an identical already synchronized value and preserves the retry deadline', async () => {
    const store = device(), remote = view(1), deadline = Date.now() + 60_000, record = await seed(store, remote, deadline), api = transport(remote), c = controller(store, api)
    expect(await c.adoptSnapshot(adopted, { record, remote }, () => {})).toBe(true)
    const first = (await store.snapshot())!.pending!
    const nextRemote = view(2), nextRecord = await store.transactNullable(current => ({ ...current!, remote: nextRemote, pending: null }))
    api.get.mockResolvedValue(nextRemote)
    expect(await c.adoptSnapshot(adopted, { record: nextRecord, remote: nextRemote }, () => {})).toBe(true)
    const persisted = (await store.snapshot())!
    expect(persisted.pending).toMatchObject({ expectedVersion: 2, organization: adopted })
    expect(persisted.pending!.mutationId).not.toBe(first.mutationId); expect(persisted.retryAfterUntil).toBe(deadline); expect(api.put).not.toHaveBeenCalled()
  })

  it('compares all journal fields semantically rather than depending on object property order', async () => {
    const store = device(), remote = view(2), record = await seed(store, remote), api = transport(remote), c = controller(store, api)
    const reordered: LibraryOrganizationRecord = { retryAfterUntil: record.retryAfterUntil, conflict: record.conflict, queued: record.queued, pending: record.pending,
      remote: { updatedAt: remote.updatedAt, organization: { favorite: adopted.favorite, tags: [...adopted.tags], folder: adopted.folder }, version: remote.version, recordId: remote.recordId, kind: remote.kind },
      local: { favorite: adopted.favorite, tags: [...adopted.tags], folder: adopted.folder }, enabled: record.enabled }
    expect(await c.adoptSnapshot(adopted, { record: reordered, remote }, () => {})).toBe(true)
    expect((await store.snapshot())!.pending!.organization).toEqual(adopted)
  })

  for (const kind of ['pending', 'queued', 'conflict'] as const) {
    it(`rejects a preexisting ${kind} journal without GET, replacement or automatic flush`, async () => {
      const store = device(), clean = await seed(store), record = await store.transactNullable(() => dirty(clean, kind)), api = transport(view(1)), c = controller(store, api)
      expect(await c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})).toBe(false)
      expect(await store.snapshot()).toEqual(record); expect(api.get).not.toHaveBeenCalled(); expect(api.put).not.toHaveBeenCalled()
      expect(c.snapshot().errorCode).toBe('choice-stale')
    })
    it(`rejects another tab's ${kind} write while GET is held and never activates its outbox`, async () => {
      const indexedDB = new IDBFactory(), store = device(indexedDB), other = device(indexedDB), record = await seed(store), api = transport(), held = deferred<LibraryOrganizationView>()
      api.get.mockImplementation(() => held.promise)
      const c = controller(store, api, 0), taking = c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})
      await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))
      const changed = await other.transactNullable(current => dirty(current!, kind)); held.resolve(view(1))
      expect(await taking).toBe(false); expect(await store.snapshot()).toEqual(changed); expect(api.put).not.toHaveBeenCalled()
      await new Promise(resolve => setTimeout(resolve, 25))
      expect(api.put).not.toHaveBeenCalled()
      expect(c.snapshot().errorCode).toBe('choice-stale')
    })
  }

  it('atomically rejects concurrent creation of a previously missing row and keeps the other tab local base', async () => {
    const indexedDB = new IDBFactory(), store = device(indexedDB), other = device(indexedDB), api = transport(), held = deferred<LibraryOrganizationView>()
    api.get.mockImplementation(() => held.promise)
    const c = controller(store, api), taking = c.adoptSnapshot(adopted, { record: null, remote: absent }, () => {})
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))
    const changed = await other.initialize({ folder: 'Other tab draft', tags: [], favorite: false }); held.resolve(absent)
    expect(await taking).toBe(false); expect(await store.snapshot()).toEqual(changed); expect(api.put).not.toHaveBeenCalled()
  })

  it('accepts exactly one of two simultaneous adoptions from the same missing nullable base', async () => {
    const indexedDB = new IDBFactory(), first = device(indexedDB), second = device(indexedDB)
    const apiA = transport(), apiB = transport(), held = deferred<LibraryOrganizationView>()
    apiA.get.mockImplementation(() => held.promise); apiB.get.mockImplementation(() => held.promise)
    const a = controller(first, apiA), b = controller(second, apiB)
    const takingA = a.adoptSnapshot(adopted, { record: null, remote: absent }, () => {})
    const takingB = b.adoptSnapshot(empty, { record: null, remote: absent }, () => {})
    await vi.waitFor(() => { expect(apiA.get).toHaveBeenCalledTimes(1); expect(apiB.get).toHaveBeenCalledTimes(1) })
    held.resolve(absent)
    const accepted = await Promise.all([takingA, takingB])
    expect(accepted.filter(Boolean)).toHaveLength(1)
    const saved = (await first.snapshot())!
    expect(saved).toEqual(await second.snapshot())
    expect(saved.pending).toMatchObject({ expectedVersion: 0, organization: accepted[0] ? adopted : empty })
    expect(apiA.put).not.toHaveBeenCalled(); expect(apiB.put).not.toHaveBeenCalled()
  })

  it('delivers the newly accepted empty intent through the ordinary CAS outbox', async () => {
    const store = device(), api = transport(), accepted = view(1, empty), c = controller(store, api)
    api.put.mockResolvedValue(accepted)
    expect(await c.adoptSnapshot(empty, { record: null, remote: absent }, () => {})).toBe(true)
    const intent = structuredClone((await store.snapshot())!.pending!)
    await c.flush()
    expect(api.put).toHaveBeenCalledExactlyOnceWith(scope, intent, expect.any(AbortSignal))
    expect(await store.snapshot()).toMatchObject({ enabled: true, local: empty, remote: accepted, pending: null, queued: null, conflict: null })
  })

  it('rejects a changed retry deadline atomically without clearing or extending it', async () => {
    const store = device(), record = await seed(store, view(1), 123), api = transport(view(1)), c = controller(store, api), tx = store.transactNullable.bind(store)
    vi.spyOn(store, 'transactNullable').mockImplementationOnce(async (reducer, guard) => {
      await tx(current => ({ ...current!, retryAfterUntil: 456 }))
      return tx(reducer, guard)
    })
    expect(await c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})).toBe(false)
    expect(await store.snapshot()).toEqual({ ...record, retryAfterUntil: 456 }); expect(api.put).not.toHaveBeenCalled()
  })

  for (const [name, response, code] of [
    ['newer server version', view(2), 'choice-stale'],
    ['version regression', absent, 'invalid-response'],
    ['same-version changed value', view(1, empty), 'invalid-response'],
    ['same-version changed timestamp', { ...view(1), updatedAt: '2026-10-22T12:01:01Z' }, 'invalid-response'],
    ['another scope', { ...view(1), kind: 'TRANSLATION' as const }, 'invalid-response'],
  ] as const) {
    it(`rejects ${name} without writing the nullable journal`, async () => {
      const store = device(), record = await seed(store), api = transport(response), c = controller(store, api)
      expect(await c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})).toBe(false)
      expect(await store.snapshot()).toEqual(record); expect(c.snapshot().errorCode).toBe(code); expect(api.put).not.toHaveBeenCalled()
    })
  }

  it('remembers a newer directly observed response even when stale comparison prevented persistence', async () => {
    const store = device(), record = await seed(store), api = transport(view(2)), c = controller(store, api)
    expect(await c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})).toBe(false)
    api.get.mockResolvedValue(view(1))
    expect(await c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})).toBe(false)
    expect(api.get).toHaveBeenCalledTimes(1); expect(c.snapshot().errorCode).toBe('invalid-response'); expect(await store.snapshot()).toEqual(record)
  })

  it('rechecks retained actor highwater in a queued reducer after a concurrent comparison observes a newer server', async () => {
    const store = device(), record = await seed(store), api = transport(view(1)), c = controller(store, api), tx = store.transactNullable.bind(store)
    const reached = deferred<void>(), release = deferred<void>()
    api.get.mockResolvedValueOnce(view(1)).mockResolvedValueOnce(view(2))
    vi.spyOn(store, 'transactNullable').mockImplementationOnce(async reducer => {
      reached.resolve(undefined); await release.promise
      // Deliberately isolate the reducer check from the store's final guard.
      return tx(reducer)
    })
    const first = c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})
    await reached.promise
    expect(await c.adoptSnapshot(adopted, { record, remote: view(1) }, () => {})).toBe(false)
    expect(c.snapshot().errorCode).toBe('choice-stale')
    release.resolve(undefined)
    expect(await first).toBe(false); expect(c.snapshot().errorCode).toBe('invalid-response')
    expect(await store.snapshot()).toEqual(record); expect(api.put).not.toHaveBeenCalled()
  })

  it('rechecks retained actor highwater in the final write guard after the reducer already produced an older intent', async () => {
    const store = device(), record = await seed(store), api = transport(view(1)), c = controller(store, api), tx = store.transactNullable.bind(store)
    const reached = deferred<void>(), release = deferred<void>()
    api.get.mockResolvedValueOnce(view(1)).mockResolvedValueOnce(view(2))
    vi.spyOn(store, 'transactNullable').mockImplementationOnce(async (reducer, guard) => {
      const next = reducer(await store.snapshot())
      reached.resolve(undefined); await release.promise
      // Simulate the last abortable write callback independently of the earlier reducer.
      guard!()
      return tx(() => next, guard)
    })
    const first = c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})
    await reached.promise
    expect(await c.adoptSnapshot(adopted, { record, remote: view(1) }, () => {})).toBe(false)
    expect(c.snapshot().errorCode).toBe('choice-stale')
    release.resolve(undefined)
    expect(await first).toBe(false); expect(c.snapshot().errorCode).toBe('invalid-response')
    expect(await store.snapshot()).toEqual(record); expect(api.put).not.toHaveBeenCalled()
  })

  for (const response of [view(1, empty), { ...view(1), kind: 'TRANSLATION' as const }]) {
    it(`revokes queued adoption after a concurrent invalid ${response.kind === scope.kind ? 'same-version content' : 'scope'} response`, async () => {
      const store = device(), record = await seed(store), api = transport(view(1)), c = controller(store, api), tx = store.transactNullable.bind(store)
      const reached = deferred<void>(), release = deferred<void>()
      const reloadReached = deferred<void>(), reload = deferred<LibraryOrganizationRecord | null>()
      api.get.mockResolvedValueOnce(view(1)).mockImplementationOnce(async () => {
        vi.spyOn(store, 'snapshot').mockImplementationOnce(() => { reloadReached.resolve(undefined); return reload.promise })
        return response
      })
      vi.spyOn(store, 'transactNullable').mockImplementationOnce(async (reducer, guard) => {
        const next = reducer(await store.snapshot())
        reached.resolve(undefined); await release.promise
        guard!()
        return tx(() => next, guard)
      })
      const first = c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})
      await reached.promise
      let secondFinished = false
      const second = c.adoptSnapshot(adopted, { record, remote: view(1) }, () => {}).then(result => { secondFinished = true; return result })
      await reloadReached.promise
      release.resolve(undefined)
      expect(await first).toBe(false); expect(await store.snapshot()).toEqual(record); expect(api.put).not.toHaveBeenCalled()
      expect(secondFinished).toBe(false)
      reload.resolve(record)
      expect(await second).toBe(false); expect(c.snapshot().errorCode).toBe('invalid-response')
      // A fresh comparison requires a fresh actor after invalid observations.
      expect(await c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})).toBe(false)
      expect(api.get).toHaveBeenCalledTimes(2)
      const fresh = controller(store, api)
      expect(await fresh.adoptSnapshot(empty, { record, remote: view(1) }, () => {})).toBe(true)
      expect(api.get).toHaveBeenCalledTimes(3); expect(api.put).not.toHaveBeenCalled()
    })
  }

  it('revokes a queued adoption immediately when ordinary refresh detects invalid content before its held display reload', async () => {
    const store = device(), record = await seed(store), api = transport(view(1)), c = controller(store, api), tx = store.transactNullable.bind(store)
    await c.start(adopted)
    api.get.mockClear()
    const reached = deferred<void>(), release = deferred<void>(), reloadReached = deferred<void>(), reload = deferred<LibraryOrganizationRecord | null>()
    api.get.mockResolvedValueOnce(view(1)).mockImplementationOnce(async () => {
      vi.spyOn(store, 'snapshot').mockImplementationOnce(() => { reloadReached.resolve(undefined); return reload.promise })
      return view(1, empty)
    })
    vi.spyOn(store, 'transactNullable').mockImplementationOnce(async (reducer, guard) => {
      const next = reducer(await store.snapshot())
      reached.resolve(undefined); await release.promise
      guard!()
      return tx(() => next, guard)
    })
    const taking = c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})
    await reached.promise
    let refreshFinished = false
    const refreshing = c.refresh().then(() => { refreshFinished = true })
    await reloadReached.promise
    release.resolve(undefined)
    expect(await taking).toBe(false)
    expect(refreshFinished).toBe(false); expect(await store.snapshot()).toEqual(record); expect(api.put).not.toHaveBeenCalled()
    reload.resolve(record); await refreshing
    expect(c.snapshot().errorCode).toBe('invalid-response')
    expect(await c.adoptSnapshot(empty, { record, remote: view(1) }, () => {})).toBe(false)
    expect(api.get).toHaveBeenCalledTimes(2); expect(api.put).not.toHaveBeenCalled()
  })

  it('rejects malformed or wrong-scope comparisons before contacting the server', async () => {
    const store = device(), api = transport(), c = controller(store, api)
    expect(await c.adoptSnapshot(empty, { record: null, remote: { ...absent, kind: 'TRANSLATION' } }, () => {})).toBe(false)
    expect(await c.adoptSnapshot(empty, { record: { enabled: false } as LibraryOrganizationRecord, remote: absent }, () => {})).toBe(false)
    expect(api.get).not.toHaveBeenCalled(); expect(await store.snapshot()).toBeNull()
  })

  it('freezes caller input, expected base and API reply while authority guards run', async () => {
    const store = device(), record = await seed(store), remote = view(1), api = transport(), held = deferred<LibraryOrganizationView>(), input = structuredClone(adopted), expected = { record: structuredClone(record), remote: structuredClone(remote) }
    api.get.mockImplementation(() => held.promise)
    let resolved = false
    const c = controller(store, api), taking = c.adoptSnapshot(input, expected, () => { if (resolved) remote.organization!.folder = 'Mutable transport object' })
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))
    input.tags.reverse(); expected.record!.local.folder = 'Mutated caller base'; expected.remote.organization!.tags.reverse()
    resolved = true; held.resolve(remote)
    expect(await taking).toBe(true); expect((await store.snapshot())!.pending!.organization).toEqual(adopted)
  })

  it('requires caller authority before GET and discards revoked authority after a held GET', async () => {
    const store = device(), api = transport(), c = controller(store, api)
    let current = false
    const guard = () => { if (!current) throw new LibraryOrganizationError('aborted') }
    expect(await c.adoptSnapshot(empty, { record: null, remote: absent }, guard)).toBe(false); expect(api.get).not.toHaveBeenCalled()
    const held = deferred<LibraryOrganizationView>(); api.get.mockImplementation(() => held.promise); current = true
    const taking = c.adoptSnapshot(empty, { record: null, remote: absent }, guard)
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(1)); current = false; held.resolve(absent)
    expect(await taking).toBe(false); expect(await store.snapshot()).toBeNull(); expect(api.put).not.toHaveBeenCalled()
  })

  it('checks caller authority in the reducer and again at the last abortable store boundary', async () => {
    const store = device(), api = transport(), c = controller(store, api), tx = store.transactNullable.bind(store)
    let current = true
    vi.spyOn(store, 'transactNullable').mockImplementationOnce((reducer, guard) => tx(record => { const next = reducer(record); current = false; return next }, guard))
    expect(await c.adoptSnapshot(empty, { record: null, remote: absent }, () => { if (!current) throw new LibraryOrganizationError('aborted') })).toBe(false)
    expect(await store.snapshot()).toBeNull(); expect(api.put).not.toHaveBeenCalled()
  })

  it('close aborts an in-flight GET and cannot create a journal from its late reply', async () => {
    const store = device(), api = transport(), held = deferred<LibraryOrganizationView>(); api.get.mockImplementation(() => held.promise)
    const c = controller(store, api), taking = c.adoptSnapshot(empty, { record: null, remote: absent }, () => {})
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(1)); const closing = c.close(); held.resolve(absent)
    expect(await taking).toBe(false); await closing; expect(await store.snapshot()).toBeNull(); expect(api.put).not.toHaveBeenCalled()
  })

  it('reports durable acceptance if the session closes after transaction completion', async () => {
    const store = device(), api = transport(), c = controller(store, api), tx = store.transactNullable.bind(store)
    vi.spyOn(store, 'transactNullable').mockImplementationOnce(async (reducer, guard) => { const value = await tx(reducer, guard); void c.close(); return value })
    expect(await c.adoptSnapshot(empty, { record: null, remote: absent }, () => {})).toBe(true)
    expect((await store.snapshot())!.pending).toMatchObject({ expectedVersion: 0, organization: empty }); expect(api.put).not.toHaveBeenCalled()
  })

  it('keeps durable acceptance and notifies remaining observers when onChange or a subscriber throws', async () => {
    const store = device(), api = transport(), failed = vi.fn(() => { throw new Error('UI observer failure') }), healthy = vi.fn()
    const c = createLibraryOrganizationController({ store, api, scope, onChange: failed, debounceMs: 60_000 }); controllers.push(c)
    c.subscribe(failed); c.subscribe(healthy)
    expect(await c.adoptSnapshot(empty, { record: null, remote: absent }, () => {})).toBe(true)
    expect((await store.snapshot())!.pending).toMatchObject({ expectedVersion: 0, organization: empty })
    expect(failed).toHaveBeenCalled(); expect(healthy).toHaveBeenCalledWith(expect.objectContaining({ status: 'pending', enabled: true })); expect(api.put).not.toHaveBeenCalled()
  })

  it('returns accepted after a postcommit reload fails even if its error observer throws', async () => {
    const store = device(), api = transport(), c = controller(store, api), tx = store.transactNullable.bind(store)
    c.subscribe(() => { throw new Error('UI observer failure') })
    vi.spyOn(store, 'transactNullable').mockImplementationOnce(async (reducer, guard) => {
      const value = await tx(reducer, guard)
      vi.spyOn(store, 'snapshot').mockRejectedValue(new LibraryOrganizationError('storage'))
      return value
    })
    expect(await c.adoptSnapshot(empty, { record: null, remote: absent }, () => {})).toBe(true)
    expect(c.snapshot().errorCode).toBe('storage'); expect(api.put).not.toHaveBeenCalled()
  })
})
