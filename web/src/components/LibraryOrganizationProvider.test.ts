import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LibraryOrganizationError, type LibraryOrganizationMutation, type LibraryOrganizationView } from '../lib/libraryOrganizationApi'
import { listLibraryOrganizationRecords } from '../lib/libraryOrganizationStore'
import { OrganizationRegistry } from './LibraryOrganizationProvider'

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
