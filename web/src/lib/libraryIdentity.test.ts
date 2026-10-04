import { afterEach, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import translation from '../../../contracts/fixtures/library-identity-v1/translation.json'
import { inspectPortableIdentity, libraryParagraphHash, validateLibraryIdentity, originalLibraryIdentity } from './libraryIdentity'
import { validateExchangeDocument, readExchange, writeExchange } from './libraryExchange'
import { createExchangeLibrary, exchangeExportChoices, exchangeReadingDocument } from './exchangeLibrary'
import { createPersonalLibrary } from './personalLibrary'
import { createOfflineLibrary } from './offline'
import type { TranslationResponse } from './types'
import { sha256 } from './validation'

afterEach(() => vi.unstubAllGlobals())
describe('portable document identity proofs', () => {
  it('matches shared Kotlin fixtures including ordered UTF-8 frames and exact raw text', async () => {
    for (const fixture of [original, translation]) {
      const document = validateExchangeDocument(structuredClone(fixture))
      expect(await libraryParagraphHash(document.paragraphs)).toBe(fixture.extensions.documentIdentity.paragraphHash)
      expect(await inspectPortableIdentity(document)).toEqual({ status: 'ready', identity: fixture.extensions.documentIdentity, recordIdHint: fixture.extensions.serverRecordId })
    }
    expect(await libraryParagraphHash([{ paragraphId: 'a:b', text: 'c' }])).not.toBe(await libraryParagraphHash([{ paragraphId: 'a', text: 'b:c' }]))
  })
  it('rejects malformed identity, empty arrays, duplicate IDs and invalid Unicode without normalizing identity', async () => {
    const identity = original.extensions.documentIdentity
    expect(validateLibraryIdentity({ ...identity, bookId: ' book ' }).bookId).toBe(' book ')
    for (const changed of [{ ...identity, sourceRevision: 'source-v1' }, { ...identity, bookId: '\ud800' }, { ...identity, bookId: 'x\n' }, { ...identity, bookId: 'x'.repeat(2001) }, { ...identity, targetLanguage: 'ko' }, { ...identity, version: 2 }]) expect(() => validateLibraryIdentity(changed)).toThrow()
    for (const paragraphs of [[], [{ paragraphId: 'p', text: 'x' }, { paragraphId: 'p', text: 'y' }], [{ paragraphId: 'p', text: '\ud800' }], [{ paragraphId: ' '.repeat(2), text: 'x' }]]) await expect(libraryParagraphHash(paragraphs)).rejects.toThrow()
  })
  it('checks language, kind, all revision formulas and ordered text beyond a claimed paragraph hash', async () => {
    for (const fixture of [original, translation]) {
      const document = validateExchangeDocument(structuredClone(fixture))
      for (const changed of [{ ...document, language: 'EN' }, { ...document, kind: 'local' as const }, { ...document, paragraphs: [...document.paragraphs].reverse() }, { ...document, paragraphs: [{ ...document.paragraphs[0], text: 'Changed' }, document.paragraphs[1]] }]) expect(await inspectPortableIdentity(changed)).toEqual({ status: 'invalid' })
      const changed = structuredClone(document); changed.paragraphs[0].text = 'Changed with a forged matching content hash'
      changed.extensions!.documentIdentity = { ...fixture.extensions.documentIdentity, paragraphHash: await libraryParagraphHash(changed.paragraphs) }
      expect(await inspectPortableIdentity(changed)).toEqual({ status: 'invalid' })
    }
    const target = validateExchangeDocument(structuredClone(translation))
    target.extensions!.documentIdentity = { ...translation.extensions.documentIdentity, artifactId: 'a'.repeat(64) }
    expect(await inspectPortableIdentity(target)).toEqual({ status: 'invalid' })
  })
  it('keeps legacy and asset documents independent and treats UUID only as an optional passive hint', async () => {
    const document = validateExchangeDocument(structuredClone(original))
    expect(await inspectPortableIdentity({ ...document, extensions: { serverRecordId: original.extensions.serverRecordId } })).toEqual({ status: 'missing' })
    expect(await inspectPortableIdentity({ ...document, assets: [{ path: `assets/${'a'.repeat(64)}`, role: 'pdf' }] })).toEqual({ status: 'assets' })
    const alternate = { ...document, extensions: { documentIdentity: original.extensions.documentIdentity, server: { recordId: original.extensions.serverRecordId.toUpperCase() } } }
    expect(await inspectPortableIdentity(alternate)).toMatchObject({ status: 'ready', recordIdHint: original.extensions.serverRecordId })
    expect(await inspectPortableIdentity({ ...alternate, extensions: { ...alternate.extensions, serverRecordId: 'not-a-uuid' } })).toEqual({ status: 'ready', identity: original.extensions.documentIdentity })
    expect(await inspectPortableIdentity({ ...alternate, extensions: { ...alternate.extensions, serverRecordId: null } })).toEqual({ status: 'ready', identity: original.extensions.documentIdentity })
  })
  it('preserves proof and unknown metadata through import, note changes and ZIP re-export without binding', async () => {
    const options = { indexedDB: new IDBFactory(), dbName: 'identity-roundtrip' }, library = createExchangeLibrary('alice', options)
    const value = { createdAt: '2026-10-03T00:00:00Z', documents: [validateExchangeDocument(structuredClone(original))], assets: [] }
    await library.importPackage(value)
    const saved = (await library.list())[0], reading = exchangeReadingDocument(saved)
    expect(reading.serverProgress).toBeUndefined(); expect(reading.glossaryIdentity).toBeUndefined()
    const exported = await readExchange(await writeExchange(await library.exportDocument(saved)))
    expect(exported.documents[0].extensions).toEqual(original.extensions)
    expect(await inspectPortableIdentity(exported.documents[0])).toMatchObject({ status: 'ready' })
    expect(await createExchangeLibrary('bob', options).list()).toEqual([])
    const tampered = structuredClone(value); tampered.documents[0].extensions!.documentIdentity = { ...original.extensions.documentIdentity, paragraphHash: 'a'.repeat(64) }
    await library.importPackage(tampered)
    expect((await library.list()).some(row => row.document.extensions?.documentIdentity && JSON.stringify(row.document.extensions.documentIdentity).includes('a'.repeat(64)))).toBe(true)
  })
  it('adds a common proof to saved server originals and translations while preserving legacy export fallback', async () => {
    vi.stubGlobal('indexedDB', new IDBFactory())
    const identity = original.extensions.documentIdentity
    const chapter = { recordId: original.extensions.serverRecordId, providerId: identity.contentProviderId, bookId: identity.bookId, chapterId: identity.chapterId, bookTitle: original.bookTitle, chapterTitle: original.chapterTitle, bookUrl: '', chapterUrl: '', sourceLanguage: identity.sourceLanguage, sourceRevision: identity.sourceRevision, paragraphs: original.paragraphs.map((p, ordinal) => ({ ...p, ordinal })), createdAt: '2026-10-03T00:00:00Z' }
    const personal = createPersonalLibrary('alice'), offline = createOfflineLibrary('alice')
    try {
      await expect(originalLibraryIdentity({ ...chapter, paragraphs: chapter.paragraphs.map(p => ({ ...p, ordinal: p.ordinal + 1 })) })).rejects.toThrow()
      await personal.saveOriginal(chapter)
      const { version: _, kind: __, paragraphHash: ___, ...metadata } = translation.extensions.documentIdentity
      await offline.save({ ...metadata, recordId: translation.extensions.serverRecordId, bookTitle: translation.bookTitle, chapterTitle: translation.chapterTitle, paragraphs: translation.paragraphs, createdAt: '2026-10-03T00:00:00Z', created: false } as TranslationResponse)
      await personal.saveOriginal({ ...chapter, recordId: '00000000-0000-4000-8000-000000000002', bookId: 'L'.repeat(2001) })
      const choices = await exchangeExportChoices('alice')
      for (const [key, fixture] of [[`original:${chapter.recordId}`, original], [`translation:${translation.extensions.serverRecordId}`, translation]] as const) {
        const portable = (await choices.find(choice => choice.key === key)!.load()).documents[0]
        expect(portable.extensions?.documentIdentity).toEqual(fixture.extensions.documentIdentity)
        expect(portable.extensions?.serverRecordId).toBe(fixture.extensions.serverRecordId)
        expect(await inspectPortableIdentity(portable)).toMatchObject({ status: 'ready' })
      }
      const fallback = (await choices.find(choice => choice.key.endsWith('000000000002'))!.load()).documents[0]
      expect(fallback.extensions?.documentIdentity).toBeUndefined(); expect(fallback.extensions?.source).toMatchObject({ bookId: 'L'.repeat(2001) })
    } finally { personal.close(); offline.close() }
  })
  it('uses Kotlin trim in translation artifact checks without trimming stored identity or text', async () => {
    const document = validateExchangeDocument(structuredClone(translation)), identity = { ...translation.extensions.documentIdentity, contentProviderId: ' source:edge ', bookId: '\ufeffbook|原🌏' }
    identity.artifactId = await sha256(['source:edge:\ufeffbook|原🌏:chapter:one', identity.sourceRevision, identity.sourceLanguage, identity.targetLanguage, identity.translationProviderId, identity.modelId, identity.promptRevision, identity.glossaryRevision].join('|'))
    identity.revision = await sha256(`${identity.artifactId}|${identity.payloadHash}`); document.extensions!.documentIdentity = identity
    expect(await inspectPortableIdentity(document)).toMatchObject({ status: 'ready', identity })
  })
})
