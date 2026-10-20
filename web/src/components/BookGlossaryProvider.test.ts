import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GlossaryRegistry } from './BookGlossaryProvider'
import { BookGlossaryError, type BookGlossaryView } from '../lib/bookGlossaryApi'
import { listBookGlossaryRecords } from '../lib/bookGlossaryStore'
import { glossaryExportErrors } from '../lib/portableGlossaryExport'
const registries: GlossaryRegistry[] = []
afterEach(() => { registries.splice(0).forEach(registry => registry.close()); vi.unstubAllGlobals() })
it('recovers a closed-editor account journal after restart while device glossary mode remains selected', async () => {
  vi.stubGlobal('indexedDB', new IDBFactory())
  const scope = { providerId: 'source', bookId: 'book', targetLanguage: 'ko' }
  let online = true, remote: BookGlossaryView = { ...scope, version: 0, entries: null, updatedAt: null }
  const api = { get: vi.fn(async () => { if (!online) throw new BookGlossaryError('network'); return structuredClone(remote) }),
    put: vi.fn(async (_scope: typeof scope, input: { expectedVersion: number; entries: BookGlossaryView['entries'] }) => {
      if (!online) throw new BookGlossaryError('network')
      remote = { ...scope, version: input.expectedVersion + 1, entries: input.entries, updatedAt: '2026-10-03T00:00:00Z' }; return structuredClone(remote)
    }), close: vi.fn() }
  const first = new GlossaryRegistry('alice', api); registries.push(first)
  const controller = first.open(scope); await first.read(scope); await controller.choose('server', controller.snapshot())
  online = false
  await controller.update([{ id: 'id', sourceTerm: 'Name', translatedTerm: '이름', displayTerm: '별칭', kind: 'Character', enabled: true, caseSensitive: false }], controller.snapshot())
  await controller.flush(); await controller.selectDevice(); first.release(scope)
  const sent = (await listBookGlossaryRecords('alice'))[0].record.pending
  first.close(); online = true
  const next = new GlossaryRegistry('alice', api); registries.push(next)
  await next.drain()
  expect(api.put.mock.calls.at(-1)![1]).toEqual(sent)
  expect((await listBookGlossaryRecords('alice'))[0].record).toMatchObject({ enabled: true, selected: false, pending: null, remote: { version: 1 } })
  expect(await listBookGlossaryRecords('bob')).toEqual([])
})

describe('fresh glossary observations', () => {
  const scope = { providerId: 'source', bookId: 'book', targetLanguage: 'ko' }
  const view = (version = 1): BookGlossaryView => ({ ...scope, version, entries: [], updatedAt: '2026-10-20T00:00:00Z' })
  function fixture(get: () => Promise<BookGlossaryView> = async () => view()) {
    vi.stubGlobal('indexedDB', new IDBFactory())
    const api = { get: vi.fn(get), put: vi.fn(async () => { throw new Error('No PUT during export') }), close: vi.fn() }
    const registry = new GlossaryRegistry('alice', api); registries.push(registry)
    let active = true
    return { api, registry, reader: registry.freshReader(() => active), setActive: (value: boolean) => { active = value } }
  }

  it.each(['older', 'changed'] as const)('rejects %s responses before replacing prior observations', async change => {
    let response = view(2)
    const f = fixture(async () => response)
    const first = await f.reader.read(scope)
    first.entries = null
    response = change === 'older' ? view(1) : { ...view(2), entries: null }
    await expect(f.reader.read(scope)).rejects.toThrow(glossaryExportErrors.response)
    response = view(1)
    await expect(f.reader.read(scope)).rejects.toThrow(glossaryExportErrors.response)
    expect(f.api.put).not.toHaveBeenCalled()
    expect(await listBookGlossaryRecords('alice')).toEqual([])
  })

  it('rejects overlapping older GETs that complete after a newer observation', async () => {
    let release!: (value: BookGlossaryView) => void, calls = 0
    const f = fixture(async () => ++calls === 1 ? new Promise<BookGlossaryView>(resolve => { release = resolve }) : view(2))
    const first = f.reader.read(scope)
    await vi.waitFor(() => expect(f.api.get).toHaveBeenCalledTimes(1))
    expect(await f.reader.read(scope)).toEqual(view(2))
    release(view(1))
    await expect(first).rejects.toThrow(glossaryExportErrors.response)
  })

  it('keeps stale or aborted readers terminal and discards late errors', async () => {
    const f = fixture(async () => { f.setActive(false); throw new BookGlossaryError('network') })
    await expect(f.reader.read(scope)).rejects.toThrow(glossaryExportErrors.stale)
    f.setActive(true)
    await expect(f.reader.read(scope)).rejects.toThrow(glossaryExportErrors.stale)
    expect(f.api.get).toHaveBeenCalledTimes(1)
    const g = fixture()
    await expect(g.reader.read(scope, AbortSignal.abort())).rejects.toThrow(glossaryExportErrors.stale)
    await expect(g.reader.read(scope)).rejects.toThrow(glossaryExportErrors.stale)
    expect(g.api.get).not.toHaveBeenCalled()
  })
})
