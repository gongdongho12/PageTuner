import { IDBFactory } from 'fake-indexeddb'
import { afterEach, expect, it, vi } from 'vitest'
import { GlossaryRegistry } from './BookGlossaryProvider'
import { BookGlossaryError, type BookGlossaryView } from '../lib/bookGlossaryApi'
import { listBookGlossaryRecords } from '../lib/bookGlossaryStore'
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
