import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LibraryOrganizationError, type LibraryOrganizationMutation, type LibraryOrganizationView } from '../lib/libraryOrganizationApi'
import { createLibraryOrganizationStore, listLibraryOrganizationRecords, type LibraryOrganizationRecord } from '../lib/libraryOrganizationStore'
import { OrganizationRegistry } from './LibraryOrganizationProvider'
import { organizationExportErrors } from '../lib/portableOrganizationExport'

const scope = { kind: 'ORIGINAL' as const, recordId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' }
const registries: OrganizationRegistry[] = []
afterEach(() => { registries.splice(0).forEach(registry => registry.close()); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('account organization outbox', () => {
  it('retries an accepted offline edit after a stale draft is rejected and its editor is closed', async () => {
    vi.stubGlobal('indexedDB', new IDBFactory())
    let online = true
    let remote: LibraryOrganizationView = { ...scope, version: 0, organization: null, updatedAt: null }
    const api = {
      get: vi.fn(async () => structuredClone(remote)),
      put: vi.fn(async (_scope: typeof scope, input: LibraryOrganizationMutation) => {
        if (!online) throw new LibraryOrganizationError('network')
        remote = { ...scope, version: input.expectedVersion + 1, organization: input.organization, updatedAt: '2026-10-02T10:00:00Z' }
        return structuredClone(remote)
      }), close: vi.fn(),
    }
    const registry = new OrganizationRegistry('alice', api); registries.push(registry)
    const controller = registry.open(scope)
    await vi.waitFor(() => { expect(api.get).toHaveBeenCalledTimes(2); expect(controller.snapshot().status).toBe('unlinked') })
    await controller.choose('local', controller.snapshot())
    const staleDraft = controller.snapshot()
    online = false
    expect(await controller.update({ folder: 'Accepted offline shelf', tags: ['Preserved tag'] }, staleDraft)).toBe(true)
    await controller.flush()
    const sent = api.put.mock.calls.at(-1)![1]
    expect(controller.snapshot().errorCode).toBe('network')
    expect(await controller.update({ folder: 'Rejected old draft' }, staleDraft)).toBe(false)
    expect(controller.snapshot().errorCode).toBe('choice-stale')
    registry.release(scope)
    online = true
    // The provider calls this same drain on online, window focus and the 30-second timer.
    await registry.drain()
    expect(api.put).toHaveBeenCalledTimes(3)
    expect(api.put.mock.calls.at(-1)![1]).toEqual(sent)
    expect(controller.snapshot()).toMatchObject({ status: 'synced', local: sent.organization, remote: { version: 2, organization: sent.organization } })
    expect((await listLibraryOrganizationRecords('alice'))[0].record).toMatchObject({ pending: null, local: sent.organization })
  })
})

describe('read-only fresh account organization session', () => {
  const value = (version = 1, folder = 'Shelf'): LibraryOrganizationView => ({ ...scope, version,
    organization: version ? { folder, tags: ['MiXeD', 'NFD e\u0301', 'NFC é'], favorite: true } : null,
    updatedAt: version ? '2026-10-20T00:00:00Z' : null })
  function fixture(get: (input: typeof scope, signal?: AbortSignal) => Promise<LibraryOrganizationView> = async () => value()) {
    vi.stubGlobal('indexedDB', new IDBFactory())
    const api = { get: vi.fn(get), put: vi.fn(async () => { throw new Error('Export must not PUT') }),
      deferWritesUntil: vi.fn(), close: vi.fn() }
    const registry = new OrganizationRegistry('alice', api); registries.push(registry)
    let active = true
    const reader = registry.freshReader(() => active)
    return { api, registry, reader, setActive: (next: boolean) => { active = next } }
  }
  async function journal(remote = value(), change: Partial<LibraryOrganizationRecord> = {}) {
    const store = createLibraryOrganizationStore('alice', scope)
    try {
      await store.initialize({ folder: '', tags: [], favorite: false })
      return await store.transact(() => ({ enabled: true, local: remote.organization ?? { folder: '', tags: [], favorite: false },
        remote, pending: null, queued: null, conflict: null, retryAfterUntil: null, ...change }))
    } finally { store.close() }
  }
  function pending(remote = value()) {
    return { expectedVersion: remote.version, mutationId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', organization: remote.organization! }
  }

  it('performs direct GET only without creating a journal, controller, or write deferral', async () => {
    const f = fixture(), open = vi.spyOn(f.registry, 'open')
    expect(await f.reader.read(scope)).toEqual(value())
    await f.reader.checkReady(scope)
    expect(f.api.get).toHaveBeenCalledExactlyOnceWith(scope, undefined)
    expect(f.api.put).not.toHaveBeenCalled(); expect(f.api.deferWritesUntil).not.toHaveBeenCalled()
    expect(open).not.toHaveBeenCalled()
    expect(await listLibraryOrganizationRecords('alice')).toEqual([])
  })

  it('preserves absent and explicitly empty present values without normalizing Unicode or tags', async () => {
    const values = [value(0), value(1), { ...value(2), organization: { folder: '', tags: [], favorite: false } }]
    const f = fixture(async () => values.shift()!)
    expect(await f.reader.read(scope)).toEqual(value(0))
    expect(await f.reader.read(scope)).toEqual(value(1))
    expect((await f.reader.read(scope)).organization).toEqual({ folder: '', tags: [], favorite: false })
  })

  it('never mutates an existing disabled device journal or exposes aliases to observations', async () => {
    const response = value(), f = fixture(async () => response)
    const original = await journal(value(), { enabled: false })
    const read = await f.reader.read(scope)
    read.organization!.tags.push('display mutation')
    response.organization!.folder = 'API mutation'
    await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.response)
    expect((await listLibraryOrganizationRecords('alice'))[0].record).toEqual(original)
    expect(f.api.put).not.toHaveBeenCalled()
  })

  it.each(['pending', 'queued', 'conflict'] as const)('rejects %s before GET without flushing or changing the journal', async kind => {
    const f = fixture(), remote = value()
    const original = await journal(remote, { pending: pending(remote), ...(kind === 'queued' ? { queued: remote.organization } : {}),
      ...(kind === 'conflict' ? { conflict: remote } : {}) })
    await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.pending)
    expect(f.api.get).not.toHaveBeenCalled(); expect(f.api.put).not.toHaveBeenCalled()
    expect((await listLibraryOrganizationRecords('alice'))[0].record).toEqual(original)
  })

  it.each(['pending', 'queued', 'conflict'] as const)('rejects %s added while the direct GET was pending', async kind => {
    const remote = value(), f = fixture(async () => {
      await journal(remote, { pending: pending(remote), ...(kind === 'queued' ? { queued: remote.organization } : {}),
        ...(kind === 'conflict' ? { conflict: remote } : {}) })
      return remote
    })
    await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.pending)
    expect(f.api.put).not.toHaveBeenCalled()
  })

  it('rechecks a journal before final export readiness', async () => {
    const f = fixture()
    await f.reader.read(scope)
    await journal(value(), { pending: pending() })
    await expect(f.reader.checkReady(scope)).rejects.toThrow(organizationExportErrors.pending)
    expect(f.api.get).toHaveBeenCalledTimes(1)
  })

  it.each(['older', 'different'] as const)('rejects %s server data against the journal before and after GET', async change => {
    const f = fixture(async () => value())
    await journal(change === 'older' ? value(2) : value(1, 'Changed'))
    await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.response)
    const g = fixture(async () => { await journal(change === 'older' ? value(2) : value(1, 'Changed')); return value() })
    await expect(g.reader.read(scope)).rejects.toThrow(organizationExportErrors.response)
  })

  it.each(['older', 'different', 'timestamp'] as const)('compares %s responses with previous observations before replacing them', async change => {
    let next = value(2)
    const f = fixture(async () => structuredClone(next))
    await f.reader.read(scope)
    next = change === 'older' ? value(1) : change === 'different' ? value(2, 'Changed') : { ...value(2), updatedAt: '2026-10-20T00:00:01Z' }
    await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.response)
    // A rejected response cannot lower or replace the retained observation.
    next = value(1)
    await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.response)
    next = value(3)
    expect(await f.reader.read(scope)).toEqual(value(3))
  })

  it('rejects an overlapping older GET that finishes after the newer observation', async () => {
    let release!: (result: LibraryOrganizationView) => void
    let requests = 0
    const f = fixture(async () => ++requests === 1 ? new Promise<LibraryOrganizationView>(resolve => { release = resolve }) : value(2))
    const first = f.reader.read(scope)
    await vi.waitFor(() => expect(f.api.get).toHaveBeenCalledTimes(1))
    expect(await f.reader.read(scope)).toEqual(value(2))
    release(value(1))
    await expect(first).rejects.toThrow(organizationExportErrors.response)
  })

  it('rejects wrong scopes and malformed responses without starting synchronization', async () => {
    const f = fixture(async () => ({ ...value(), recordId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' }))
    await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.response)
    f.api.get.mockResolvedValue({ ...value(), version: -1 })
    await expect(f.reader.read(scope)).rejects.toThrow('invalid-response')
    expect(f.api.put).not.toHaveBeenCalled()
  })

  it('keeps invalidated sessions expired through A -> B -> A and rejects closed registries', async () => {
    const f = fixture()
    f.setActive(false)
    expect(() => f.reader.assertCurrent()).toThrow(organizationExportErrors.stale)
    f.setActive(true)
    await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.stale)
    expect(f.api.get).not.toHaveBeenCalled()
    const replacement = f.registry.freshReader(() => true)
    expect(await replacement.read(scope)).toEqual(value())
    f.registry.close()
    await expect(replacement.checkReady(scope)).rejects.toThrow(organizationExportErrors.stale)
  })

  it('rejects client/session replacement during a GET and discards a late success or failure', async () => {
    for (const failure of [false, true]) {
      const f = fixture(async () => {
        f.setActive(false)
        if (failure) throw new LibraryOrganizationError('network')
        return value()
      })
      await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.stale)
      f.setActive(true)
      await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.stale)
    }
  })

  it('makes aborted readers terminal before and after GET and does not call unavailable clients', async () => {
    const f = fixture(), cancelled = AbortSignal.abort()
    await expect(f.reader.read(scope, cancelled)).rejects.toThrow(organizationExportErrors.stale)
    await expect(f.reader.read(scope)).rejects.toThrow(organizationExportErrors.stale)
    expect(f.api.get).not.toHaveBeenCalled()
    const controller = new AbortController(), g = fixture(async () => { controller.abort(); return value() })
    await expect(g.reader.read(scope, controller.signal)).rejects.toThrow(organizationExportErrors.stale)
    expect(() => g.reader.assertCurrent()).toThrow(organizationExportErrors.stale)
    const offline = new OrganizationRegistry('alice', null); registries.push(offline)
    await expect(offline.freshReader(() => true).read(scope)).rejects.toThrow(organizationExportErrors.offline)
  })
})
