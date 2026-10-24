import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import glossaryFixture from '../../../contracts/fixtures/book-glossary-snapshots-v1.json'
import { OrganizationRegistry } from '../components/LibraryOrganizationProvider'
import { createExchangeLibrary } from './exchangeLibrary'
import { createPortableBindings } from './portableBinding'
import { createLibraryOrganizationStore, listLibraryOrganizationRecords } from './libraryOrganizationStore'
import { validateExchangeDocument } from './libraryExchange'
import { validateLibraryIdentity } from './libraryIdentity'
import { withLibraryOrganizationSnapshot } from './portableLibraryOrganization'
import { createPortableOrganizationAdoption, organizationAdoptionErrors } from './portableOrganizationAdoption'
import type { LibraryOrganization } from './libraryOrganizationApi'
import { readingTransaction, readingNamespace } from './deviceReadingDatabase'
import { withBookGlossarySnapshots, readBookGlossarySnapshotsFromDocument } from './portableBookGlossary'

const time = '2026-10-24T10:45:00Z', registries: OrganizationRegistry[] = []
const value: LibraryOrganization = { folder: '읽을  책', tags: ['é', 'e\u0301', 'Tag', 'tag', '中  文'], favorite: true }
const empty: LibraryOrganization = { folder: '', tags: [], favorite: false }
beforeEach(() => { vi.stubGlobal('indexedDB', new IDBFactory()); vi.stubGlobal('crypto', webcrypto) })
afterEach(() => { registries.splice(0).forEach(registry => registry.close()); vi.unstubAllGlobals() })
async function setup(presence: 'present' | 'absent' = 'present', organization = value, withGlossary = false) {
  const identity = validateLibraryIdentity(original.extensions.documentIdentity)
  let base = validateExchangeDocument(structuredClone(original))
  base.extensions!.unknownSibling = { untouched: ['é', 'e\u0301'] }
  base.organization = { folder: 'Legacy', tags: ['keep'], favorite: false }
  if (withGlossary) base = withBookGlossarySnapshots(base, { version: 1, snapshots: ['ko', 'en'].map(targetLanguage => ({
    providerId: identity.contentProviderId, bookId: identity.bookId, targetLanguage, presence: 'present',
    entries: Array.from({ length: targetLanguage === 'ko' ? 500 : 1 }, (_, index) => ({ ...glossaryFixture.snapshots[0].entries![0], id: `entry-${index}`, sourceTerm: ` Source ${index} ` })),
  })) })
  const document = await withLibraryOrganizationSnapshot(base, { version: 1, identity, presence, organization: presence === 'present' ? organization : null })
  const library = createExchangeLibrary('reader')
  await library.importPackage({ createdAt: time, documents: [document], assets: [] })
  const book = (await library.list())[0], scope = { kind: identity.kind, recordId: original.extensions.serverRecordId }
  const remote = { ...scope, version: 0, organization: null, updatedAt: null }
  const api = { get: vi.fn(async () => structuredClone(remote)), put: vi.fn(), close() {} }
  const registry = new OrganizationRegistry('reader', api); registries.push(registry)
  vi.spyOn(registry, 'drain').mockResolvedValue(undefined)
  const result = { verified: true as const, ...scope, identity }
  const identityClient = { verify: vi.fn(async () => structuredClone(result)), close() {} }
  const memory = new Map<string, string>(), bindings = createPortableBindings('reader', 'https://account.example', {
    getItem: key => memory.get(key) ?? null, setItem: (key, next) => { memory.set(key, next) }, removeItem: key => { memory.delete(key) },
  })
  await bindings.confirm(book, result)
  let current = true
  const options = { session: registry.adoptionSession(() => current), library, bindings, identityClient, current: () => current }
  const adapter = createPortableOrganizationAdoption(options)
  return { adapter, options, api, scope, remote, library, book, bindings, result, identityClient, memory, expire: () => { current = false } }
}
async function changeRow(id: string, remove: boolean) {
  await readingTransaction(['exchanges'], 'readwrite', (tx, done) => {
    const store = tx.objectStore('exchanges'), key = [readingNamespace('reader'), id]
    if (remove) { store.delete(key); done(undefined) }
    else { const request = store.get(key); request.onsuccess = () => { store.put({ ...request.result, importedAt: '2026-10-24T10:46:00Z' }); done(undefined) } }
  })
}

describe('canonical ZIP organization comparison and explicit account adoption', () => {
  it('compares read-only and preserves legacy/sibling/source bytes without initializing account journals', async () => {
    const f = await setup(), before = structuredClone(await f.library.get(f.book.id)), bindings = [...f.memory.entries()]
    const review = await f.adapter.prepare(f.book.id)
    expect(review.snapshot.organization).toEqual(value); expect(review.device).toBeNull(); expect(review.server).toEqual(f.remote)
    expect(await listLibraryOrganizationRecords('reader')).toEqual([]); expect(f.api.put).not.toHaveBeenCalled()
    expect(await f.library.get(f.book.id)).toEqual(before); expect([...f.memory.entries()]).toEqual(bindings)
    review.close()
  })

  it('creates a fresh explicit empty intent against absent version zero, only on confirmation', async () => {
    const f = await setup('present', empty), before = structuredClone(await f.library.get(f.book.id))
    const review = await f.adapter.prepare(f.book.id)
    expect(await listLibraryOrganizationRecords('reader')).toEqual([])
    await review.confirm()
    const rows = await listLibraryOrganizationRecords('reader')
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ scope: f.scope, record: { enabled: true, local: empty, pending: { expectedVersion: 0, organization: empty }, queued: null, conflict: null } })
    expect(rows[0].record.pending!.mutationId).toMatch(/^[0-9a-f-]{36}$/)
    expect(await f.library.get(f.book.id)).toEqual(before)
    await expect(review.confirm()).rejects.toThrow(); expect(f.api.put).not.toHaveBeenCalled()
  })

  it('preserves all 500 glossary entry fields, another language scope and passive siblings during organization adoption', async () => {
    const f = await setup('present', value, true), before = structuredClone(await f.library.get(f.book.id))
    const review = await f.adapter.prepare(f.book.id)
    await review.confirm()
    const after = (await f.library.get(f.book.id))!
    expect(after).toEqual(before)
    const snapshots = readBookGlossarySnapshotsFromDocument(after.document)!.snapshots
    expect(snapshots.map(snapshot => snapshot.entries?.length)).toEqual([500, 1])
    expect(after.document.organization).toEqual({ folder: 'Legacy', tags: ['keep'], favorite: false })
    expect(after.document.extensions!.unknownSibling).toEqual({ untouched: ['é', 'e\u0301'] })
  })

  it('never turns an absent snapshot into an empty organization intent', async () => {
    const f = await setup('absent'), review = await f.adapter.prepare(f.book.id)
    await expect(review.confirm()).rejects.toThrow(organizationAdoptionErrors.absent)
    expect(await listLibraryOrganizationRecords('reader')).toEqual([]); expect(f.api.put).not.toHaveBeenCalled()
  })

  it('keeps the private reviewed command independent of mutable display copies', async () => {
    const f = await setup(), review = await f.adapter.prepare(f.book.id)
    review.scope.recordId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
    if (review.snapshot.presence === 'present') { review.snapshot.organization.tags.reverse(); review.snapshot.organization.folder = 'Injected' }
    review.server.version = 99
    await review.confirm()
    const rows = await listLibraryOrganizationRecords('reader')
    expect(rows[0].scope).toEqual(f.scope); expect(rows[0].record.pending!.organization).toEqual(value)
    expect(rows[0].record.pending!.expectedVersion).toBe(0)
  })

  it.each(['query', 'confirm'])('rejects same-UUID unlink/relink during %s without changing the new binding', async phase => {
    const f = await setup()
    if (phase === 'query') f.api.get.mockImplementationOnce(async () => { f.bindings.remove(f.book); await f.bindings.confirm(f.book, f.result); return f.remote })
    else { const review = await f.adapter.prepare(f.book.id); f.bindings.remove(f.book); await f.bindings.confirm(f.book, f.result); await expect(review.confirm()).rejects.toThrow() }
    if (phase === 'query') await expect(f.adapter.prepare(f.book.id)).rejects.toThrow()
    expect(f.bindings.ticket(f.book)?.current()).toBe(true)
    expect(await listLibraryOrganizationRecords('reader')).toEqual([]); expect(f.api.put).not.toHaveBeenCalled()
  })

  it.each([true, false])('rejects canonical deletion=%s while account GET is pending', async remove => {
    const f = await setup()
    f.api.get.mockImplementationOnce(async () => { await changeRow(f.book.id, remove); return f.remote })
    await expect(f.adapter.prepare(f.book.id)).rejects.toThrow()
    expect(await listLibraryOrganizationRecords('reader')).toEqual([]); expect(f.api.put).not.toHaveBeenCalled()
  })

  it('rechecks canonical content after comparison before creating any intent', async () => {
    const f = await setup(), review = await f.adapter.prepare(f.book.id)
    await changeRow(f.book.id, false)
    await expect(review.confirm()).rejects.toThrow(organizationAdoptionErrors.source)
    expect(await listLibraryOrganizationRecords('reader')).toEqual([]); expect(f.api.put).not.toHaveBeenCalled()
  })

  it('keeps another actor pending journal intact instead of reusing or clearing it', async () => {
    const f = await setup(), review = await f.adapter.prepare(f.book.id), store = createLibraryOrganizationStore('reader', f.scope)
    const pending = { mutationId: crypto.randomUUID(), expectedVersion: 0, organization: empty }
    await store.transactNullable(() => ({ enabled: true, local: empty, remote: f.remote, pending, queued: null, conflict: null, retryAfterUntil: null }))
    await expect(review.confirm()).rejects.toThrow()
    expect((await store.snapshot())!.pending).toEqual(pending); expect(f.api.put).not.toHaveBeenCalled(); store.close()
  })

  it('rejects an expired account or closed review without a journal write', async () => {
    const f = await setup(), review = await f.adapter.prepare(f.book.id)
    f.expire()
    await expect(review.confirm()).rejects.toThrow()
    expect(await listLibraryOrganizationRecords('reader')).toEqual([]); expect(f.api.put).not.toHaveBeenCalled()
  })

  it('does not retire an existing binding when readonly server proof verification fails', async () => {
    const f = await setup(), before = [...f.memory.entries()]
    f.identityClient.verify.mockRejectedValueOnce(new Error('Unreachable'))
    await expect(f.adapter.prepare(f.book.id)).rejects.toThrow()
    expect([...f.memory.entries()]).toEqual(before); expect(await listLibraryOrganizationRecords('reader')).toEqual([])
  })
})
