import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import translation from '../../../contracts/fixtures/library-identity-v1/translation.json'
import glossary from '../../../contracts/fixtures/book-glossary-snapshots-v1.json'
import { GlossaryRegistry } from '../components/BookGlossaryProvider'
import { BookGlossaryError, type BookGlossaryScope, type BookGlossaryView } from './bookGlossaryApi'
import { createBookGlossaryStore, listBookGlossaryRecords } from './bookGlossaryStore'
import type { ExchangeExportChoice, SavedExchange } from './exchangeLibrary'
import type { LibraryIdentityResult } from './libraryIdentityApi'
import { readExchange, validateExchangeDocument, writeExchange, type ExchangeDocument } from './libraryExchange'
import { createPortableBindings } from './portableBinding'
import { readBookGlossarySnapshotsFromDocument, withBookGlossarySnapshots } from './portableBookGlossary'
import { glossaryExportErrors, prepareFreshGlossaryExport } from './portableGlossaryExport'

const time = '2026-10-04T00:00:00Z', registries: GlossaryRegistry[] = []
const entries = structuredClone(glossary.snapshots[0].entries!) as NonNullable<BookGlossaryView['entries']>
const scope = { providerId: original.extensions.documentIdentity.contentProviderId, bookId: original.extensions.documentIdentity.bookId, targetLanguage: 'ko' }
beforeEach(() => { vi.stubGlobal('indexedDB', new IDBFactory()); vi.stubGlobal('crypto', webcrypto) })
afterEach(() => { registries.splice(0).forEach(value => value.close()); vi.unstubAllGlobals() })
const pack = (document: ExchangeDocument) => ({ createdAt: time, documents: [document], assets: [] })
function setup(document = validateExchangeDocument(structuredClone(original)), view: BookGlossaryView = { ...scope, version: 3, entries, updatedAt: time }) {
  let current = true
  const api = { get: vi.fn(async (_scope: BookGlossaryScope) => structuredClone(view)), put: vi.fn(), close: vi.fn() }
  const registry = new GlossaryRegistry('reader', api); registries.push(registry)
  const choice: ExchangeExportChoice = { key: document.id, title: document.bookTitle, kind: document.kind,
    glossarySource: { kind: 'server', recordId: original.extensions.serverRecordId }, load: vi.fn(async () => pack(document)) }
  const identityClient = { verify: vi.fn(async (recordId, identity) => ({ verified: true, kind: identity.kind, recordId, identity }) as LibraryIdentityResult), close: vi.fn() }
  const memory = new Map<string, string>()
  const storage = { getItem: (key: string) => memory.get(key) ?? null, setItem: (key: string, value: string) => { memory.set(key, value) }, removeItem: (key: string) => { memory.delete(key) } }
  const bindings = createPortableBindings('reader', 'https://account.example', storage)
  const options = { choices: [choice], targetLanguage: 'ko', reader: registry.freshReader(() => current), identityClient, bindings, current: () => current }
  return { options, api, registry, choice, document, bindings, expire: () => { current = false } }
}
async function pending(inputScope = scope) {
  const store = createBookGlossaryStore('reader', inputScope)
  try {
    await store.initialize(null)
    await store.transact(row => ({ ...row, enabled: true, selected: true, local: entries, remote: { ...inputScope, entries: [], version: 1, updatedAt: time },
      pending: { entries, expectedVersion: 1, mutationId: 'b85863f3-187c-4074-94d4-24e7a5fb20ad' } }))
  } finally { store.close() }
}

describe('explicit fresh account glossary export', () => {
  it.each([
    ['absent', 0, null, null], ['deleted', 3, null, time], ['present', 3, [], time], ['present', 3, entries, time],
  ] as const)('exports %s version %s as passive exact metadata with no PUT or journal initialization', async (presence, version, input, updatedAt) => {
    const state = setup(undefined, { ...scope, version, entries: structuredClone(input) as BookGlossaryView['entries'], updatedAt })
    state.document.glossary = [{ source: 'legacy', target: ' separate ', enabled: false, caseSensitive: true }]
    const before = structuredClone(state.document)
    const output = await readExchange(await prepareFreshGlossaryExport(state.options))
    const exported = output.documents[0], snapshots = readBookGlossarySnapshotsFromDocument(exported)!
    expect(snapshots).toEqual({ version: 1, snapshots: [{ ...scope, presence, entries: input }] })
    expect(exported.glossary).toEqual(before.glossary)
    expect(exported.extensions).toMatchObject(before.extensions!)
    expect(state.document).toEqual(before)
    expect(state.api.get).toHaveBeenCalledExactlyOnceWith(scope, undefined)
    expect(state.api.put).not.toHaveBeenCalled()
    expect(await listBookGlossaryRecords('reader')).toEqual([])
    for (const forbidden of ['expectedVersion', 'mutationId', 'username', 'origin', 'password', 'retryAfterUntil', 'updatedAt']) expect(JSON.stringify(snapshots)).not.toContain(`"${forbidden}":`)
    if (input?.length) expect(snapshots.snapshots[0].entries).toEqual(entries)
  })

  it('replaces only the exact selected scope, preserving other language scopes, order, aliases and sibling metadata', async () => {
    const state = setup()
    const existing = { version: 1, snapshots: [
      { ...scope, targetLanguage: 'ja', presence: 'present', entries },
      { ...scope, presence: 'deleted', entries: null },
      { ...scope, providerId: 'another-provider', presence: 'absent', entries: null },
    ] }
    state.document = withBookGlossarySnapshots(state.document, existing)
    state.choice.load = async () => pack(state.document)
    const output = await readExchange(await prepareFreshGlossaryExport(state.options))
    expect(readBookGlossarySnapshotsFromDocument(output.documents[0])!.snapshots).toEqual([
      existing.snapshots[0], { ...scope, presence: 'present', entries }, existing.snapshots[2],
    ])
    expect(readBookGlossarySnapshotsFromDocument(state.document)).toEqual(existing)
  })

  it('requires current account identity proof and rejects local files even if they contain a UUID hint', async () => {
    const state = setup()
    state.choice.glossarySource = undefined
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow(glossaryExportErrors.identity)
    expect(state.api.get).not.toHaveBeenCalled()
    state.choice.glossarySource = { kind: 'server', recordId: original.extensions.serverRecordId }
    state.options.identityClient.verify.mockRejectedValueOnce(new Error('not-found'))
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow('not-found')
    expect(state.api.get).not.toHaveBeenCalled()
    state.document.paragraphs[0].text += ' changed'
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow(glossaryExportErrors.identity)
    expect(state.api.get).not.toHaveBeenCalled()
  })

  it('requires an explicit imported-copy binding and rechecks it after ZIP encoding', async () => {
    const state = setup(), book: SavedExchange = { id: 'portable-copy', document: state.document, assets: [], checksum: '', importedAt: time, integratedNoteIds: [] }
    state.choice.glossarySource = { kind: 'portable', book }
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow(glossaryExportErrors.binding)
    expect(state.api.get).not.toHaveBeenCalled()
    const proof = await state.options.identityClient.verify(original.extensions.serverRecordId, original.extensions.documentIdentity as LibraryIdentityResult['identity'])
    await state.bindings.confirm(book, proof)
    await expect(prepareFreshGlossaryExport({ ...state.options, encode: async value => { const bytes = await writeExchange(value); state.bindings.remove(book); return bytes } })).rejects.toThrow(glossaryExportErrors.binding)
    await state.bindings.confirm(book, proof)
    const output = await readExchange(await prepareFreshGlossaryExport(state.options))
    expect(readBookGlossarySnapshotsFromDocument(output.documents[0])!.snapshots[0]).toMatchObject(scope)
    expect(state.api.put).not.toHaveBeenCalled()
  })

  it.each(['KO', ' ko', 'ko ', 'auto', 'zh_CN'])('refuses non-exact language %s before any server read', async targetLanguage => {
    const state = setup()
    await expect(prepareFreshGlossaryExport({ ...state.options, targetLanguage })).rejects.toThrow(glossaryExportErrors.language)
    expect(state.api.get).not.toHaveBeenCalled(); expect(state.options.identityClient.verify).not.toHaveBeenCalled()
  })

  it('requires a translated book to use its exact target language', async () => {
    const state = setup(validateExchangeDocument(structuredClone(translation)))
    await expect(prepareFreshGlossaryExport({ ...state.options, targetLanguage: 'ja' })).rejects.toThrow(glossaryExportErrors.translationLanguage)
    expect(state.api.get).not.toHaveBeenCalled()
  })

  it.each(['providerId', 'bookId', 'targetLanguage'] as const)('refuses a fresh response with a different %s', async key => {
    const state = setup(undefined, { ...scope, [key]: key === 'targetLanguage' ? 'ja' : 'other', version: 1, entries, updatedAt: time })
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow(glossaryExportErrors.response)
  })

  it('preserves unsupported snapshots during ordinary export but refuses to overwrite them with a fresh snapshot', async () => {
    const state = setup()
    state.document.extensions!.bookGlossarySnapshots = { version: 2, data: ['preserve'] }
    const before = structuredClone(state.document)
    expect((await readExchange(await writeExchange(pack(state.document)))).documents[0]).toEqual(before)
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow(glossaryExportErrors.snapshot)
    expect(state.document).toEqual(before); expect(state.api.get).not.toHaveBeenCalled()
  })

  it('rejects combined extensions above 256 KiB without truncating glossary or sibling contents', async () => {
    const state = setup()
    state.document.extensions!.opaque = 'x'.repeat(256 * 1024 - new TextEncoder().encode(JSON.stringify({ ...state.document.extensions, opaque: '' })).length)
    const before = structuredClone(state.document)
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow(glossaryExportErrors.size)
    expect(state.document).toEqual(before)
  })

  it.each(['load', 'verify', 'read', 'encode'] as const)('discards a session changed during %s and never produces export bytes', async stage => {
    const state = setup()
    if (stage === 'load') state.choice.load = async () => { state.expire(); return pack(state.document) }
    if (stage === 'verify') state.options.identityClient.verify.mockImplementation(async (recordId, identity) => { state.expire(); return { verified: true, recordId, identity, kind: identity.kind } })
    if (stage === 'read') state.api.get.mockImplementation(async () => { state.expire(); return { ...scope, version: 0, entries: null, updatedAt: null } })
    const encode = stage === 'encode' ? async () => { state.expire(); return new Uint8Array([1]) } : undefined
    await expect(prepareFreshGlossaryExport({ ...state.options, encode })).rejects.toThrow(glossaryExportErrors.stale)
    expect(state.api.put).not.toHaveBeenCalled()
  })

  it('discards registry closure during a late response, even when a custom transport ignores abort', async () => {
    const state = setup()
    state.api.get.mockImplementation(async () => { state.registry.close(); return { ...scope, version: 0, entries: null, updatedAt: null } })
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow(glossaryExportErrors.stale)
  })

  it('does not revive a previous ticket after the same username reconnects with a new client', async () => {
    const state = setup()
    state.api.get.mockImplementation(async () => {
      state.registry.close()
      const replacement = new GlossaryRegistry('reader', { get: vi.fn(), put: vi.fn(), close: vi.fn() }); registries.push(replacement)
      expect(replacement.username).toBe(state.registry.username)
      return { ...scope, version: 0, entries: null, updatedAt: null }
    })
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow(glossaryExportErrors.stale)
    expect(state.api.put).not.toHaveBeenCalled()
  })

  it('rejects canceled tickets before loading or after encoding', async () => {
    const state = setup()
    await expect(prepareFreshGlossaryExport({ ...state.options, signal: AbortSignal.abort() })).rejects.toThrow(glossaryExportErrors.stale)
    expect(state.choice.load).not.toHaveBeenCalled()
    const controller = new AbortController()
    await expect(prepareFreshGlossaryExport({ ...state.options, signal: controller.signal, encode: async () => { controller.abort(); return new Uint8Array() } })).rejects.toThrow(glossaryExportErrors.stale)
  })

  it.each(['before', 'read', 'encode'] as const)('refuses pending changes created %s export without flushing or changing that journal', async stage => {
    const state = setup()
    if (stage === 'before') await pending()
    if (stage === 'read') state.api.get.mockImplementation(async () => { await pending(); return { ...scope, version: 3, entries, updatedAt: time } })
    const encode = stage === 'encode' ? async () => { await pending(); return new Uint8Array([1]) } : undefined
    await expect(prepareFreshGlossaryExport({ ...state.options, encode })).rejects.toThrow(glossaryExportErrors.pending)
    expect(state.api.put).not.toHaveBeenCalled()
    expect((await listBookGlossaryRecords('reader'))[0].record.pending?.mutationId).toBe('b85863f3-187c-4074-94d4-24e7a5fb20ad')
    if (stage === 'before') expect(state.api.get).not.toHaveBeenCalled()
  })

  it('checks previously read scopes again when a later book develops an earlier-book conflict', async () => {
    const state = setup(), nextDocument = structuredClone(state.document)
    nextDocument.id = 'second-book'
    const identity = nextDocument.extensions!.documentIdentity as LibraryIdentityResult['identity']; identity.bookId = 'second-original-book'
    const next = { ...state.choice, key: 'second-book', load: async () => pack(nextDocument) }
    state.api.get.mockImplementation(async incoming => {
      if (incoming.bookId === 'second-original-book') {
        await pending()
        const store = createBookGlossaryStore('reader', scope)
        await store.transact(row => ({ ...row, conflict: row.remote })); store.close()
      }
      return { ...incoming, version: 3, entries, updatedAt: time }
    })
    await expect(prepareFreshGlossaryExport({ ...state.options, choices: [state.choice, next] })).rejects.toThrow(glossaryExportErrors.pending)
    expect(state.api.get).toHaveBeenCalledTimes(2); expect(state.api.put).not.toHaveBeenCalled()
  })

  it('does not label an older response as fresh when a newer confirmed view is already known', async () => {
    const state = setup(), store = createBookGlossaryStore('reader', scope)
    await store.initialize(null)
    await store.transact(row => ({ ...row, remote: { ...scope, version: 4, entries: [], updatedAt: time } })); store.close()
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow(glossaryExportErrors.response)
  })

  it('propagates actual read failure without falling back to a cached or legacy glossary', async () => {
    const state = setup()
    state.api.get.mockRejectedValue(new BookGlossaryError('network'))
    await expect(prepareFreshGlossaryExport(state.options)).rejects.toThrow('network')
    expect(state.api.put).not.toHaveBeenCalled()
  })
})
