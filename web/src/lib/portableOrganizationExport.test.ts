import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import { OrganizationRegistry } from '../components/LibraryOrganizationProvider'
import { createExchangeLibrary, exchangeExportChoices } from './exchangeLibrary'
import { createPortableBindings } from './portableBinding'
import { createLibraryOrganizationStore, listLibraryOrganizationRecords } from './libraryOrganizationStore'
import type { LibraryOrganizationView } from './libraryOrganizationApi'
import { readExchange, writeExchange, validateExchangeDocument, type ExchangeDocument } from './libraryExchange'
import { validateLibraryIdentity } from './libraryIdentity'
import { readLibraryOrganizationSnapshotFromDocument } from './portableLibraryOrganization'
import { organizationExportErrors, prepareFreshOrganizationExport } from './portableOrganizationExport'

const time = '2026-10-20T00:00:00Z', registries: OrganizationRegistry[] = []
beforeEach(() => { vi.stubGlobal('indexedDB', new IDBFactory()); vi.stubGlobal('crypto', webcrypto) })
afterEach(() => { registries.splice(0).forEach(value => value.close()); vi.unstubAllGlobals() })

async function setup(input = validateExchangeDocument(structuredClone(original)), version = 3) {
  const library = createExchangeLibrary('reader')
  await library.importPackage({ createdAt: time, documents: [input], assets: [] })
  const book = (await library.list())[0], choice = (await exchangeExportChoices('reader')).find(value => value.key === book.id)!
  const identity = validateLibraryIdentity(original.extensions.documentIdentity)
  const result = { verified: true as const, recordId: original.extensions.serverRecordId, kind: identity.kind, identity }
  const identityClient = { verify: vi.fn(async () => structuredClone(result)), close() {} }
  const scope = { kind: identity.kind, recordId: result.recordId }
  const view: LibraryOrganizationView = { ...scope, version, organization: version ? { folder: 'Folder  中', tags: ['é', 'e\u0301', '中  文'], favorite: true } : null, updatedAt: version ? time : null }
  const api = { get: vi.fn(async () => structuredClone(view)), put: vi.fn(), close() {} }
  const registry = new OrganizationRegistry('reader', api); registries.push(registry)
  const memory = new Map<string, string>(), bindings = createPortableBindings('reader', 'https://account.example', {
    getItem: key => memory.get(key) ?? null, setItem: (key, value) => { memory.set(key, value) }, removeItem: key => { memory.delete(key) },
  })
  await bindings.confirm(book, result)
  let active = true
  const options = { choices: [choice], reader: registry.freshReader(() => active), identityClient, bindings, current: () => active }
  return { options, library, book, choice, identity, result, scope, view, api, registry, bindings, expire: () => { active = false } }
}

describe('captured fresh account organization ZIP preparation', () => {
  it.each([0, 3])('writes passive version %i preserving canonical document and sibling metadata', async version => {
    const document = validateExchangeDocument(structuredClone(original))
    document.organization = { folder: 'Legacy folder', tags: ['legacy'], favorite: false }
    document.extensions!.unknownSibling = { preserved: 'value' }
    const f = await setup(document, version), before = structuredClone(f.book)
    const ready = await prepareFreshOrganizationExport(f.options)
    await ready.checkBeforeSave()
    const parsed = await readExchange(ready.bytes), output = parsed.documents[0]
    expect(await readLibraryOrganizationSnapshotFromDocument(output)).toEqual({ version: 1, identity: f.identity,
      presence: version ? 'present' : 'absent', organization: f.view.organization })
    expect(output.organization).toEqual(document.organization)
    expect(output.extensions).toMatchObject(document.extensions!)
    expect(output.paragraphs).toEqual(document.paragraphs)
    expect(f.book).toEqual(before)
    expect(f.api.get).toHaveBeenCalledExactlyOnceWith(f.scope, undefined)
    expect(f.api.put).not.toHaveBeenCalled()
    expect(await listLibraryOrganizationRecords('reader')).toEqual([])
    const snapshot = JSON.stringify(output.extensions!.libraryOrganizationSnapshot)
    for (const key of ['recordId', 'username', 'origin', 'bindingNonce', 'expectedVersion', 'mutationId', 'updatedAt']) expect(snapshot).not.toContain(`"${key}":`)
  })

  it('preserves explicitly empty positive-version values', async () => {
    const f = await setup()
    f.view.organization = { folder: '', tags: [], favorite: false }
    const ready = await prepareFreshOrganizationExport(f.options)
    expect(await readLibraryOrganizationSnapshotFromDocument((await readExchange(ready.bytes)).documents[0])).toMatchObject({ presence: 'present', organization: f.view.organization })
  })

  it.each(['query', 'encode', 'save'])('rejects same-UUID reconnection during %s', async phase => {
    const f = await setup()
    const reconnect = async () => { f.bindings.remove(f.book); await f.bindings.confirm(f.book, f.result) }
    if (phase === 'query') f.api.get.mockImplementation(async () => { await reconnect(); return f.view })
    if (phase === 'save') {
      const prepared = await prepareFreshOrganizationExport(f.options)
      await reconnect()
      await expect(prepared.checkBeforeSave()).rejects.toThrow()
    } else await expect(prepareFreshOrganizationExport({ ...f.options, ...(phase === 'encode' ? { encode: async value => { await reconnect(); return writeExchange(value) } } : {}) })).rejects.toThrow()
    expect(f.api.put).not.toHaveBeenCalled()
  })

  it.each(['query', 'encode', 'save'])('rejects source deletion during %s', async phase => {
    const f = await setup()
    // Deletion uses the same production device database transaction boundary as another tab.
    const { readingTransaction, readingNamespace } = await import('./deviceReadingDatabase')
    const remove = () => readingTransaction(['exchanges'], 'readwrite', (tx, done) => { tx.objectStore('exchanges').delete([readingNamespace('reader'), f.book.id]); done(undefined) })
    if (phase === 'query') f.api.get.mockImplementation(async () => { await remove(); return f.view })
    if (phase === 'save') {
      const ready = await prepareFreshOrganizationExport(f.options); await remove()
      await expect(ready.checkBeforeSave()).rejects.toThrow()
    } else await expect(prepareFreshOrganizationExport({ ...f.options, ...(phase === 'encode' ? { encode: async value => { await remove(); return writeExchange(value) } } : {}) })).rejects.toThrow()
  })

  it('rejects pending edits arriving after preparation without sending PUT or changing them', async () => {
    const f = await setup(), ready = await prepareFreshOrganizationExport(f.options), store = createLibraryOrganizationStore('reader', f.scope)
    let pending
    try {
      await store.initialize({ folder: '', tags: [], favorite: false })
      pending = await store.transact(() => ({ enabled: true, local: f.view.organization!, remote: f.view,
        pending: { expectedVersion: f.view.version, mutationId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', organization: f.view.organization! }, queued: null, conflict: null, retryAfterUntil: null }))
    } finally { store.close() }
    await expect(ready.checkBeforeSave()).rejects.toThrow(organizationExportErrors.pending)
    expect((await listLibraryOrganizationRecords('reader'))[0].record).toEqual(pending)
    expect(f.api.put).not.toHaveBeenCalled()
  })

  it('rechecks canonical source after the final asynchronous journal guard', async () => {
    const f = await setup(), ready = await prepareFreshOrganizationExport(f.options)
    const { readingTransaction, readingNamespace } = await import('./deviceReadingDatabase')
    const originalCheck = f.options.reader.checkReady
    f.options.reader.checkReady = async (...args) => {
      await originalCheck(...args)
      await readingTransaction(['exchanges'], 'readwrite', (tx, done) => {
        tx.objectStore('exchanges').delete([readingNamespace('reader'), f.book.id]); done(undefined)
      })
    }
    await expect(ready.checkBeforeSave()).rejects.toThrow()
  })

  it('rejects unbound copies, missing canonical capture, unsupported snapshots and total extension overflow', async () => {
    const f = await setup(); f.bindings.remove(f.book)
    await expect(prepareFreshOrganizationExport(f.options)).rejects.toThrow(organizationExportErrors.binding)
    await expect(prepareFreshOrganizationExport({ ...f.options, choices: [{ ...f.choice, capture: undefined }] })).rejects.toThrow(organizationExportErrors.source)
    for (const mode of ['unsupported', 'oversize']) {
      const doc: ExchangeDocument = validateExchangeDocument(structuredClone(original))
      if (mode === 'unsupported') doc.extensions!.libraryOrganizationSnapshot = { version: 2 }
      else doc.extensions!.padding = 'x'.repeat(256 * 1024 - new TextEncoder().encode(JSON.stringify(doc.extensions)).length - 20)
      const g = await setup(doc)
      await expect(prepareFreshOrganizationExport(g.options)).rejects.toThrow(mode === 'unsupported' ? organizationExportErrors.snapshot : organizationExportErrors.size)
    }
  })

  it('rejects account/selection expiration and abort before bytes can be saved', async () => {
    const f = await setup(), ready = await prepareFreshOrganizationExport(f.options)
    f.expire()
    await expect(ready.checkBeforeSave()).rejects.toThrow(organizationExportErrors.stale)
    const g = await setup()
    await expect(prepareFreshOrganizationExport({ ...g.options, signal: AbortSignal.abort() })).rejects.toThrow(organizationExportErrors.stale)
  })
})
