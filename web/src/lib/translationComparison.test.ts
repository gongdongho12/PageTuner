import { webcrypto } from 'node:crypto'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import stored from '../../../contracts/fixtures/translation-v1/stored-response.json'
import type { TranslationResponse } from './api'
import type { StoredChapter } from './workflowApi'
import { sha256 } from './validation'
import { createReadingNotes } from './readingNotes'
import { createPersonalLibrary } from './personalLibrary'
import { comparisonParagraphId, comparisonSwitchAnchor, findTranslationSource, matchTranslationSource, pairedComparisonDocument } from './translationComparison'

beforeAll(() => vi.stubGlobal('crypto', webcrypto))
async function fixture() {
  const paragraphs = [{ paragraphId: 'p1', ordinal: 0, text: 'Hello 🌏. A very long original paragraph.' }, { paragraphId: 'original:p2', ordinal: 1, text: 'Another original paragraph.' }]
  const sourceRevision = await sha256((await Promise.all(paragraphs.map(async p => `${p.paragraphId}:${p.ordinal}:${await sha256(p.text)}`))).join('\n'))
  const source: StoredChapter = { recordId: '00000000-0000-0000-0000-000000000001', providerId: 'fixture', bookId: 'book', chapterId: 'chapter', bookTitle: 'Book', chapterTitle: 'Chapter',
    bookUrl: 'https://example.com/book', chapterUrl: 'https://example.com/book/chapter', sourceLanguage: 'en', sourceRevision, paragraphs, createdAt: '2026-09-15T00:00:00Z' }
  const translation: TranslationResponse = { ...stored, contentProviderId: source.providerId, bookId: source.bookId, chapterId: source.chapterId, sourceRevision,
    paragraphs: [{ paragraphId: 'p1', text: '안녕 🌏.' }, { paragraphId: 'original:p2', text: '다른 문단.' }] }
  translation.payloadHash = await sha256(translation.paragraphs.map(p => `${p.paragraphId}:${p.text}`).join('\n'))
  translation.artifactId = await sha256([`${source.providerId}:${source.bookId}:${source.chapterId}`, sourceRevision, translation.sourceLanguage, translation.targetLanguage,
    translation.translationProviderId, translation.modelId, translation.promptRevision, translation.glossaryRevision].join('|'))
  translation.revision = await sha256(`${translation.artifactId}|${translation.payloadHash}`)
  return { source, translation }
}

describe('verified original and translation comparison', () => {
  it('matches exact immutable source and ordered paragraph identities, rejecting changed source or translation text', async () => {
    const { source, translation } = await fixture(), match = await matchTranslationSource(translation, source)
    expect(match.original.id).toBe(`original:${source.recordId}:${source.sourceRevision}`)
    expect(match.translated.id).toBe(translation.recordId)
    expect(match.original.paragraphs.map(p => p.text)).toEqual(source.paragraphs.map(p => p.text))
    for (const changed of [{ ...source, chapterId: 'other' }, { ...source, providerId: 'other' }, { ...source, bookId: 'other' },
      { ...source, sourceRevision: 'b'.repeat(64) }, { ...source, paragraphs: [{ ...source.paragraphs[0], text: 'tampered' }, source.paragraphs[1]] }]) {
      await expect(matchTranslationSource(translation, changed)).rejects.toThrow()
    }
    await expect(matchTranslationSource({ ...translation, paragraphs: [...translation.paragraphs].reverse() }, source)).rejects.toThrow()
    for (const paragraphs of [[...translation.paragraphs].reverse(), translation.paragraphs.slice(0, 1)]) {
      const payloadHash = await sha256(paragraphs.map(p => `${p.paragraphId}:${p.text}`).join('\n'))
      const validArtifact = { ...translation, paragraphs, payloadHash, revision: await sha256(`${translation.artifactId}|${payloadHash}`) }
      await expect(matchTranslationSource(validArtifact, source)).rejects.toThrow('대응하는 원문')
    }
  })

  it('interleaves matching paragraphs without changing originals, notes or canonical document identities', async () => {
    const { source, translation } = await fixture(), match = await matchTranslationSource(translation, source), before = JSON.stringify({ source, translation })
    const notes = createReadingNotes('comparison-reader', { indexedDB: new IDBFactory() })
    const originalNote = await notes.add(match.original, { kind: 'highlight', title: 'World', anchor: { paragraphId: 'p1', characterOffset: 6 },
      range: { start: { paragraphId: 'p1', characterOffset: 6 }, end: { paragraphId: 'p1', characterOffset: 8 } } })
    const translationNote = await notes.add(match.translated, { kind: 'bookmark', title: 'Translated bookmark', anchor: { paragraphId: 'p1', characterOffset: 3 } })
    const paired = pairedComparisonDocument(match, { original: 'Original', translation: 'Translation' })
    expect(paired.paragraphs.map(p => p.text)).toEqual(source.paragraphs.flatMap((p, i) => [`Original · ${i + 1}\n${p.text}`, `Translation · ${i + 1}\n${translation.paragraphs[i].text}`]))
    expect((await notes.list(paired)).items).toEqual([])
    expect((await notes.list(match.original)).items[0]).toEqual(originalNote)
    expect((await notes.list(match.translated)).items[0]).toEqual(translationNote)
    expect(JSON.stringify({ source, translation })).toBe(before)
    expect(comparisonParagraphId('comparison', { paragraphId: 'original:original:p2', characterOffset: 5 })).toBe('original:p2')
  })

  it('switches at matching paragraphs and never treats an original character offset as a translated offset', async () => {
    const { source, translation } = await fixture(), match = await matchTranslationSource(translation, source)
    const translatedAnchor = { paragraphId: 'p1', characterOffset: 3 }
    expect(comparisonSwitchAnchor(match, 'translation', 'p1', translatedAnchor)).toEqual(translatedAnchor)
    expect(comparisonSwitchAnchor(match, 'translation', 'p1', { paragraphId: 'p1', characterOffset: 20 })).toEqual({ paragraphId: 'p1', characterOffset: 0 })
    expect(comparisonSwitchAnchor(match, 'original', 'p1', { paragraphId: 'p1', characterOffset: 7 })).toEqual({ paragraphId: 'p1', characterOffset: 0 }) // Inside the emoji.
    expect(comparisonSwitchAnchor(match, 'original', 'original:p2', translatedAnchor)).toEqual({ paragraphId: 'original:p2', characterOffset: 0 })
    expect(comparisonSwitchAnchor(match, 'comparison', 'original:p2')).toEqual({ paragraphId: 'original:original:p2', characterOffset: 0 })
    expect(comparisonSwitchAnchor(match, 'translation', 'missing')).toBeUndefined()
  })

  it('uses only the current account saved source offline and does not make network requests on cache hits', async () => {
    const { source, translation } = await fixture(), indexedDB = new IDBFactory(), personal = createPersonalLibrary('alice', { indexedDB }), other = createPersonalLibrary('bob', { indexedDB })
    await personal.saveOriginal(source)
    const client = { chapter: vi.fn(), chapters: vi.fn() }, signal = new AbortController().signal
    expect((await findTranslationSource(translation, { personal, client, signal }))?.saved).toBe(true)
    expect(client.chapter).not.toHaveBeenCalled(); expect(client.chapters).not.toHaveBeenCalled()
    expect(await findTranslationSource(translation, { personal: other, client: null, signal })).toBeUndefined()
    personal.close(); other.close()
  })

  it('fetches the workflow source directly, falls back from unavailable storage, and checks the returned revision', async () => {
    const { source, translation } = await fixture(), personal = { originals: vi.fn(async () => { throw new Error('storage denied') }) }
    const client = { chapter: vi.fn(async () => source), chapters: vi.fn() }, signal = new AbortController().signal
    expect((await findTranslationSource(translation, { personal, client, sourceRecordId: source.recordId, signal }))?.saved).toBe(false)
    expect(client.chapter).toHaveBeenCalledWith(source.recordId, signal); expect(client.chapters).not.toHaveBeenCalled()
    client.chapter.mockResolvedValueOnce({ ...source, sourceRevision: 'changed' })
    await expect(findTranslationSource(translation, { client, sourceRecordId: source.recordId, signal })).rejects.toThrow()
  })

  it('finds a server library match after the first page and stops cleanly on cancellation or absence', async () => {
    const { source, translation } = await fixture(), client = { chapter: vi.fn(async () => source), chapters: vi.fn(async (page = 0) => ({
      items: page === 1 ? [{ ...source, paragraphCount: source.paragraphs.length }] : [], page, size: 12, totalItems: 13, totalPages: 2, hasNext: page === 0,
    })) }, signal = new AbortController().signal
    expect((await findTranslationSource(translation, { client, signal }))?.match.source.recordId).toBe(source.recordId)
    expect(client.chapters).toHaveBeenCalledTimes(2); expect(client.chapter).toHaveBeenCalledTimes(1)
    const controller = new AbortController(); controller.abort()
    await expect(findTranslationSource(translation, { client, signal: controller.signal })).rejects.toThrow()
    expect(client.chapters).toHaveBeenCalledTimes(2)
    client.chapters.mockImplementation(async (page = 0) => ({ items: [], page, size: 12, totalItems: 0, totalPages: 0, hasNext: false }))
    expect(await findTranslationSource(translation, { client, signal })).toBeUndefined()
  })
})
