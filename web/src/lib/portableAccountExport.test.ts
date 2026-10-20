import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import { OrganizationRegistry } from '../components/LibraryOrganizationProvider'
import { GlossaryRegistry } from '../components/BookGlossaryProvider'
import { BookGlossaryError, type BookGlossaryEntry, type BookGlossaryView } from './bookGlossaryApi'
import { LibraryOrganizationError, type LibraryOrganizationView } from './libraryOrganizationApi'
import { createExchangeLibrary, exchangeExportChoices } from './exchangeLibrary'
import { createPortableBindings } from './portableBinding'
import { createLibraryOrganizationStore, listLibraryOrganizationRecords } from './libraryOrganizationStore'
import { listBookGlossaryRecords } from './bookGlossaryStore'
import { readingNamespace, readingTransaction } from './deviceReadingDatabase'
import { readExchange, writeExchange, validateExchangeDocument } from './libraryExchange'
import { validateLibraryIdentity } from './libraryIdentity'
import { readBookGlossarySnapshotsFromDocument, withBookGlossarySnapshots } from './portableBookGlossary'
import { readLibraryOrganizationSnapshotFromDocument } from './portableLibraryOrganization'
import { glossaryExportErrors } from './portableGlossaryExport'
import { organizationExportErrors } from './portableOrganizationExport'
import { accountExchangeExportErrorMessage, accountExportErrors, prepareAccountExchangeExport } from './portableAccountExport'

const time = '2026-10-20T00:00:00Z', registries: { close(): void }[] = []
beforeEach(() => { vi.stubGlobal('indexedDB', new IDBFactory()); vi.stubGlobal('crypto', webcrypto) })
afterEach(() => { registries.splice(0).forEach(registry => registry.close()); vi.unstubAllGlobals() })

async function setup() {
  const identity = validateLibraryIdentity(original.extensions.documentIdentity)
  const glossaryScope = { providerId: identity.contentProviderId, bookId: identity.bookId, targetLanguage: 'ko' }
  const document = withBookGlossarySnapshots(validateExchangeDocument(structuredClone(original)), { version: 1, snapshots: [
    { ...glossaryScope, targetLanguage: 'ja', presence: 'present', entries: [] },
    { ...glossaryScope, presence: 'deleted', entries: null },
  ] })
  document.glossary = [{ source: 'legacy', target: ' Separate ', enabled: false, caseSensitive: true }]
  document.organization = { folder: 'Legacy', tags: ['old'], favorite: false }
  document.extensions!.unknownSibling = { preserved: ['é', 'e\u0301'] }
  const library = createExchangeLibrary('reader')
  await library.importPackage({ createdAt: time, documents: [document], assets: [] })
  const book = (await library.list())[0], choice = (await exchangeExportChoices('reader')).find(value => value.key === book.id)!
  const result = { verified: true as const, recordId: original.extensions.serverRecordId, kind: identity.kind, identity }
  const identityClient = { verify: vi.fn(async () => structuredClone(result)), close() {} }
  const organizationScope = { kind: identity.kind, recordId: result.recordId }
  const organizationView: LibraryOrganizationView = { ...organizationScope, version: 3,
    organization: { folder: 'Folder  中', tags: ['é', 'e\u0301', '中  文'], favorite: true }, updatedAt: time }
  const glossaryView: BookGlossaryView = { ...glossaryScope, version: 3, entries: [], updatedAt: time }
  const orgApi = { get: vi.fn(async () => structuredClone(organizationView)), put: vi.fn(), close() {} }
  const glossaryApi = { get: vi.fn(async () => structuredClone(glossaryView)), put: vi.fn(), close() {} }
  const orgRegistry = new OrganizationRegistry('reader', orgApi), glossaryRegistry = new GlossaryRegistry('reader', glossaryApi)
  registries.push(orgRegistry, glossaryRegistry)
  const memory = new Map<string, string>(), bindings = createPortableBindings('reader', 'https://account.example', {
    getItem: key => memory.get(key) ?? null, setItem: (key, value) => { memory.set(key, value) }, removeItem: key => { memory.delete(key) },
  })
  await bindings.confirm(book, result)
  let active = true
  const options = { choices: [choice], glossary: { reader: glossaryRegistry.freshReader(() => active), targetLanguage: 'ko' },
    organization: { reader: orgRegistry.freshReader(() => active) }, identityClient, bindings, current: () => active }
  const remove = () => readingTransaction(['exchanges'], 'readwrite', (tx, done) => { tx.objectStore('exchanges').delete([readingNamespace('reader'), book.id]); done(undefined) })
  return { options, library, book, document, choice, result, glossaryScope, organizationScope, glossaryView, organizationView, orgApi, glossaryApi, identityClient, bindings, remove, expire: () => { active = false } }
}

describe('one guarded account ZIP download preparation', () => {
  it('combines 500 exact glossary entries and organization without dropping language scopes or siblings', async () => {
    const f = await setup()
    const entries: BookGlossaryEntry[] = Array.from({ length: 500 }, (_, i) => ({ id: `ID-${i}`, sourceTerm: ` Source  ${i} `,
      translatedTerm: ` 번역 ${i} `, displayTerm: i % 2 ? ` Alias ${i} ` : '', kind: i % 3 ? 'Character' : 'Place', enabled: i % 2 === 0, caseSensitive: i % 3 === 0 }))
    f.glossaryView.entries = entries
    const capture = vi.spyOn(f.choice, 'capture')
    const ready = await prepareAccountExchangeExport(f.options)
    await ready.checkBeforeSave(); ready.assertCurrent()
    const output = (await readExchange(ready.bytes)).documents[0]
    expect(readBookGlossarySnapshotsFromDocument(output)).toEqual({ version: 1, snapshots: [
      { ...f.glossaryScope, targetLanguage: 'ja', presence: 'present', entries: [] }, { ...f.glossaryScope, presence: 'present', entries },
    ] })
    expect(await readLibraryOrganizationSnapshotFromDocument(output)).toEqual({ version: 1, identity: f.result.identity, presence: 'present', organization: f.organizationView.organization })
    expect(output.glossary).toEqual(f.document.glossary); expect(output.organization).toEqual(f.document.organization)
    expect(output.extensions!.unknownSibling).toEqual(f.document.extensions!.unknownSibling)
    expect(output.paragraphs).toEqual(f.document.paragraphs)
    expect(capture).toHaveBeenCalledOnce(); expect(f.identityClient.verify).toHaveBeenCalledOnce()
    expect(f.orgApi.get).toHaveBeenCalledOnce(); expect(f.glossaryApi.get).toHaveBeenCalledOnce()
    expect(f.orgApi.put).not.toHaveBeenCalled(); expect(f.glossaryApi.put).not.toHaveBeenCalled()
    expect(await listLibraryOrganizationRecords('reader')).toEqual([]); expect(await listBookGlossaryRecords('reader')).toEqual([])
    for (const forbidden of ['recordId', 'username', 'origin', 'bindingNonce', 'expectedVersion', 'mutationId', 'updatedAt']) {
      expect(JSON.stringify([output.extensions!.bookGlossarySnapshots, output.extensions!.libraryOrganizationSnapshot])).not.toContain(`"${forbidden}":`)
    }
  })

  it.each(['absent', 'deleted', 'empty'] as const)('preserves %s metadata without account mutations', async mode => {
    const f = await setup()
    f.glossaryView.version = mode === 'absent' ? 0 : 4; f.glossaryView.entries = mode === 'empty' ? [] : null; f.glossaryView.updatedAt = mode === 'absent' ? null : time
    f.organizationView.version = mode === 'absent' ? 0 : 4; f.organizationView.organization = mode === 'absent' ? null : { folder: '', tags: [], favorite: false }; f.organizationView.updatedAt = mode === 'absent' ? null : time
    const ready = await prepareAccountExchangeExport(f.options), output = (await readExchange(ready.bytes)).documents[0]
    expect(readBookGlossarySnapshotsFromDocument(output)!.snapshots[1]).toEqual({ ...f.glossaryScope, presence: mode === 'empty' ? 'present' : mode, entries: mode === 'empty' ? [] : null })
    expect(await readLibraryOrganizationSnapshotFromDocument(output)).toMatchObject({ presence: mode === 'absent' ? 'absent' : 'present', organization: f.organizationView.organization })
    expect(f.orgApi.put).not.toHaveBeenCalled(); expect(f.glossaryApi.put).not.toHaveBeenCalled()
  })

  it('guards ordinary ZIPs using canonical captures without requiring or reading an account', async () => {
    const f = await setup()
    const ready = await prepareAccountExchangeExport({ ...f.options, glossary: undefined, organization: undefined, identityClient: null })
    expect((await readExchange(ready.bytes)).documents[0]).toEqual(f.document)
    expect(f.identityClient.verify).not.toHaveBeenCalled(); expect(f.orgApi.get).not.toHaveBeenCalled(); expect(f.glossaryApi.get).not.toHaveBeenCalled()
    await f.remove(); await expect(ready.checkBeforeSave()).rejects.toThrow()
    await expect(prepareAccountExchangeExport({ ...f.options, choices: [{ ...f.choice, capture: undefined }] })).rejects.toThrow(organizationExportErrors.source)
  })

  it.each(['encode', 'save'] as const)('rejects nonce ABA, source deletion and expired sessions at %s', async phase => {
    for (const kind of ['nonce', 'source', 'session']) {
      const f = await setup()
      const mutate = async () => {
        if (kind === 'nonce') { f.bindings.remove(f.book); await f.bindings.confirm(f.book, f.result) }
        if (kind === 'source') await f.remove()
        if (kind === 'session') f.expire()
      }
      if (phase === 'save') { const ready = await prepareAccountExchangeExport(f.options); await mutate(); await expect(ready.checkBeforeSave()).rejects.toThrow(); if (kind !== 'source') expect(() => ready.assertCurrent()).toThrow() }
      else await expect(prepareAccountExchangeExport({ ...f.options, encode: async value => { await mutate(); return writeExchange(value) } })).rejects.toThrow()
    }
  })

  it('detects pending journals and source deletion during final journal awaits', async () => {
    const f = await setup(), ready = await prepareAccountExchangeExport(f.options)
    const originalCheck = f.options.organization.reader.checkReady
    f.options.organization.reader.checkReady = async (...args) => { await originalCheck(...args); await f.remove() }
    await expect(ready.checkBeforeSave()).rejects.toThrow()
    const g = await setup(), prepared = await prepareAccountExchangeExport(g.options), store = createLibraryOrganizationStore('reader', g.organizationScope)
    try {
      await store.initialize({ folder: '', tags: [], favorite: false })
      await store.transact(row => ({ ...row, enabled: true, remote: g.organizationView, local: g.organizationView.organization!, pending: {
        expectedVersion: g.organizationView.version, mutationId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', organization: g.organizationView.organization!,
      } }))
    } finally { store.close() }
    await expect(prepared.checkBeforeSave()).rejects.toThrow(organizationExportErrors.pending)
    expect(g.orgApi.put).not.toHaveBeenCalled()
  })

  it('rejects unsupported existing snapshots, combined extension overflow, mismatched identity and abort', async () => {
    const f = await setup()
    f.identityClient.verify.mockImplementationOnce(async () => ({ ...f.result, recordId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' }))
    await expect(prepareAccountExchangeExport(f.options)).rejects.toThrow('서버의 확인 응답을 검증하지 못했습니다.')
    await expect(prepareAccountExchangeExport({ ...f.options, signal: AbortSignal.abort() })).rejects.toThrow(glossaryExportErrors.stale)
    // A production capture is trusted only for the canonical value; account codec still rejects unsafe augmentation.
    for (const mode of ['unsupported', 'overflow']) {
      const g = await setup(), value = await g.choice.capture!()
      if (mode === 'unsupported') value.value.documents[0].extensions!.libraryOrganizationSnapshot = { version: 2 }
      else {
        value.value.documents[0].extensions!.padding = 'x'.repeat(256 * 1024 - new TextEncoder().encode(JSON.stringify(value.value.documents[0].extensions)).length - 20)
        g.glossaryView.entries = [{ id: 'extra', sourceTerm: 's'.repeat(200), translatedTerm: 't'.repeat(200), displayTerm: '', kind: 'Term', enabled: true, caseSensitive: false }]
      }
      g.choice.capture = async () => value
      await expect(prepareAccountExchangeExport(g.options)).rejects.toThrow(mode === 'unsupported' ? organizationExportErrors.snapshot : glossaryExportErrors.size)
    }
  })

  it('maps account transport/storage errors without exposing arbitrary response text', () => {
    expect(accountExchangeExportErrorMessage(new BookGlossaryError('storage'))).toBe(accountExportErrors.journal)
    expect(accountExchangeExportErrorMessage(new LibraryOrganizationError('authentication'))).toBe(accountExportErrors.offline)
    expect(accountExchangeExportErrorMessage(new Error('secret arbitrary response'))).toBe(accountExportErrors.invalid)
    expect(accountExchangeExportErrorMessage(new Error(organizationExportErrors.pending))).toBe(organizationExportErrors.pending)
    for (const message of [
      '서버 연결 확인이 취소되었습니다.',
      '서버 연결이 변경되어 이전 확인 결과를 취소했습니다. 다시 확인해 주세요.',
      '서버 연결 정보를 확인하지 못했습니다. 원래 연결과 기기 기록은 보존됩니다.',
      '서버 연결을 확인하려면 다시 로그인해 주세요. 기기에서 읽기는 계속 사용할 수 있습니다.',
      '서버 원본을 확인하려면 다시 로그인해 주세요.',
      '서버 확인 권한을 확인해 주세요. 다시 로그인한 뒤 시도해 주세요.',
      '현재 계정에서 해당 서버 문서를 찾을 수 없습니다.',
      '서버 문서의 식별자·언어·revision·전체 문단이 가져온 책과 일치하지 않습니다.',
      '서버 문서의 원본 정보를 검증할 수 없습니다. 기존 책은 계속 읽을 수 있습니다.',
      '서버에 연결하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.',
      '서버 확인 시간이 초과되었습니다. 다시 시도해 주세요.',
      '서버에서 원본을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      '서버의 확인 응답을 검증하지 못했습니다.',
    ]) expect(accountExchangeExportErrorMessage(new Error(message))).toBe(message)
  })
})
