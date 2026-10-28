import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import fixtures from '../../../contracts/fixtures/reading-progress-snapshot-v1.json'
import { ProgressRegistry } from '../components/ReadingProgressProvider'
import { OrganizationRegistry } from '../components/LibraryOrganizationProvider'
import { GlossaryRegistry } from '../components/BookGlossaryProvider'
import { createExchangeLibrary, exchangeExportChoices } from './exchangeLibrary'
import { createPortableBindings } from './portableBinding'
import { readingNamespace, readingTransaction } from './deviceReadingDatabase'
import { readExchange, writeExchange, validateExchangeDocument, type ExchangeDocument } from './libraryExchange'
import { validateLibraryIdentity } from './libraryIdentity'
import type { LibraryIdentityClient } from './libraryIdentityApi'
import { readReadingProgressSnapshotFromDocument } from './portableReadingProgress'
import { readBookGlossarySnapshotsFromDocument, withBookGlossarySnapshots } from './portableBookGlossary'
import { readLibraryOrganizationSnapshotFromDocument } from './portableLibraryOrganization'
import { ReadingProgressError, type ReadingProgressView } from './readingProgressApi'
import { createReadingProgressStore, emptyReadingProgress } from './readingProgressStore'
import { readingProgressExportErrors } from './portableReadingProgressExport'
import { accountExchangeExportErrorMessage, accountExportErrors, prepareAccountExchangeExport } from './portableAccountExport'
import type { BookGlossaryEntry, BookGlossaryView } from './bookGlossaryApi'
import type { LibraryOrganizationView } from './libraryOrganizationApi'

const time = '2026-10-27T00:00:00Z', registries: { close(): void }[] = []
let sequence = 0
beforeEach(() => { vi.stubGlobal('indexedDB', new IDBFactory()); vi.stubGlobal('crypto', webcrypto) })
afterEach(() => { registries.splice(0).forEach(value => value.close()); vi.restoreAllMocks(); vi.unstubAllGlobals() })
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }

async function setup(name = 'original-present', change?: (document: ExchangeDocument) => void) {
  const source = fixtures.cases.find(value => value.name === name)!
  let document = validateExchangeDocument(structuredClone(source.document))
  document.id = `progress-composer-${++sequence}`
  const identity = validateLibraryIdentity(source.snapshot.identity)
  const recordId = identity.kind === 'ORIGINAL' ? 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' : 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
  document.extensions = { ...document.extensions, documentIdentity: identity, serverRecordId: recordId,
    preservedUnknown: { exact: ['é', 'e\u0301', 'middle  spaces'] } }
  const first = document.paragraphs[0]
  document.position = { paragraphId: first.paragraphId, characterOffset: first.text.length }
  document.notes = first.text ? [{ id: 'legacy-note', kind: 'bookmark', title: 'Keep', text: '', excerpt: '',
    anchor: { paragraphId: first.paragraphId, characterOffset: 0 }, createdAt: time }] : []
  const glossaryScope = { providerId: identity.contentProviderId, bookId: identity.bookId, targetLanguage: 'ko' }
  document = withBookGlossarySnapshots(document, { version: 1, snapshots: [
    ...readBookGlossarySnapshotsFromDocument(document)!.snapshots.filter(value => value.providerId !== glossaryScope.providerId || value.bookId !== glossaryScope.bookId),
    { ...glossaryScope, targetLanguage: 'ja', presence: 'present', entries: [] },
    { ...glossaryScope, presence: 'absent', entries: null },
  ] })
  change?.(document)
  const library = createExchangeLibrary('reader')
  await library.importPackage({ createdAt: time, documents: [document], assets: [] })
  const book = (await library.list()).find(value => value.document.id === document.id)!
  const choice = (await exchangeExportChoices('reader')).find(value => value.key === book.id)!
  const result = { verified: true as const, recordId, kind: identity.kind, identity }
  const identityClient = { verify: vi.fn<LibraryIdentityClient['verify']>(async () => structuredClone(result)), close() {} }
  const scope = { kind: identity.kind, recordId }
  const progressView: ReadingProgressView = { ...scope, version: source.snapshot.presence === 'absent' ? 0 : 3,
    anchor: source.snapshot.anchor ? { ...source.snapshot.anchor } : null, updatedAt: source.snapshot.presence === 'absent' ? null : time }
  const progressApi = { get: vi.fn(async () => structuredClone(progressView)), put: vi.fn(), close() {} }
  const organizationView: LibraryOrganizationView = { ...scope, version: 3,
    organization: { folder: 'Folder  中', tags: ['é', 'e\u0301', 'Tag', 'tag', '中  文'], favorite: true }, updatedAt: time }
  const glossaryView: BookGlossaryView = { ...glossaryScope, version: 3, entries: [], updatedAt: time }
  const orgApi = { get: vi.fn(async () => structuredClone(organizationView)), put: vi.fn(), close() {} }
  const glossaryApi = { get: vi.fn(async () => structuredClone(glossaryView)), put: vi.fn(), close() {} }
  const progressRegistry = new ProgressRegistry('reader', progressApi), orgRegistry = new OrganizationRegistry('reader', orgApi), glossaryRegistry = new GlossaryRegistry('reader', glossaryApi)
  registries.push(progressRegistry, orgRegistry, glossaryRegistry)
  const memory = new Map<string, string>(), bindings = createPortableBindings('reader', 'https://account.example', {
    getItem: key => memory.get(key) ?? null, setItem: (key, value) => { memory.set(key, value) }, removeItem: key => { memory.delete(key) },
  })
  await bindings.confirm(book, result)
  let active = true
  const options: Parameters<typeof prepareAccountExchangeExport>[0] = {
    choices: [choice], progress: { reader: progressRegistry.freshReader(() => active) },
    identityClient, bindings, current: () => active,
  }
  const remove = () => readingTransaction(['exchanges'], 'readwrite', (tx, done) => { tx.objectStore('exchanges').delete([readingNamespace('reader'), book.id]); done(undefined) })
  const replace = (document: ExchangeDocument) => readingTransaction(['exchanges'], 'readwrite', (tx, done) => {
    const store = tx.objectStore('exchanges'), request = store.get([readingNamespace('reader'), book.id])
    request.onsuccess = () => { store.put({ ...request.result, document }); done(undefined) }
  })
  const combined = () => {
    options.glossary = { reader: glossaryRegistry.freshReader(() => active), targetLanguage: 'ko' }
    options.organization = { reader: orgRegistry.freshReader(() => active) }
  }
  return { document, library, book, choice, options, identityClient, bindings, result, scope, progressView, progressApi,
    progressRegistry, glossaryScope, glossaryView, organizationView, orgApi, glossaryApi, combined, remove, replace,
    expire: () => { active = false } }
}

describe('latest progress in guarded canonical account ZIPs', () => {
  it.each(['original-present', 'original-absent', 'translation-present', 'translation-absent-no-sibling',
    'original-paragraph-end-no-sibling', 'translation-paragraph-end', 'original-empty-paragraph-whitespace-id'])('%s preserves exact text scope, account anchor and legacy reading metadata', async name => {
    const f = await setup(name), ready = await prepareAccountExchangeExport(f.options)
    await ready.checkBeforeSave(); ready.assertCurrent()
    const output = (await readExchange(ready.bytes)).documents[0]
    expect(await readReadingProgressSnapshotFromDocument(output)).toEqual({ version: 1, identity: f.result.identity,
      presence: f.progressView.version === 0 ? 'absent' : 'present', anchor: f.progressView.anchor })
    expect(output.position).toEqual(f.document.position); expect(output.notes).toEqual(f.document.notes)
    expect(output.paragraphs).toEqual(f.document.paragraphs); expect(output.organization).toEqual(f.document.organization)
    expect(output.extensions!.preservedUnknown).toEqual(f.document.extensions!.preservedUnknown)
    expect(f.progressApi.get).toHaveBeenCalledTimes(1); expect(f.progressApi.put).not.toHaveBeenCalled()
    expect(await f.progressRegistry.store.snapshotAliases(f.scope)).toEqual([])
    const snapshot = JSON.stringify(output.extensions!.readingProgressSnapshot)
    for (const key of ['recordId', 'username', 'origin', 'bindingNonce', 'expectedVersion', 'mutationId', 'updatedAt']) expect(snapshot).not.toContain(`"${key}":`)
  })

  it('accumulates 500 glossary entries, organization and progress without losing scopes or legacy data', async () => {
    const f = await setup(); f.combined()
    const entries: BookGlossaryEntry[] = Array.from({ length: 500 }, (_, i) => ({ id: `id-${i}`, sourceTerm: ` Source  ${i} `,
      translatedTerm: ` 번역 ${i} `, displayTerm: i % 2 ? ` Alias ${i} ` : '', kind: i % 3 ? 'Character' : 'Place', caseSensitive: i % 2 === 0, enabled: i % 3 === 0 }))
    f.glossaryView.entries = entries
    const before = structuredClone(f.document), capture = vi.spyOn(f.choice, 'capture')
    const ready = await prepareAccountExchangeExport(f.options), output = (await readExchange(ready.bytes)).documents[0]
    const glossary = readBookGlossarySnapshotsFromDocument(output)!
    expect(glossary.snapshots.find(value => value.providerId === f.glossaryScope.providerId && value.bookId === f.glossaryScope.bookId && value.targetLanguage === 'ko')).toEqual({ ...f.glossaryScope, presence: 'present', entries })
    expect(glossary.snapshots.find(value => value.providerId === f.glossaryScope.providerId && value.targetLanguage === 'ja')).toEqual({ ...f.glossaryScope, targetLanguage: 'ja', presence: 'present', entries: [] })
    expect(await readLibraryOrganizationSnapshotFromDocument(output)).toEqual({ version: 1, identity: f.result.identity, presence: 'present', organization: f.organizationView.organization })
    expect(await readReadingProgressSnapshotFromDocument(output)).toMatchObject({ presence: 'present', anchor: f.progressView.anchor })
    for (const field of ['position', 'notes', 'organization', 'glossary', 'paragraphs', 'outline'] as const) expect(output[field]).toEqual(before[field])
    expect(output.extensions!.preservedUnknown).toEqual(before.extensions!.preservedUnknown)
    expect(capture).toHaveBeenCalledOnce(); expect(f.identityClient.verify).toHaveBeenCalledOnce()
    expect(f.progressApi.get).toHaveBeenCalledOnce(); expect(f.orgApi.get).toHaveBeenCalledOnce(); expect(f.glossaryApi.get).toHaveBeenCalledOnce()
    for (const api of [f.progressApi, f.orgApi, f.glossaryApi]) expect(api.put).not.toHaveBeenCalled()
  })

  it('ordinary ZIPs preserve future opaque progress without identity or progress requests', async () => {
    const f = await setup('original-present', document => { document.extensions!.readingProgressSnapshot = { version: 9, future: ['preserve'] } })
    const ready = await prepareAccountExchangeExport({ ...f.options, progress: undefined, identityClient: null })
    expect((await readExchange(ready.bytes)).documents[0]).toEqual(f.document)
    expect(f.progressApi.get).not.toHaveBeenCalled(); expect(f.identityClient.verify).not.toHaveBeenCalled()
    await f.remove(); await expect(ready.checkBeforeSave()).rejects.toThrow()
  })

  it.each(['unsupported', 'malformed', 'mismatch'] as const)('rejects %s existing own snapshot before progress GET', async mode => {
    const f = await setup('original-present', document => {
      const identity = validateLibraryIdentity(document.extensions!.documentIdentity)
      document.extensions!.readingProgressSnapshot = mode === 'unsupported' ? { version: 2 } : {
        version: 1, identity: mode === 'mismatch' ? { ...identity, bookId: 'wrong-book' } : identity,
        presence: 'present', anchor: mode === 'malformed' ? null : { paragraphId: document.paragraphs[0].paragraphId, characterOffset: 0 },
      }
    })
    await expect(prepareAccountExchangeExport(f.options)).rejects.toThrow(accountExportErrors.progressSnapshot)
    expect(f.progressApi.get).not.toHaveBeenCalled()
    expect((await f.library.get(f.book.id))!.document).toEqual(f.document)
  })

  it.each(['missing', 'surrogate', 'beyond-end'] as const)('rejects latest %s anchor without moving legacy position', async mode => {
    const f = await setup(), first = f.document.paragraphs[0]
    f.progressView.anchor = { paragraphId: mode === 'missing' ? 'missing' : first.paragraphId,
      characterOffset: mode === 'surrogate' ? first.text.indexOf('🌏') + 1 : mode === 'beyond-end' ? first.text.length + 1 : 0 }
    await expect(prepareAccountExchangeExport(f.options)).rejects.toThrow(readingProgressExportErrors.response)
    expect((await f.library.get(f.book.id))!.document.position).toEqual(f.document.position)
    expect(f.progressApi.put).not.toHaveBeenCalled()
  })

  it.each(['pending', 'queued', 'conflict'] as const)('final readiness rejects %s introduced after preparation without touching it', async mode => {
    const f = await setup(), ready = await prepareAccountExchangeExport(f.options)
    const mutation = { expectedVersion: 3, mutationId: 'cccccccc-cccc-cccc-cccc-cccccccccccc', anchor: f.progressView.anchor! }
    const store = createReadingProgressStore('reader'), saved = await store.update(f.scope, () => ({ ...emptyReadingProgress(f.scope),
      remote: f.progressView, pending: mutation, queued: mode === 'queued' ? f.progressView.anchor : null,
      conflict: mode === 'conflict' ? f.progressView : null, retryAfterUntil: 9_000_000 }))
    await expect(ready.checkBeforeSave()).rejects.toThrow(readingProgressExportErrors.pending)
    expect(await store.get(f.scope)).toEqual(saved); expect(f.progressApi.put).not.toHaveBeenCalled()
  })

  it.each(['progress', 'organization', 'glossary'] as const)('rechecks canonical source after final %s journal await', async selected => {
    const f = await setup(); f.combined()
    const ready = await prepareAccountExchangeExport(f.options), reader = f.options[selected]!.reader, originalCheck = reader.checkReady
    reader.checkReady = async (...args: never[]) => { await (originalCheck as (...args: never[]) => Promise<void>)(...args); await f.remove() }
    await expect(ready.checkBeforeSave()).rejects.toThrow()
    expect(f.progressApi.put).not.toHaveBeenCalled()
  })

  it.each(['nonce', 'metadata', 'session'].flatMap(mode => ['encode', 'save'].map(phase => [mode, phase])))('rejects %s change at %s', async (mode, phase) => {
      const f = await setup(), mutate = async () => {
        if (mode === 'nonce') { f.bindings.remove(f.book); await f.bindings.confirm(f.book, f.result) }
        if (mode === 'metadata') await f.replace({ ...f.document, chapterTitle: 'changed canonical metadata' })
        if (mode === 'session') f.expire()
      }
      if (phase === 'save') { const ready = await prepareAccountExchangeExport(f.options); await mutate(); await expect(ready.checkBeforeSave()).rejects.toThrow() }
      else await expect(prepareAccountExchangeExport({ ...f.options, encode: async value => { await mutate(); return writeExchange(value) } })).rejects.toThrow()
  })

  it('uses canonical bytes despite forged inventory and cached book copies', async () => {
    const f = await setup()
    f.book.document.bookTitle = 'forged cached book'
    if (f.choice.glossarySource?.kind === 'portable') f.choice.glossarySource.book.document.paragraphs[0].text = 'forged cached body'
    const output = (await readExchange((await prepareAccountExchangeExport(f.options)).bytes)).documents[0]
    expect(output.bookTitle).toBe(f.document.bookTitle); expect(output.paragraphs).toEqual(f.document.paragraphs)
  })

  it('retains fixed reader/current/binding authority references while GET waits', async () => {
    const f = await setup(), held = deferred<ReadingProgressView>(), entered = deferred<void>()
    f.progressApi.get.mockImplementationOnce(async () => { entered.resolve(); return held.promise })
    const preparing = prepareAccountExchangeExport(f.options)
    await entered.promise
    f.options.current = () => true
    f.options.progress = { reader: { assertCurrent() {}, read: async () => f.progressView, checkReady: async () => {} } }
    f.options.bindings = { capture: async () => { throw new Error('must not use replaced bindings') } }
    f.options.choices.splice(0)
    f.expire(); held.resolve(f.progressView)
    await expect(preparing).rejects.toThrow()
    expect(f.progressApi.put).not.toHaveBeenCalled()
  })

  it('captures the current authority function even when the reader has an independent guard', async () => {
    const f = await setup(), held = deferred<ReadingProgressView>(), entered = deferred<void>()
    f.options.progress = { reader: { assertCurrent() {}, read: async () => { entered.resolve(); return held.promise }, checkReady: async () => {} } }
    const preparing = prepareAccountExchangeExport(f.options)
    await entered.promise; f.options.current = () => true; f.expire(); held.resolve(f.progressView)
    await expect(preparing).rejects.toThrow()
  })

  it('retains the binding capture factory before the first canonical await', async () => {
    const f = await setup(), canonical = await f.choice.capture!(), held = deferred<typeof canonical>(), entered = deferred<void>()
    f.choice.capture = async () => { entered.resolve(); return held.promise }
    const preparing = prepareAccountExchangeExport(f.options)
    await entered.promise
    const replacement = vi.fn(async () => { throw new Error('replaced binding authority') })
    f.options.bindings.capture = replacement
    held.resolve(canonical)
    const ready = await preparing; await ready.checkBeforeSave()
    expect(replacement).not.toHaveBeenCalled()
    expect(await readReadingProgressSnapshotFromDocument((await readExchange(ready.bytes)).documents[0])).toMatchObject({ anchor: f.progressView.anchor })
  })

  it('captures later selection locators before GET and deduplicates one scope without losing metadata', async () => {
    const f = await setup(), g = await setup('original-present', document => { document.chapterTitle = 'Second metadata' })
    await f.bindings.confirm(g.book, g.result)
    const captureBinding = vi.spyOn(f.bindings, 'capture')
    f.options.choices.push(g.choice)
    const held = deferred<ReadingProgressView>(), entered = deferred<void>()
    f.progressApi.get.mockImplementationOnce(async () => { entered.resolve(); return held.promise })
    const preparing = prepareAccountExchangeExport(f.options)
    await entered.promise
    g.choice.key = 'forged-choice-key'
    g.choice.glossarySource = { kind: 'server', recordId: g.scope.recordId }
    g.choice.capture = async () => { throw new Error('replaced later source') }
    held.resolve(f.progressView)
    const ready = await preparing, output = (await readExchange(ready.bytes)).documents
    expect(output).toHaveLength(2)
    expect(output.map(document => document.chapterTitle)).toContain('Second metadata')
    expect(captureBinding).toHaveBeenCalledTimes(2); expect(f.progressApi.get).toHaveBeenCalledTimes(1)
    for (const document of output) expect(await readReadingProgressSnapshotFromDocument(document)).toMatchObject({ identity: f.result.identity, anchor: f.progressView.anchor })
  })

  it('does not reuse a scope-cached snapshot for a different full text identity', async () => {
    const f = await setup(), g = await setup('original-language-case')
    await f.bindings.confirm(g.book, g.result)
    f.identityClient.verify.mockImplementation(async (recordId, identity) => ({ verified: true, recordId, kind: identity.kind, identity }))
    f.options.choices.push(g.choice)
    await expect(prepareAccountExchangeExport(f.options)).rejects.toThrow()
    expect(f.progressApi.get).toHaveBeenCalledTimes(1); expect(f.progressApi.put).not.toHaveBeenCalled()
  })

  it.each(['newer', 'anchor', 'timestamp'] as const)('refuses prepared bytes after %s journal observation changes', async mode => {
    const f = await setup(), ready = await prepareAccountExchangeExport(f.options), originalBytes = ready.bytes.slice()
    const next = { ...f.progressView, version: mode === 'newer' ? 4 : 3,
      anchor: mode === 'anchor' ? { ...f.progressView.anchor!, characterOffset: 1 } : f.progressView.anchor,
      updatedAt: mode === 'timestamp' ? '2026-10-27T00:01:00Z' : time }
    const store = createReadingProgressStore('reader'), journal = await store.update(f.scope, () => ({ ...emptyReadingProgress(f.scope), remote: next }))
    await expect(ready.checkBeforeSave()).rejects.toThrow(readingProgressExportErrors.response)
    expect(await store.get(f.scope)).toEqual(journal); expect(ready.bytes).toEqual(originalBytes)
    expect(f.progressApi.get).toHaveBeenCalledTimes(1); expect(f.progressApi.put).not.toHaveBeenCalled()
  })

  it.each(['local', 'asset'] as const)('rejects %s canonical captures for account text progress without a GET', async mode => {
    const f = await setup(), canonical = await f.choice.capture!()
    if (mode === 'local') canonical.value.documents[0].kind = 'local'
    else canonical.value.documents[0].assets = [{ path: 'source.pdf', role: 'pdf' }]
    f.choice.capture = async () => canonical
    await expect(prepareAccountExchangeExport(f.options)).rejects.toThrow()
    expect(f.identityClient.verify).not.toHaveBeenCalled(); expect(f.progressApi.get).not.toHaveBeenCalled()
  })

  it('rejects invalid response scope and maps transport failures without exposing bodies', async () => {
    const f = await setup()
    f.progressApi.get.mockResolvedValueOnce({ ...f.progressView, recordId: 'dddddddd-dddd-dddd-dddd-dddddddddddd' })
    await expect(prepareAccountExchangeExport(f.options)).rejects.toThrow(readingProgressExportErrors.response)
    expect(accountExchangeExportErrorMessage(new ReadingProgressError('storage'))).toBe(accountExportErrors.journal)
    expect(accountExchangeExportErrorMessage(new ReadingProgressError('authentication'))).toBe(accountExportErrors.offline)
    expect(accountExchangeExportErrorMessage(new ReadingProgressError('network'))).toBe(accountExportErrors.network)
    expect(accountExchangeExportErrorMessage(new Error('raw sensitive body'))).toBe(accountExportErrors.invalid)
    expect(accountExchangeExportErrorMessage(new Error(readingProgressExportErrors.pending))).toBe(readingProgressExportErrors.pending)
  })

  it('rejects combined metadata overflow explicitly without dropping the existing extensions', async () => {
    const f = await setup(), captured = await f.choice.capture!(), original = structuredClone(captured.value.documents[0].extensions)
    captured.value.documents[0].extensions!.padding = 'x'.repeat(256 * 1024 - new TextEncoder().encode(JSON.stringify(captured.value.documents[0].extensions)).length - 20)
    f.choice.capture = async () => captured
    await expect(prepareAccountExchangeExport(f.options)).rejects.toThrow(accountExportErrors.progressSize)
    expect((await f.library.get(f.book.id))!.document.extensions).toEqual(original)
    expect(f.progressApi.put).not.toHaveBeenCalled()
  })
})
