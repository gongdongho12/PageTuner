import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { BookGlossaryError, type BookGlossary, type BookGlossaryClient, type BookGlossaryEntry, type BookGlossaryView } from './bookGlossaryApi'
import { createBookGlossaryStore } from './bookGlossaryStore'
import { createBookGlossaryController } from './bookGlossarySync'
import { bookGlossaryWorkflow, selectedBookGlossary } from './bookGlossaryProjection'
import { glossaryRevision, normalizeGlossary } from './glossary'
import { sha256 } from './validation'

const scope = { providerId: 'source:A', bookId: 'book/B:原文', targetLanguage: 'ko' }
const term = (id = 'stable-id', extra: Partial<BookGlossaryEntry> = {}): BookGlossaryEntry => ({ id, sourceTerm: ' Alice ', translatedTerm: ' 앨리스 ', displayTerm: ' 아리 ', kind: 'Character', caseSensitive: false, enabled: true, ...extra })
const at = '2026-10-03T00:00:00Z'
const resources: { controller: ReturnType<typeof createBookGlossaryController>; store: ReturnType<typeof createBookGlossaryStore> }[] = []
afterEach(async () => { for (const item of resources.splice(0)) { await item.controller.close(); item.store.close() } vi.unstubAllGlobals() })
function setup(initial: BookGlossary = null, version = 0, factory = new IDBFactory()) {
  let remote: BookGlossaryView = { ...scope, entries: initial, version, updatedAt: version ? at : null }, online = true
  const api: BookGlossaryClient = { get: vi.fn(async () => { if (!online) throw new BookGlossaryError('network'); return structuredClone(remote) }),
    put: vi.fn(async (_scope, input) => { if (!online) throw new BookGlossaryError('network'); if (input.expectedVersion !== remote.version) throw new BookGlossaryError('conflict', structuredClone(remote)); remote = { ...scope, entries: input.entries, version: remote.version + 1, updatedAt: at }; return structuredClone(remote) }), close: vi.fn() }
  const open = (username = 'alice', key = scope) => {
    const store = createBookGlossaryStore(username, key, { indexedDB: factory }), controller = createBookGlossaryController({ store, api, scope: key, debounceMs: 100_000 })
    resources.push({ store, controller }); return { store, controller }
  }
  return { api, open, setOnline: (value: boolean) => { online = value }, setRemote: (entries: BookGlossary, version: number) => { remote = { ...scope, entries, version, updatedAt: version ? at : null } } }
}
describe('account book glossary durable selection and conflicts', () => {
  it('leaves an absent snapshot and old local glossary unlinked until explicit adoption', async () => {
    const env = setup(), { controller, store } = env.open(); await controller.start([term()])
    expect(controller.snapshot()).toMatchObject({ enabled: false, local: [term()], remote: { version: 0, entries: null } })
    expect(env.api.put).not.toHaveBeenCalled()
    expect(await controller.adoptLegacy([{ source: 'Alice', target: '앨리스' }], controller.snapshot())).toBe(true)
    const id = controller.snapshot().local![0].id
    await controller.flush(); await controller.close(); store.close()
    const reopened = env.open(); await reopened.controller.start()
    expect(reopened.controller.snapshot().local![0].id).toBe(id)
    expect(await reopened.controller.adoptLegacy([{ source: 'Alice', target: '새 표기' }], reopened.controller.snapshot())).toBe(true)
    expect(reopened.controller.snapshot().local![0].id).toBe(id)
  })
  it('never resurrects a nonempty initial fallback after selecting a remote deletion', async () => {
    const env = setup([term()], 1), { controller } = env.open(); await controller.start([term('old-local')])
    await controller.choose('server', controller.snapshot())
    env.setRemote(null, 2); await controller.refresh()
    expect(controller.snapshot()).toMatchObject({ enabled: true, local: null, remote: { entries: null, version: 2 } })
    expect(selectedBookGlossary(controller.snapshot(), [{ source: 'Old', target: '예전' }])).toEqual([])
  })
  it('keeps an immutable uncertain mutation and restores the latest local edit after reopening offline', async () => {
    const env = setup([term()], 1), first = env.open(); await first.controller.start(); await first.controller.choose('server', first.controller.snapshot())
    env.setOnline(false); await first.controller.update([term('stable-id', { displayTerm: 'first' })], first.controller.snapshot()); await first.controller.flush()
    const pending = (await first.store.snapshot())!.pending
    await first.controller.update(null, first.controller.snapshot()); await first.controller.close(); first.store.close()
    const next = env.open(); await next.controller.start()
    expect((await next.store.snapshot())!.pending).toEqual(pending)
    expect(next.controller.snapshot().local).toBeNull()
    env.setOnline(true); await next.controller.refresh()
    expect(next.controller.snapshot()).toMatchObject({ status: 'synced', local: null, remote: { version: 3, entries: null } })
  })
  it('preserves every field/order and rejects a stale conflict choice', async () => {
    const env = setup([term()], 1), { controller } = env.open(); await controller.start(); await controller.choose('server', controller.snapshot())
    const local = [term('z', { enabled: false, kind: 'Place' }), term('a')]
    await controller.update(local, controller.snapshot()); env.setRemote([term('remote')], 2); await controller.flush()
    const choice = controller.snapshot(); expect(choice).toMatchObject({ status: 'conflict', local })
    env.setRemote(null, 3); await controller.refresh()
    expect(await controller.choose('local', choice)).toBe(false)
    expect(controller.snapshot().local).toEqual(local)
    expect(await controller.choose('server', controller.snapshot())).toBe(true)
    expect(controller.snapshot().local).toBeNull()
  })
  it('rejects a wire-oversize edit before persisting it and allows a smaller edit afterwards', async () => {
    const env = setup(), { controller, store } = env.open(); await controller.start(); await controller.choose('server', controller.snapshot())
    const huge = Array.from({ length: 500 }, (_, i) => term(`${i}${'字'.repeat(195)}`, { sourceTerm: '字'.repeat(200), translatedTerm: '字'.repeat(200), displayTerm: '字'.repeat(200) }))
    expect(await controller.update(huge, controller.snapshot())).toBe(false)
    expect((await store.snapshot())!.pending).toBeNull()
    expect(await controller.update([term()], controller.snapshot())).toBe(true)
    await controller.flush(); expect(controller.snapshot().status).toBe('synced')
  })
  it('switches back to the device glossary without discarding or stranding a pending account edit', async () => {
    const env = setup([term()], 1), { controller, store } = env.open(); await controller.start(); await controller.choose('server', controller.snapshot())
    env.setOnline(false); await controller.update([term('pending')], controller.snapshot()); await controller.flush()
    const pending = (await store.snapshot())!.pending
    expect(await controller.selectDevice()).toBe(true)
    expect(selectedBookGlossary(controller.snapshot(), [{ source: 'Local', target: '기기' }])).toEqual([{ source: 'Local', target: '기기' }])
    expect((await store.snapshot())!.pending).toEqual(pending)
    env.setOnline(true); await controller.refresh()
    expect(controller.snapshot()).toMatchObject({ enabled: false, linked: true, remote: { version: 2, entries: [term('pending')] } })
    await controller.choose('server', controller.snapshot()); expect(controller.snapshot()).toMatchObject({ enabled: true, local: [term('pending')] })
  })
  it('retains stable adoption IDs across more than 1000 historical source terms', async () => {
    const env = setup(), { controller } = env.open(); await controller.start()
    const legacy = Array.from({ length: 500 }, (_, index) => ({ source: `A${index}`, target: 'A' }))
    expect(await controller.adoptLegacy(legacy, controller.snapshot())).toBe(true)
    const firstId = controller.snapshot().local![0].id
    expect(await controller.adoptLegacy(legacy.map(e => ({ ...e, source: `B${e.source}` })), controller.snapshot())).toBe(true)
    expect(await controller.adoptLegacy([{ source: '1001st', target: 'C' }], controller.snapshot())).toBe(true)
    expect(await controller.adoptLegacy(legacy, controller.snapshot())).toBe(true)
    expect(controller.snapshot().local![0].id).toBe(firstId)
  })
  it('retains ACK at version ceiling and newer intent as a resolvable conflict', async () => {
    const env = setup([term()], Number.MAX_SAFE_INTEGER - 1), { controller, store } = env.open(); await controller.start(); await controller.choose('server', controller.snapshot())
    await controller.update([term('first')], controller.snapshot())
    let answer!: (view: BookGlossaryView) => void
    vi.mocked(env.api.put).mockImplementationOnce(() => new Promise(resolve => { answer = resolve }))
    const flushing = controller.flush(); await vi.waitFor(() => expect(answer).toBeTypeOf('function'))
    await controller.update([term('newer')], controller.snapshot())
    answer({ ...scope, version: Number.MAX_SAFE_INTEGER, entries: [term('first')], updatedAt: at }); await flushing
    expect(controller.snapshot()).toMatchObject({ status: 'conflict', local: [term('newer')], remote: { version: Number.MAX_SAFE_INTEGER } })
    expect((await store.snapshot())!.pending).not.toBeNull()
    expect(await controller.choose('server', controller.snapshot())).toBe(true)
    expect(controller.snapshot()).toMatchObject({ status: 'synced', local: [term('first')] })
  })
  it('isolates original identity tuples, language and account', async () => {
    const env = setup(), first = env.open(); await first.controller.start(); await first.controller.update([term()])
    for (const [username, key] of [['bob', scope], ['alice', { ...scope, targetLanguage: 'en' }], ['alice', { ...scope, providerId: 'source', bookId: 'A:book/B:原文' }]] as const) {
      const { store } = env.open(username, key); expect(await store.snapshot()).toBeNull()
    }
  })
  it('leaves a durable uncertain outbox intact when a transport ignores logout cancellation', async () => {
    const env = setup([term()], 1), { controller, store } = env.open(); await controller.start(); await controller.choose('server', controller.snapshot())
    await controller.update([term('new')], controller.snapshot()); const pending = (await store.snapshot())!.pending
    let answer!: (value: BookGlossaryView) => void
    vi.mocked(env.api.put).mockImplementationOnce(() => new Promise(resolve => { answer = resolve }))
    const flushing = controller.flush(); await vi.waitFor(() => expect(answer).toBeTypeOf('function'))
    await controller.close(); answer({ ...scope, entries: [term('new')], version: 2, updatedAt: at }); await flushing
    expect((await store.snapshot())!.pending).toEqual(pending)
    expect((await store.snapshot())!.remote!.version).toBe(1)
    expect(await env.open('bob').store.snapshot()).toBeNull()
  })
})
describe('account glossary projections', () => {
  it('preserves stable IDs, whitespace and order while allowing explicit duplicate source entries', async () => {
    const all = [term('z'), term('a', { sourceTerm: 'Alice', enabled: true }), term('off', { enabled: false })]
    const projected = bookGlossaryWorkflow(all)
    expect(projected.map(entry => entry.id)).toEqual(['z', 'a']); expect(projected[0].source).toBe(' Alice ')
    const revision = await glossaryRevision(projected)
    expect(await glossaryRevision(projected.map(entry => ({ ...entry, kind: 'Place', displayTerm: 'Other' })))).toBe(revision)
    expect(await glossaryRevision(projected.map(entry => ({ ...entry, source: entry.source.trim(), target: entry.target.trim() })))).toBe(revision)
    expect(await glossaryRevision([{ id: 'x', source: '\ufeffAlice\ufeff', target: '앨리스' }])).not.toBe(await glossaryRevision([{ id: 'x', source: 'Alice', target: '앨리스' }]))
    expect(() => normalizeGlossary([{ id: 'x', source: 'Alice', target: 'one' }, { source: 'alice', target: 'two' }])).toThrow()
    const legacyId = (await sha256('alice')).slice(0, 24)
    await expect(glossaryRevision([{ id: legacyId, source: 'Other', target: 'one' }, { source: 'Alice', target: 'two' }])).rejects.toThrow()
  })
  it('keeps all 500 account entries and fails a translation exceeding 200 active entries', () => {
    const full = Array.from({ length: 500 }, (_, i) => term(String(i), { enabled: i < 200 }))
    expect(bookGlossaryWorkflow(full)).toHaveLength(200); expect(full).toHaveLength(500)
    full[200].enabled = true; expect(() => bookGlossaryWorkflow(full)).toThrow('200'); expect(full).toHaveLength(500)
    expect(() => selectedBookGlossary(undefined, [])).toThrow()
  })
})
