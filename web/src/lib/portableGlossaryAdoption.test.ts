import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import { BookGlossaryError, type BookGlossaryView } from './bookGlossaryApi'
import { createBookGlossaryStore, listBookGlossaryRecords } from './bookGlossaryStore'
import { createBookGlossaryController } from './bookGlossarySync'
import type { SavedExchange } from './exchangeLibrary'
import { validateLibraryIdentity } from './libraryIdentity'
import { validateExchangeDocument } from './libraryExchange'
import { createPortableBindings } from './portableBinding'
import { withBookGlossarySnapshots, type PortableBookGlossarySnapshot } from './portableBookGlossary'
import { createPortableGlossaryAdoption, glossaryAdoptionErrors } from './portableGlossaryAdoption'

const time = '2026-10-04T00:00:00Z', scope = { providerId: 'source:edge', bookId: 'book|原🌏', targetLanguage: 'ko' }
const entry = { id: ' original-entry🌏'.trimStart(), sourceTerm: ' Alice ', translatedTerm: ' 앨리스 ', displayTerm: ' 아리 ', kind: 'Character' as const, enabled: false, caseSensitive: true }
const snapshot: PortableBookGlossarySnapshot = { ...scope, presence: 'present', entries: [entry] }
const cleanups: (() => void)[] = []
beforeEach(() => { vi.stubGlobal('indexedDB', new IDBFactory()); vi.stubGlobal('crypto', webcrypto) })
afterEach(() => { cleanups.splice(0).forEach(clean => clean()); vi.unstubAllGlobals() })
async function setup(value = snapshot, initial: BookGlossaryView = { ...scope, version: 0, entries: null, updatedAt: null }) {
  let current = true, server = structuredClone(initial)
  const api = { get: vi.fn(async () => structuredClone(server)), put: vi.fn(async (_scope, input) => {
    if (input.expectedVersion !== server.version) throw new BookGlossaryError('conflict', structuredClone(server))
    server = { ...scope, version: server.version + 1, entries: structuredClone(input.entries), updatedAt: time }; return structuredClone(server)
  }), close: vi.fn() }
  const book: SavedExchange = { id: 'portable-copy', document: withBookGlossarySnapshots(validateExchangeDocument(structuredClone(original)), { version: 1, snapshots: [value] }), assets: [], importedAt: time, checksum: '', integratedNoteIds: [] }
  const values = new Map<string, string>(), storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value) }, removeItem: (key: string) => { values.delete(key) } }
  const bindings = createPortableBindings('reader', 'https://account.example', storage)
  const proof = { verified: true as const, recordId: original.extensions.serverRecordId, kind: 'ORIGINAL' as const, identity: validateLibraryIdentity(original.extensions.documentIdentity) }
  const identity = { verify: vi.fn(async () => proof), close: vi.fn() }
  await bindings.confirm(book, proof)
  const onQueued = vi.fn(), assertCurrent = () => { if (!current) throw new Error(glossaryAdoptionErrors.stale) }
  const session = createPortableGlossaryAdoption({ username: 'reader', api, onQueued, assertCurrent })
  const store = createBookGlossaryStore('reader', scope); cleanups.push(() => store.close())
  return { book, bindings, proof, identity, api, onQueued, store, session,
    prepare: () => session.prepare(book, scope, identity, bindings), expire: () => { current = false },
    advance: (value: BookGlossaryView) => { server = structuredClone(value) },
  }
}

describe('explicit ZIP account glossary adoption', () => {
  it('prepares readonly comparison and atomically establishes the missing journal with all 500 exact entries', async () => {
    const entries = Array.from({ length: 500 }, (_, index) => ({ ...entry, id: `original-${499 - index}`, kind: index % 2 ? 'Place' as const : 'Character' as const }))
    const state = await setup({ ...scope, presence: 'present', entries }), originalZip = structuredClone(state.book)
    const review = await state.prepare()
    expect(review.device).toBeNull(); expect(review.server.version).toBe(0); expect(review.snapshot.entries).toEqual(entries)
    expect(await listBookGlossaryRecords('reader')).toEqual([]); expect(state.api.put).not.toHaveBeenCalled()
    await review.confirm()
    const stored = await state.store.snapshot()
    expect(stored).toMatchObject({ enabled: true, selected: true, local: entries, remote: { version: 0 }, pending: { expectedVersion: 0, entries }, conflict: null })
    expect(stored!.pending!.mutationId).toMatch(/^[a-f0-9-]{36}$/)
    expect(state.onQueued).toHaveBeenCalledExactlyOnceWith(scope); expect(state.api.put).not.toHaveBeenCalled()
    expect(state.book).toEqual(originalZip)
  })
  it.each(['deleted', 'present'] as const)('stages explicit %s against an absent server even for null or empty values', async presence => {
    const value: PortableBookGlossarySnapshot = presence === 'deleted' ? { ...scope, presence, entries: null } : { ...scope, presence, entries: [] }
    const state = await setup(value), review = await state.prepare()
    await review.confirm()
    expect((await state.store.snapshot())!.pending).toMatchObject({ expectedVersion: 0, entries: value.entries })
  })
  it('treats absent as information with no journal or server mutation even if confirm is invoked', async () => {
    const state = await setup({ ...scope, presence: 'absent', entries: null }), review = await state.prepare()
    await expect(review.confirm()).rejects.toThrow(glossaryAdoptionErrors.absent)
    expect(await state.store.snapshot()).toBeNull(); expect(state.api.put).not.toHaveBeenCalled(); expect(state.onQueued).not.toHaveBeenCalled()
    expect(state.api.get).toHaveBeenCalledTimes(1)
  })
  it('keeps immutable command copies when the caller changes displayed entries, server version or device values', async () => {
    const state = await setup(), review = await state.prepare()
    review.snapshot.entries![0].id = 'tampered-display'; review.server.version = 99; review.scope.bookId = 'wrong'
    await review.confirm()
    expect((await state.store.snapshot())!.pending).toMatchObject({ expectedVersion: 0, entries: [entry] })
  })
  it('refuses stale server confirmation without changing the device base', async () => {
    const state = await setup(), review = await state.prepare()
    state.advance({ ...scope, version: 1, entries: [], updatedAt: time })
    await expect(review.confirm()).rejects.toThrow(glossaryAdoptionErrors.stale)
    expect(await state.store.snapshot()).toBeNull(); expect(state.onQueued).not.toHaveBeenCalled()
  })
  it('atomically refuses a concurrently initialized journal instead of initializing and overwriting it', async () => {
    const state = await setup(), review = await state.prepare()
    const newer = await state.store.initialize([{ ...entry, id: 'other-tab-local' }])
    await expect(review.confirm()).rejects.toThrow(glossaryAdoptionErrors.stale)
    expect(await state.store.snapshot()).toEqual(newer)
  })
  it('compares every device field, including selected state and legacy IDs, rather than only local entries', async () => {
    const state = await setup()
    await state.store.initialize(null)
    const review = await state.prepare()
    const changed = await state.store.transact(row => ({ ...row, legacyIds: { name: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' } }))
    await expect(review.confirm()).rejects.toThrow(glossaryAdoptionErrors.stale)
    expect(await state.store.snapshot()).toEqual(changed)
  })
  it('serializes simultaneous-tab confirmations so only one new intent wins', async () => {
    const state = await setup(), [first, second] = await Promise.all([state.prepare(), state.prepare()])
    const result = await Promise.allSettled([first.confirm(), second.confirm()])
    expect(result.filter(value => value.status === 'fulfilled')).toHaveLength(1)
    expect(result.filter(value => value.status === 'rejected')).toHaveLength(1)
    expect(state.onQueued).toHaveBeenCalledTimes(1)
  })
  it('consumes the confirmation before awaiting and rejects a duplicate click without a second intent', async () => {
    const state = await setup(), review = await state.prepare()
    const result = await Promise.allSettled([review.confirm(), review.confirm()])
    expect(result.filter(value => value.status === 'fulfilled')).toHaveLength(1); expect(state.onQueued).toHaveBeenCalledTimes(1)
    await expect(review.confirm()).rejects.toThrow(glossaryAdoptionErrors.stale)
  })
  it.each([false, true])('refuses a pre-existing pending/conflict journal (%s) without flushing it', async conflict => {
    const state = await setup(), first = await state.prepare()
    await first.confirm()
    if (conflict) await state.store.transact(row => ({ ...row, conflict: row.remote }))
    const before = await state.store.snapshot()
    await expect(state.prepare()).rejects.toThrow(glossaryAdoptionErrors.pending)
    expect(await state.store.snapshot()).toEqual(before); expect(state.api.put).not.toHaveBeenCalled()
  })
  it('requires the existing exact binding and invalidates a review after unlink/relink to the same source', async () => {
    const state = await setup(), review = await state.prepare()
    state.bindings.remove(state.book)
    await expect(state.prepare()).rejects.toThrow(glossaryAdoptionErrors.binding)
    await state.bindings.confirm(state.book, state.proof)
    await expect(review.confirm()).rejects.toThrow(glossaryAdoptionErrors.stale)
    expect(await state.store.snapshot()).toBeNull()
  })
  it('rejects a binding changed during identity verification or server confirmation', async () => {
    const state = await setup(), review = await state.prepare()
    state.api.get.mockImplementation(async () => { state.bindings.remove(state.book); return { ...scope, version: 0, entries: null, updatedAt: null } })
    await expect(review.confirm()).rejects.toThrow(glossaryAdoptionErrors.stale)
    expect(await state.store.snapshot()).toBeNull()
  })
  it.each(['prepare', 'confirm'] as const)('rejects account/client generation replacement during %s', async stage => {
    const state = await setup(), review = stage === 'confirm' ? await state.prepare() : undefined
    state.api.get.mockImplementation(async () => { state.expire(); return { ...scope, version: 0, entries: null, updatedAt: null } })
    await expect(review ? review.confirm() : state.prepare()).rejects.toThrow(glossaryAdoptionErrors.stale)
    expect(await state.store.snapshot()).toBeNull(); expect(state.onQueued).not.toHaveBeenCalled()
  })
  it('rejects canceled confirmation without queuing', async () => {
    const state = await setup(), review = await state.prepare()
    await expect(review.confirm(AbortSignal.abort())).rejects.toThrow(glossaryAdoptionErrors.stale)
    expect(await state.store.snapshot()).toBeNull()
  })
  it('rejects unknown snapshot versions and wrong exact scopes before the account query', async () => {
    const state = await setup()
    state.book.document.extensions!.bookGlossarySnapshots = { version: 2, snapshots: [snapshot] }
    await expect(state.prepare()).rejects.toThrow(glossaryAdoptionErrors.snapshot)
    state.book.document.extensions!.bookGlossarySnapshots = { version: 1, snapshots: [{ ...snapshot, bookId: 'wrong' }] }
    await expect(state.session.prepare(state.book, { ...scope, bookId: 'wrong' }, state.identity, state.bindings)).rejects.toThrow(glossaryAdoptionErrors.scope)
    expect(state.api.get).not.toHaveBeenCalled(); expect(await state.store.snapshot()).toBeNull()
  })
  it('uses the existing outbox retry and preserves the same mutation ID after a network failure', async () => {
    const state = await setup(), review = await state.prepare(); await review.confirm()
    const pending = (await state.store.snapshot())!.pending!
    state.api.put.mockRejectedValueOnce(new BookGlossaryError('network'))
    const controller = createBookGlossaryController({ api: state.api, store: state.store, scope })
    cleanups.push(() => { void controller.close() })
    await controller.start()
    expect((await state.store.snapshot())!.pending).toEqual(pending)
    await controller.refresh()
    expect((await state.store.snapshot())!.pending).toBeNull()
    expect(state.api.put.mock.calls.map(call => call[1].mutationId)).toEqual([pending.mutationId, pending.mutationId])
  })
  it('preserves the exact adopted intent on CAS conflict for the existing comparison flow', async () => {
    const state = await setup(), review = await state.prepare(); await review.confirm()
    state.advance({ ...scope, version: 1, entries: [], updatedAt: time })
    const controller = createBookGlossaryController({ api: state.api, store: state.store, scope })
    cleanups.push(() => { void controller.close() })
    await controller.start()
    expect(controller.snapshot()).toMatchObject({ status: 'conflict', local: [entry], remote: { version: 1, entries: [] } })
    expect((await state.store.snapshot())!.pending!.entries).toEqual([entry])
    const before = controller.snapshot()
    expect(await controller.choose('local', before)).toBe(true)
    expect((await state.store.snapshot())!.pending).toBeNull()
    expect((await state.store.snapshot())!.remote!.entries).toEqual([entry])
  })
})
