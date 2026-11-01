import { afterEach, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import translation from '../../../contracts/fixtures/library-identity-v1/translation.json'
import envelope from '../../../contracts/fixtures/account-glossary-snapshot-v1/snapshots.json'
import documentFixture from '../../../contracts/fixtures/account-glossary-snapshot-v1/document.json'
import { validateAccountGlossarySnapshots, accountGlossarySnapshot, mergeAccountGlossarySnapshot } from './accountGlossarySnapshot'
import { validateLibraryIdentity } from './libraryIdentity'
import { validateExchangeDocument, writeExchange, readExchange, type ExchangePackage } from './libraryExchange'
import { createExchangeLibrary, exchangeExportChoices, exportLatestAccountGlossaries } from './exchangeLibrary'
import { listBookGlossaryRecords } from './bookGlossaryStore'
import { createOfflineLibrary } from './offline'
import { createReadingNotes } from './readingNotes'
import { createReadingNoteStore } from './readingNoteStore'
import { translationReadingDocument } from './translationReading'
import type { TranslationResponse } from './types'
import { BookGlossaryError, type BookGlossaryView, type BookGlossaryScope } from './bookGlossaryApi'
const identity = validateLibraryIdentity(original.extensions.documentIdentity)
const scope = { providerId: identity.contentProviderId, bookId: identity.bookId, targetLanguage: 'ko' }
const view = (entries: BookGlossaryView['entries'], version = 1): BookGlossaryView => ({ ...scope, version, entries, updatedAt: version ? '2026-10-03T01:00:00Z' : null })
const portable = (document = documentFixture): ExchangePackage => ({ createdAt: '2026-10-03T01:00:00Z', documents: [validateExchangeDocument(structuredClone(document))], assets: [] })
const clients = () => ({ identityClient: { verify: vi.fn().mockResolvedValue({ verified: true, recordId: original.extensions.serverRecordId, kind: identity.kind, identity }), close() {} },
  glossaryClient: { get: vi.fn().mockResolvedValue(view(validateAccountGlossarySnapshots(envelope, identity).snapshots[0].entries)), put: vi.fn(), close() {} }, originalTargetLanguage: 'ko' })
afterEach(() => vi.unstubAllGlobals())
describe('passive full account glossary snapshots', () => {
  it('matches shared ordered original ID/alias/language/full entry fixtures and distinguishes empty/absent/deleted', () => {
    expect(validateAccountGlossarySnapshots(envelope, identity)).toEqual(envelope)
    expect(accountGlossarySnapshot(view(null, 0), identity, scope)).toMatchObject({ state: 'ABSENT', entries: null })
    expect(accountGlossarySnapshot(view(null), identity, scope)).toMatchObject({ state: 'DELETED', entries: null })
    expect(accountGlossarySnapshot(view([]), identity, scope)).toMatchObject({ state: 'PRESENT', entries: [] })
  })
  it('rejects unsupported version, duplicates, credentials/CAS extras, invalid states/scopes/entry IDs and 500/16 bounds', () => {
    const changes: ((v: typeof envelope) => void)[] = [
      v => { v.version = 2 }, v => { v.snapshots.push(v.snapshots[0]) }, v => { v.snapshots[0].state = 'ABSENT' },
      v => { v.snapshots[0].providerId = 'wrong' }, v => { v.snapshots[0].entries![0].id = ' ' },
      v => { v.snapshots[0].entries!.push(v.snapshots[0].entries![0]) },
      v => { v.snapshots[0].entries = Array.from({ length: 501 }, (_, i) => ({ ...v.snapshots[0].entries![0], id: String(i) })) },
      v => { v.snapshots = Array.from({ length: 17 }, (_, i) => ({ ...v.snapshots[0], targetLanguage: `ko-${i}` })) },
    ]
    for (const mutate of changes) { const v = structuredClone(envelope); mutate(v); expect(() => validateAccountGlossarySnapshots(v, identity)).toThrow() }
    expect(() => validateAccountGlossarySnapshots({ ...envelope, expectedVersion: 3 }, identity)).toThrow()
    expect(() => validateAccountGlossarySnapshots({ version: 1, snapshots: [{ ...envelope.snapshots[0], apiKey: 'secret' }] }, identity)).toThrow()
    expect(() => validateAccountGlossarySnapshots(envelope, validateLibraryIdentity(translation.extensions.documentIdentity))).toThrow()
  })
  it('updates only requested scope in place, preserves other scopes/passive metadata, and fails on merged extension overflow', () => {
    const extension = { vendorPassive: { keep: 'value' }, accountGlossarySnapshots: envelope }
    const next = accountGlossarySnapshot(view([]), identity, scope), result = mergeAccountGlossarySnapshot(extension, next, identity)
    expect(result.vendorPassive).toEqual(extension.vendorPassive)
    expect((result.accountGlossarySnapshots as typeof envelope).snapshots.map(s => s.targetLanguage)).toEqual(envelope.snapshots.map(s => s.targetLanguage))
    expect((result.accountGlossarySnapshots as typeof envelope).snapshots.slice(1)).toEqual(envelope.snapshots.slice(1))
    expect(() => mergeAccountGlossarySnapshot({ vendorPassive: 'x'.repeat(256 * 1024) }, next, identity)).toThrow()
    expect(() => mergeAccountGlossarySnapshot({ accountGlossarySnapshots: { version: 2, snapshots: [] } }, next, identity)).toThrow()
  })
  it('actual export verifies provenance, fetches selected live scope, omits mutation metadata, and keeps source/local records intact through import/reexport', async () => {
    const originalPackage = portable(), api = clients(), result = await exportLatestAccountGlossaries(originalPackage, api)
    expect(api.identityClient.verify).toHaveBeenCalledWith(original.extensions.serverRecordId, identity, undefined)
    expect(api.glossaryClient.get).toHaveBeenCalledWith(scope, undefined); expect(api.glossaryClient.put).not.toHaveBeenCalled()
    expect(result.documents[0].glossary).toEqual(originalPackage.documents[0].glossary)
    expect(result.documents[0].extensions?.vendorPassive).toEqual(originalPackage.documents[0].extensions?.vendorPassive)
    expect(result.documents[0].extensions?.documentIdentity).toEqual(originalPackage.documents[0].extensions?.documentIdentity)
    expect(originalPackage).toEqual(portable())
    const wire = await readExchange(await writeExchange(result)), db = new IDBFactory(); vi.stubGlobal('indexedDB', db)
    const library = createExchangeLibrary('alice'), imported = await library.importPackage(wire), saved = (await library.list())[0]
    expect(imported.added).toBe(1)
    expect((await readExchange(await writeExchange(await library.exportDocument(saved)))).documents[0].extensions).toEqual(wire.documents[0].extensions)
    expect(await listBookGlossaryRecords('alice')).toEqual([]); expect(await listBookGlossaryRecords('bob')).toEqual([])
    expect((await createExchangeLibrary('bob').list())).toEqual([])
    const serialized = JSON.stringify(result.documents[0].extensions?.accountGlossarySnapshots)
    for (const key of ['expectedVersion', 'mutationId', 'updatedAt', 'pending', 'remote', 'conflict', 'username', 'authorization']) expect(serialized).not.toContain(`"${key}"`)
  })
  it('integrates through real saved-server export choices without creating note mutations or overwriting local positions', async () => {
    vi.stubGlobal('indexedDB', new IDBFactory())
    const { version: _, kind: __, paragraphHash: ___, ...metadata } = translation.extensions.documentIdentity
    const value = { ...metadata, recordId: translation.extensions.serverRecordId, bookTitle: translation.bookTitle, chapterTitle: translation.chapterTitle,
      paragraphs: translation.paragraphs, createdAt: '2026-10-03T00:00:00Z', created: false } as TranslationResponse
    const offline = createOfflineLibrary('alice'), reading = translationReadingDocument(value), notes = createReadingNotes('alice')
    try {
      await offline.save(value)
      const anchor = { paragraphId: reading.paragraphs[0].paragraphId, characterOffset: reading.paragraphs[0].text.length }
      await notes.setPosition(reading, anchor)
      await notes.add({ ...reading, serverProgress: undefined }, { kind: 'note', title: 'local untouched', text: 'device copy', anchor })
      const choices = await exchangeExportChoices('alice'), loaded = await choices.find(c => c.key === `translation:${value.recordId}`)!.load(), api = clients()
      api.glossaryClient.get.mockImplementation(async (requested: BookGlossaryScope) => ({ ...requested, version: 1, entries: [], updatedAt: '2026-10-03T00:00:00Z' }))
      const zip = await readExchange(await writeExchange(await exportLatestAccountGlossaries(loaded, api)))
      expect(zip.documents[0].position).toEqual(anchor); expect(zip.documents[0].notes[0].text).toBe('device copy')
      expect(await createReadingNoteStore('alice').snapshot(reading.serverProgress!)).toEqual({ document: null, rows: [] })
      expect(await notes.getPosition(reading)).toEqual(anchor); expect(api.glossaryClient.put).not.toHaveBeenCalled()
    } finally { offline.close() }
  })
  it('fails closed for network/unsupported provenance and aborts a delayed response without producing a download package', async () => {
    const api = clients(); api.glossaryClient.get.mockRejectedValue(new BookGlossaryError('network'))
    await expect(exportLatestAccountGlossaries(portable(), api)).rejects.toThrow('서버의 최신 계정 용어집')
    const missing = portable(); delete missing.documents[0].extensions!.documentIdentity
    await expect(exportLatestAccountGlossaries(missing, clients())).rejects.toThrow('서버 원본 확인 정보')
    const delayed = clients(), controller = new AbortController(); let finish!: (value: BookGlossaryView) => void
    delayed.glossaryClient.get.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const pending = exportLatestAccountGlossaries(portable(), { ...delayed, signal: controller.signal })
    for (let i = 0; i < 30 && !finish; i++) await new Promise(resolve => setTimeout(resolve, 1))
    controller.abort(); finish(view([])); await expect(pending).rejects.toThrow('취소')
  })
  it('uses actual translation target scope rather than the selected original target language', async () => {
    const document = validateExchangeDocument(structuredClone(translation)), api = clients(), target = translation.extensions.documentIdentity.targetLanguage
    api.glossaryClient.get.mockImplementation(async (requested: BookGlossaryScope) => ({ ...requested, version: 0, entries: null, updatedAt: null }))
    const result = await exportLatestAccountGlossaries({ createdAt: '2026-10-03T01:00:00Z', documents: [document], assets: [] }, { ...api, originalTargetLanguage: 'ja' })
    expect(api.glossaryClient.get.mock.calls[0][0].targetLanguage).toBe(target)
    expect(result.documents[0].extensions?.accountGlossarySnapshots).toMatchObject({ snapshots: [{ targetLanguage: target, state: 'ABSENT', entries: null }] })
  })
})
