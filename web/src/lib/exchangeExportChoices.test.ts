import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import original from '../../../contracts/fixtures/library-identity-v1/original.json'
import translation from '../../../contracts/fixtures/library-identity-v1/translation.json'
import { createExchangeLibrary, exchangeExportChoices } from './exchangeLibrary'
import { createLocalDocuments, parseLocalDocument } from './localDocuments'
import { createPersonalLibrary } from './personalLibrary'
import { createOfflineLibrary } from './offline'
import { createReadingNotes } from './readingNotes'
import { readingTransaction } from './deviceReadingDatabase'
import { validateExchangeDocument } from './libraryExchange'
import type { TranslationResponse } from './types'

beforeEach(() => { vi.stubGlobal('indexedDB', new IDBFactory()) })
afterEach(() => vi.unstubAllGlobals())
const username = 'canonical-reader'
const file = (value: string) => {
  const bytes = new TextEncoder().encode(value)
  return { name: 'Canonical.txt', size: bytes.length, arrayBuffer: async () => bytes.slice().buffer }
}
function chapter() {
  const identity = original.extensions.documentIdentity
  return { recordId: original.extensions.serverRecordId, providerId: identity.contentProviderId, bookId: identity.bookId,
    chapterId: identity.chapterId, bookTitle: original.bookTitle, chapterTitle: original.chapterTitle, bookUrl: '', chapterUrl: '',
    sourceLanguage: identity.sourceLanguage, sourceRevision: identity.sourceRevision,
    paragraphs: original.paragraphs.map((paragraph, ordinal) => ({ ...paragraph, ordinal })), createdAt: '2026-10-03T00:00:00Z' }
}
function translated(): TranslationResponse {
  const { version: _, kind: __, paragraphHash: ___, ...metadata } = translation.extensions.documentIdentity
  return { ...metadata, recordId: translation.extensions.serverRecordId, bookTitle: translation.bookTitle,
    chapterTitle: translation.chapterTitle, paragraphs: translation.paragraphs, createdAt: '2026-10-03T00:00:00Z', created: false } as TranslationResponse
}

describe('canonical export choice captures', () => {
  it('reloads current local classification and rejects removed files', async () => {
    const library = createLocalDocuments(username), document = await parseLocalDocument(file('First paragraph\n\nSecond paragraph'))
    await library.save(document)
    const choice = (await exchangeExportChoices(username)).find(value => value.key === `local:${document.id}`)!
    await library.organize(document.id, { folder: 'Latest folder', tags: ['Ordered', '태그'], favorite: true })
    expect((await choice.load()).documents[0].organization.folder).toBe('Latest folder')
    const captured = await choice.capture!()
    expect(captured.value.documents[0].paragraphs).toEqual(document.paragraphs)
    await captured.assertCurrent()
    await library.remove(document.id)
    await expect(choice.load()).rejects.toThrow()
    await expect(captured.assertCurrent()).rejects.toThrow()
  })

  it('includes latest notes but refuses to certify them after they change', async () => {
    const library = createLocalDocuments(username), document = await parseLocalDocument(file('First paragraph\n\nSecond paragraph'))
    await library.save(document)
    const choice = (await exchangeExportChoices(username))[0], notes = createReadingNotes(username)
    const note = await notes.add(document, { kind: 'note', title: 'Latest', text: 'Exact note', anchor: { paragraphId: document.paragraphs[0].paragraphId, characterOffset: 0 } })
    const captured = await choice.capture!()
    expect(captured.value.documents[0].notes[0].text).toBe('Exact note')
    captured.value.documents[0].notes[0].text = 'Caller mutation'
    await captured.assertCurrent()
    await notes.update(document, note.id, { title: 'Updated', text: 'Changed while server GET was pending' }, note)
    await expect(captured.assertCurrent()).rejects.toThrow()
    expect((await choice.load()).documents[0].notes[0].text).toBe('Changed while server GET was pending')
  })

  it('captures current portable rows rather than the inventory book hint and catches deletion or replacement', async () => {
    const library = createExchangeLibrary(username)
    await library.importPackage({ createdAt: '2026-10-03T00:00:00Z', documents: [validateExchangeDocument(structuredClone(original))], assets: [] })
    const choice = (await exchangeExportChoices(username))[0]
    if (choice.glossarySource?.kind !== 'portable') throw new Error('Expected portable source')
    choice.glossarySource.book.document.paragraphs[0].text = 'Forged inventory body'
    const captured = await choice.capture!()
    expect(captured.portableBook!.document.paragraphs).toEqual(original.paragraphs)
    expect(captured.value.documents[0].paragraphs).toEqual(original.paragraphs)
    captured.portableBook!.document.paragraphs[0].text = 'Caller changes isolated copy'
    await captured.assertCurrent()
    await readingTransaction<void>(['exchanges'], 'readwrite', tx => {
      const store = tx.objectStore('exchanges'), request = store.get([username, choice.key])
      request.onsuccess = () => store.put({ ...request.result, importedAt: '2026-10-20T00:00:00Z' })
    })
    await expect(captured.assertCurrent()).rejects.toThrow()
    const replacement = await choice.capture!()
    await readingTransaction<void>(['exchanges'], 'readwrite', tx => { tx.objectStore('exchanges').delete([username, choice.key]) })
    await expect(replacement.assertCurrent()).rejects.toThrow()
    await expect(choice.load()).rejects.toThrow()
  })

  it('rereads server-origin original and translation stores and rejects deletions after inventory', async () => {
    const personal = createPersonalLibrary(username), offline = createOfflineLibrary(username)
    try {
      await personal.saveOriginal(chapter()); await offline.save(translated())
      const choices = await exchangeExportChoices(username)
      for (const [key, expected] of [[`original:${chapter().recordId}`, original], [`translation:${translated().recordId}`, translation]] as const) {
        const choice = choices.find(value => value.key === key)!, captured = await choice.capture!()
        expect(captured.value.documents[0].paragraphs).toEqual(expected.paragraphs)
        await captured.assertCurrent()
        const syncRows = await readingTransaction<number>(['noteSync', 'noteSyncDocuments'], 'readonly', (tx, done) => {
          const requests = [tx.objectStore('noteSync').getAll(), tx.objectStore('noteSyncDocuments').getAll()]
          let remaining = requests.length, rows = 0
          requests.forEach(request => { request.onsuccess = () => { rows += request.result.length; if (!--remaining) done(rows) } })
        })
        expect(syncRows).toBe(0)
        if (key.startsWith('original:')) await personal.removeOriginal(chapter().recordId)
        else await offline.remove(translated().recordId)
        await expect(captured.assertCurrent()).rejects.toThrow()
        await expect(choice.load()).rejects.toThrow()
      }
      expect(await exchangeExportChoices('another-reader')).toEqual([])
    } finally { personal.close(); offline.close() }
  })
})
