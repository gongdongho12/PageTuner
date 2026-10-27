import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ReadingProgressError, type ReadingProgressIdentity, type ReadingProgressView } from '../lib/readingProgressApi'
import { createReadingProgressStore, emptyReadingProgress, type ReadingProgressRecord } from '../lib/readingProgressStore'
import { readingProgressExportErrors as errors } from '../lib/portableReadingProgressExport'
import { ProgressRegistry } from './ReadingProgressProvider'

const scope: ReadingProgressIdentity = { kind: 'ORIGINAL', recordId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' }
const upper = { ...scope, recordId: scope.recordId.toUpperCase() }
const registries: ProgressRegistry[] = []
const value = (version = 1, offset = 4): ReadingProgressView => ({ ...scope, version,
  anchor: version ? { paragraphId: ' p:空 e\u0301 🌏 ', characterOffset: offset } : null,
  updatedAt: version ? '2026-10-27T00:00:00Z' : null })
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function fixture(get: (identity: ReadingProgressIdentity, signal?: AbortSignal) => Promise<ReadingProgressView> = async () => value()) {
  vi.stubGlobal('indexedDB', new IDBFactory())
  const api = { get: vi.fn(get), put: vi.fn(async () => { throw new Error('Fresh read must not PUT') }), close: vi.fn() }
  const registry = new ProgressRegistry('alice', api); registries.push(registry)
  let active = true
  return { registry, api, reader: registry.freshReader(() => active), active: (next: boolean) => { active = next } }
}
async function journal(remote = value(), change: Partial<ReadingProgressRecord> = {}, identity = scope, username = 'alice') {
  return createReadingProgressStore(username).update(identity, () => ({ ...emptyReadingProgress(identity), remote: { ...remote, ...identity }, ...change }))
}
const pending = () => ({ expectedVersion: 1, mutationId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', anchor: value().anchor! })
async function rows() {
  return new Promise<unknown[]>((resolve, reject) => {
    const request = indexedDB.open('pageturner-reading-progress', 1)
    request.onerror = () => reject(request.error)
    request.onsuccess = () => {
      const db = request.result, tx = db.transaction('records', 'readonly'), get = tx.objectStore('records').getAll()
      tx.oncomplete = () => { db.close(); resolve(get.result) }; tx.onabort = () => { db.close(); reject(tx.error) }
    }
  })
}
async function deleteRows() {
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.open('pageturner-reading-progress', 1)
    request.onerror = () => reject(request.error)
    request.onsuccess = () => {
      const db = request.result, tx = db.transaction('records', 'readwrite'); tx.objectStore('records').clear()
      tx.oncomplete = () => { db.close(); resolve() }; tx.onabort = () => { db.close(); reject(tx.error) }
    }
  })
}
afterEach(() => { registries.splice(0).forEach(registry => registry.close()); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('GET-only account reading progress session', () => {
  it('performs direct GET without a controller, journal row, write, fallback movement or drain', async () => {
    const f = fixture(), open = vi.spyOn(f.registry, 'open'), drain = vi.spyOn(f.registry, 'drain'), update = vi.spyOn(f.registry.store, 'update')
    expect(await f.reader.read(scope)).toEqual(value())
    await f.reader.checkReady(scope)
    expect(f.api.get).toHaveBeenCalledExactlyOnceWith(scope, undefined)
    expect(open).not.toHaveBeenCalled(); expect(drain).not.toHaveBeenCalled(); expect(update).not.toHaveBeenCalled()
    expect(f.api.put).not.toHaveBeenCalled(); expect(await rows()).toEqual([])
  })
  it('preserves an existing clean journal and cooldown while returning exact absent/zero/end-like positions', async () => {
    let next = value(0)
    const f = fixture(async () => structuredClone(next))
    const saved = await journal(value(0), { retryAfterUntil: 99_999 })
    const before = await rows()
    expect(await f.reader.read(scope)).toEqual(value(0))
    next = value(1, 0); expect(await f.reader.read(scope)).toEqual(next)
    next = value(2, 2147483647); expect(await f.reader.read(scope)).toEqual(next)
    expect(await rows()).toEqual(before)
    expect(await f.registry.store.get(scope)).toEqual(saved)
    expect(f.api.put).not.toHaveBeenCalled()
  })
  it('requires an accepted direct GET for each scope before final readiness', async () => {
    const f = fixture()
    await expect(f.reader.checkReady(scope)).rejects.toThrow(errors.response)
    expect(f.api.get).not.toHaveBeenCalled()
    const reader = f.registry.freshReader(() => true)
    await reader.read(scope)
    await expect(reader.checkReady({ ...scope, recordId: 'cccccccc-cccc-cccc-cccc-cccccccccccc' })).rejects.toThrow(errors.response)
    expect(f.api.get).toHaveBeenCalledTimes(1)
  })
  it('captures caller scope and detaches both returned and retained views before awaiting journal reads', async () => {
    const result = value(), f = fixture(async () => result), mutable = { ...upper }
    const promise = f.reader.read(mutable)
    mutable.kind = 'TRANSLATION'; mutable.recordId = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
    const returned = await promise
    expect(f.api.get).toHaveBeenCalledExactlyOnceWith(scope, undefined)
    returned.anchor!.characterOffset = 99; result.anchor!.characterOffset = 88
    await expect(f.reader.read(scope)).rejects.toThrow(errors.response)
    await expect(f.reader.checkReady(scope)).rejects.toThrow(errors.response)
  })
  it.each(['pending', 'queued', 'conflict'] as const)('blocks %s before any GET and preserves the whole journal', async kind => {
    const f = fixture()
    await journal(value(), { pending: pending(), ...(kind === 'queued' ? { queued: value().anchor } : {}), ...(kind === 'conflict' ? { conflict: value(2) } : {}) })
    const before = await rows()
    await expect(f.reader.read(scope)).rejects.toThrow(errors.pending)
    expect(f.api.get).not.toHaveBeenCalled(); expect(f.api.put).not.toHaveBeenCalled(); expect(await rows()).toEqual(before)
  })
  it.each(['pending', 'queued', 'conflict'] as const)('blocks %s introduced during GET', async kind => {
    const held = deferred<ReadingProgressView>(), f = fixture(async () => held.promise)
    const first = f.reader.read(scope)
    await vi.waitFor(() => expect(f.api.get).toHaveBeenCalledTimes(1))
    await journal(value(), { pending: pending(), ...(kind === 'queued' ? { queued: value().anchor } : {}), ...(kind === 'conflict' ? { conflict: value(2) } : {}) })
    held.resolve(value())
    await expect(first).rejects.toThrow(errors.pending)
    expect(f.api.put).not.toHaveBeenCalled()
  })
  it.each(['pending', 'queued', 'conflict'] as const)('blocks %s at final readiness after an accepted direct GET', async kind => {
    const f = fixture(); await f.reader.read(scope)
    await journal(value(), { pending: pending(), ...(kind === 'queued' ? { queued: value().anchor } : {}), ...(kind === 'conflict' ? { conflict: value(2) } : {}) })
    const before = await rows()
    await expect(f.reader.checkReady(scope)).rejects.toThrow(errors.pending)
    expect(f.api.get).toHaveBeenCalledTimes(1); expect(f.api.put).not.toHaveBeenCalled(); expect(await rows()).toEqual(before)
  })
  it('blocks a pending UUID spelling alias even when a canonical clean row exists', async () => {
    const f = fixture(); await journal(); await journal(value(), { pending: pending() }, upper)
    const before = await rows()
    await expect(f.reader.read(scope)).rejects.toThrow(errors.pending)
    await expect(f.reader.read(upper)).rejects.toThrow(errors.pending)
    expect(f.api.get).not.toHaveBeenCalled(); expect(await rows()).toEqual(before)
  })
  it('includes clean UUID aliases in highwater and leaves unrelated account/kind/document rows alone', async () => {
    const f = fixture(async () => value(2))
    await journal(value(3), {}, upper)
    await journal(value(9), { pending: pending() }, scope, 'bob')
    await journal(value(9), { pending: pending() }, { ...scope, kind: 'TRANSLATION' })
    await journal(value(9), { pending: pending() }, { ...scope, recordId: 'cccccccc-cccc-cccc-cccc-cccccccccccc' })
    await expect(f.reader.read(scope)).rejects.toThrow(errors.response)
    const fresh = f.registry.freshReader(() => true)
    f.api.get.mockImplementation(async () => value(3))
    expect(await fresh.read(scope)).toEqual(value(3))
    expect((await rows()).length).toBe(4)
  })
  it('retains a journal highwater across a failed GET and deletion of its row', async () => {
    const f = fixture(async () => { throw new ReadingProgressError('network') })
    await journal(value(3))
    await expect(f.reader.read(scope)).rejects.toThrow(ReadingProgressError)
    await deleteRows()
    f.api.get.mockImplementation(async () => value(2))
    await expect(f.reader.read(scope)).rejects.toThrow(errors.response)
    expect(await rows()).toEqual([])
  })
  it('retains a valid remote highwater even when pending prevents the GET and its row is later deleted', async () => {
    const f = fixture(async () => value(2))
    await journal(value(3), { pending: pending() })
    await expect(f.reader.read(scope)).rejects.toThrow(errors.pending)
    expect(f.api.get).not.toHaveBeenCalled()
    await deleteRows()
    await expect(f.reader.read(scope)).rejects.toThrow(errors.response)
  })
  it.each(['older', 'anchor', 'timestamp'] as const)('rejects %s observations and permanently retires the reader', async change => {
    let next = value(2), f = fixture(async () => next)
    await f.reader.read(scope)
    next = change === 'older' ? value(1) : change === 'anchor' ? value(2, 5) : { ...value(2), updatedAt: '2026-10-27T00:00:01Z' }
    await expect(f.reader.read(scope)).rejects.toThrow(errors.response)
    next = value(3); await expect(f.reader.read(scope)).rejects.toThrow(errors.response)
    expect(f.api.get).toHaveBeenCalledTimes(2)
  })
  it('rejects a newer journal or different same-version journal at final readiness', async () => {
    const f = fixture(); await f.reader.read(scope); await journal(value(2))
    await expect(f.reader.checkReady(scope)).rejects.toThrow(errors.response)
    const fresh = f.registry.freshReader(() => true)
    await deleteRows(); await fresh.read(scope); await journal(value(1, 99))
    await expect(fresh.checkReady(scope)).rejects.toThrow(errors.response)
  })
  it('rejects late older GET success after a newer overlapping GET has completed', async () => {
    const held = deferred<ReadingProgressView>(), f = fixture()
    f.api.get.mockImplementationOnce(async () => held.promise).mockImplementationOnce(async () => value(2))
    const first = f.reader.read(scope)
    await vi.waitFor(() => expect(f.api.get).toHaveBeenCalledTimes(1))
    expect(await f.reader.read(scope)).toEqual(value(2))
    held.resolve(value(1)); await expect(first).rejects.toThrow(errors.response)
  })
  it('rechecks overlapping observations after the final journal await', async () => {
    const entered = deferred<void>(), release = deferred<void>(), f = fixture()
    let calls = 0
    vi.spyOn(f.registry.store, 'snapshotAliases').mockImplementation(async () => {
      if (++calls === 2) { entered.resolve(); await release.promise }
      return []
    })
    f.api.get.mockImplementationOnce(async () => value(1)).mockImplementationOnce(async () => value(2))
    const first = f.reader.read(scope)
    await entered.promise
    expect(await f.reader.read(scope)).toEqual(value(2))
    release.resolve(); await expect(first).rejects.toThrow(errors.response)
  })
  it.each([
    { ...value(), kind: 'TRANSLATION' }, { ...value(), recordId: upper.recordId }, { ...value(), version: 1.5 },
    { ...value(), version: Number.MAX_SAFE_INTEGER + 1 }, { ...value(), version: Infinity }, { ...value(), future: true },
    { ...value(), anchor: { ...value().anchor, future: 1 } }, { ...value(), anchor: { paragraphId: '\ud800', characterOffset: 0 } },
    { ...value(), anchor: { paragraphId: 'bad\u0085', characterOffset: 0 } }, { ...value(), anchor: { paragraphId: 'p', characterOffset: -1 } },
    { ...value(), anchor: null }, { ...value(0), anchor: value().anchor }, { ...value(0), updatedAt: '2026-10-27T00:00:00Z' },
  ])('strictly rejects malformed injected response %#', async malformed => {
    const f = fixture(async () => malformed as ReadingProgressView)
    await expect(f.reader.read(scope)).rejects.toThrow(errors.response)
    await expect(f.reader.checkReady(scope)).rejects.toThrow(errors.response)
    expect(f.api.put).not.toHaveBeenCalled()
  })
  it('retires on production INVALID_RESPONSE but permits an entirely new reader after revalidation', async () => {
    const f = fixture(async () => { throw new ReadingProgressError('invalid-response') })
    await expect(f.reader.read(scope)).rejects.toThrow(errors.response)
    f.api.get.mockImplementation(async () => value())
    await expect(f.reader.read(scope)).rejects.toThrow(errors.response)
    expect(await f.registry.freshReader(() => true).read(scope)).toEqual(value())
  })
  it.each(['success', 'error'] as const)('discards late %s after account/session expiry and keeps the old reader terminal on ABA', async result => {
    const held = deferred<ReadingProgressView>(), f = fixture(async () => held.promise)
    const first = f.reader.read(scope)
    await vi.waitFor(() => expect(f.api.get).toHaveBeenCalledTimes(1))
    f.active(false)
    expect(() => f.reader.assertCurrent()).toThrow(errors.stale)
    f.active(true)
    if (result === 'success') held.resolve(value()); else held.reject(new ReadingProgressError('network'))
    await expect(first).rejects.toThrow(errors.stale)
    await expect(f.reader.read(scope)).rejects.toThrow(errors.stale)
  })
  it('expires synchronously on abort while GET is still held', async () => {
    const held = deferred<ReadingProgressView>(), f = fixture(async () => held.promise), abort = new AbortController()
    const first = f.reader.read(scope, abort.signal)
    await vi.waitFor(() => expect(f.api.get).toHaveBeenCalledTimes(1))
    abort.abort()
    expect(() => f.reader.assertCurrent()).toThrow(errors.stale)
    await expect(f.reader.read(scope)).rejects.toThrow(errors.stale)
    held.resolve(value()); await expect(first).rejects.toThrow(errors.stale)
  })
  it('does not issue GET after abort during a delayed journal read', async () => {
    const entered = deferred<void>(), release = deferred<void>(), f = fixture(), abort = new AbortController()
    vi.spyOn(f.registry.store, 'snapshotAliases').mockImplementation(async () => { entered.resolve(); await release.promise; return [] })
    const first = f.reader.read(scope, abort.signal)
    await entered.promise; abort.abort()
    expect(() => f.reader.assertCurrent()).toThrow(errors.stale)
    release.resolve(); await expect(first).rejects.toThrow(errors.stale)
    expect(f.api.get).not.toHaveBeenCalled()
  })
  it('discards a successful held GET after its registry closes', async () => {
    const held = deferred<ReadingProgressView>(), f = fixture(async () => held.promise)
    const first = f.reader.read(scope)
    await vi.waitFor(() => expect(f.api.get).toHaveBeenCalledTimes(1))
    f.registry.close(); held.resolve(value())
    await expect(first).rejects.toThrow(errors.stale)
    await expect(f.reader.checkReady(scope)).rejects.toThrow(errors.stale)
  })
  it('retires the lease when its captured HTTP client is closed', async () => {
    const f = fixture(async () => { throw new ReadingProgressError('aborted') })
    await expect(f.reader.read(scope)).rejects.toThrow(errors.stale)
    await expect(f.reader.read(scope)).rejects.toThrow(errors.stale)
    expect(f.api.get).toHaveBeenCalledTimes(1)
  })
  it('rejects registry close, authority exceptions and offline use without network or mutations', async () => {
    const f = fixture(); f.registry.close()
    await expect(f.reader.read(scope)).rejects.toThrow(errors.stale)
    const g = fixture(), bad = g.registry.freshReader(() => { throw new Error('bad authority') })
    expect(() => bad.assertCurrent()).toThrow(errors.stale)
    const offline = new ProgressRegistry('alice', null); registries.push(offline)
    await expect(offline.freshReader(() => true).read(scope)).rejects.toThrow(errors.offline)
    expect(g.api.get).not.toHaveBeenCalled(); expect(g.api.put).not.toHaveBeenCalled()
  })
})
