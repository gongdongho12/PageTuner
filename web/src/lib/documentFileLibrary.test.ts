import { webcrypto } from 'node:crypto'
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import translation from '../../../contracts/fixtures/translation-v1/stored-response.json'
import { createLocalDocuments, parseLocalDocument } from './localDocuments'
import { createExchangeLibrary, exchangeExportChoices } from './exchangeLibrary'
import * as exchange from './exchangeLibrary'
import { createOfflineLibrary } from './offline'
import { createPersonalLibrary } from './personalLibrary'
import { createReadingNotes } from './readingNotes'
import { documentFileChoices } from './documentFileLibrary'
import { prepareDocumentFileExport } from './documentFileExport'

beforeEach(() => { vi.stubGlobal('crypto', webcrypto); vi.stubGlobal('indexedDB', new IDBFactory()) })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

it('lists complete local, original, translated and ZIP bodies without combining account namespaces or exporting notes', async () => {
  const bytes = new TextEncoder().encode('First 🌏 paragraph\n\nLast paragraph')
  const local = await parseLocalDocument({ name: 'Offline.txt', size: bytes.length, arrayBuffer: async () => Uint8Array.from(bytes).buffer })
  await createLocalDocuments('guest').save(local)
  await createReadingNotes('guest').add(local, { kind: 'note', title: 'PRIVATE NOTE', text: 'secret-not-in-prose', anchor: { paragraphId: local.paragraphs[0].paragraphId, characterOffset: 0 } })
  const archive = await (await exchangeExportChoices('guest'))[0].load()
  await createExchangeLibrary('guest').importPackage(archive)
  const originals = createPersonalLibrary('guest'), translations = createOfflineLibrary('guest')
  try {
    await originals.saveOriginal({ recordId: '00000000-0000-0000-0000-000000000001', providerId: 'source', bookId: 'book', bookTitle: 'Book', bookUrl: 'https://example.com/book', chapterId: 'c1', chapterTitle: 'Chapter 1', chapterUrl: 'https://example.com/book/1', sourceLanguage: 'en', sourceRevision: '293dd3dd2fd29d0eb46d4498e49b95fc27ed77f1b1e2c51cdea1a6fc46c55589', paragraphs: [{ paragraphId: 'p1', ordinal: 0, text: 'First paragraph.' }, { paragraphId: 'p2', ordinal: 1, text: 'Second paragraph.' }], createdAt: '2026-09-14T00:00:00Z' })
    await translations.save(translation)
  } finally { originals.close(); translations.close() }

  expect(await documentFileChoices('registered-account')).toEqual({ choices: [], damaged: 0, unavailable: [] })
  const result = await documentFileChoices('guest')
  expect(result.damaged).toBe(0)
  expect(result.choices.map(choice => choice.label)).toEqual(['로컬 파일', '원문 보관', '번역 보관', '가져온 책'])
  const outputs = await Promise.all(result.choices.map(async choice => (await prepareDocumentFileExport(choice.document, 'txt')).blob.text()))
  expect(outputs[0]).toBe(outputs[3])
  expect(outputs[0]).toContain('First 🌏 paragraph\n\nLast paragraph')
  expect(outputs[1]).toContain('First paragraph.\n\nSecond paragraph.')
  expect(outputs[2]).toContain(translation.paragraphs.at(-1)!.text)
  for (const output of outputs) { expect(output).not.toContain('secret-not-in-prose'); expect(output).not.toContain('PRIVATE NOTE') }
})

it('keeps healthy local bodies available when the ZIP store cannot be read and reports that failure', async () => {
  const bytes = new TextEncoder().encode('Healthy text')
  const local = await parseLocalDocument({ name: 'Healthy.txt', size: bytes.length, arrayBuffer: async () => Uint8Array.from(bytes).buffer })
  await createLocalDocuments('guest').save(local)
  const store = createExchangeLibrary('guest')
  vi.spyOn(exchange, 'createExchangeLibrary').mockReturnValue({ ...store, list: async () => { throw new Error('Corrupt ZIP') } })
  const result = await documentFileChoices('guest')
  expect(result.choices.map(choice => choice.document)).toEqual([local])
  expect(result.unavailable).toEqual(['가져온 책'])
  expect(result.damaged).toBe(0)
})
