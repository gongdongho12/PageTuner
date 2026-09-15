import type { TranslationResponse } from './api'
import type { ChapterSummary, StoredChapter, WorkflowClient } from './workflowApi'
import { validateOriginal, type PersonalLibrary } from './personalLibrary'
import { validateTranslation } from './validation'
import type { ReadingAnchor } from './offline'
import type { ReadingDocument } from './readingDocument'
import { translationReadingDocument } from './translationReading'

export type ComparisonMode = 'translation' | 'original' | 'comparison'
export type MatchedComparison = { translation: TranslationResponse; source: StoredChapter; original: ReadingDocument; translated: ReadingDocument }
const mismatch = () => new Error('이 번역에 대응하는 원문을 확인하지 못했습니다. 다른 원문과 대조하지 않습니다.')

export function isComparisonIdentity(source: Pick<ChapterSummary, 'providerId' | 'bookId' | 'chapterId' | 'sourceRevision'>, translation: TranslationResponse) {
  return source.providerId === translation.contentProviderId && source.bookId === translation.bookId &&
    source.chapterId === translation.chapterId && source.sourceRevision === translation.sourceRevision
}

/** Both payloads are verified before any source text is exposed as a matching translation. */
export async function matchTranslationSource(rawTranslation: TranslationResponse, rawSource: StoredChapter): Promise<MatchedComparison> {
  const [translation, source] = await Promise.all([validateTranslation(rawTranslation), validateOriginal(rawSource)])
  if (!isComparisonIdentity(source, translation) || source.paragraphs.length !== translation.paragraphs.length ||
    source.paragraphs.some((paragraph, index) => paragraph.paragraphId !== translation.paragraphs[index].paragraphId)) throw mismatch()
  return { translation, source, translated: translationReadingDocument(translation), original: {
    id: `original:${source.recordId}:${source.sourceRevision}`, kind: 'original', bookTitle: source.bookTitle, chapterTitle: source.chapterTitle,
    language: source.sourceLanguage, paragraphs: source.paragraphs.map(({ paragraphId, text }) => ({ paragraphId, text })),
    glossaryIdentity: { providerId: source.providerId, bookId: source.bookId },
  } }
}

export async function findTranslationSource(translation: TranslationResponse, options: {
  personal?: Pick<PersonalLibrary, 'originals'> | null
  client?: Pick<WorkflowClient, 'chapter' | 'chapters'> | null
  sourceRecordId?: string
  signal: AbortSignal
}): Promise<{ match: MatchedComparison; saved: boolean } | undefined> {
  const { signal } = options
  signal.throwIfAborted()
  if (options.personal) {
    let source: StoredChapter | undefined
    try { const snapshot = await options.personal.originals(); source = snapshot.books.find(({ chapter }) => isComparisonIdentity(chapter, translation))?.chapter }
    catch (failure) { if (!options.client) throw failure }
    signal.throwIfAborted()
    if (source) { const match = await matchTranslationSource(translation, source); signal.throwIfAborted(); return { match, saved: true } }
  }
  if (!options.client) return undefined
  if (options.sourceRecordId) {
    const source = await options.client.chapter(options.sourceRecordId, signal)
    const match = await matchTranslationSource(translation, source); signal.throwIfAborted(); return { match, saved: false }
  }
  // The library endpoint predates sourceRecordId on artifact responses. Search summaries, and
  // download only an exact revision match. Every page remains cancellable when the reader closes.
  for (let page = 0; ; page++) {
    signal.throwIfAborted()
    const result = await options.client.chapters(page, signal); signal.throwIfAborted()
    if (result.page !== page) throw mismatch()
    const summary = result.items.find(source => isComparisonIdentity(source, translation))
    if (summary) {
      const source = await options.client.chapter(summary.recordId, signal)
      const match = await matchTranslationSource(translation, source); signal.throwIfAborted(); return { match, saved: false }
    }
    if (!result.hasNext) return undefined
  }
}

/** A display-only interleaving; these IDs and labels never enter canonical note/position stores. */
export function pairedComparisonDocument(match: MatchedComparison, labels: { original: string; translation: string }): ReadingDocument {
  return { id: `comparison:${match.translation.recordId}:${match.translation.revision}`, kind: 'introduction',
    bookTitle: match.translated.bookTitle, chapterTitle: match.translated.chapterTitle, language: `${match.original.language} / ${match.translated.language}`,
    paragraphs: match.original.paragraphs.flatMap((paragraph, index) => [
      { paragraphId: `original:${paragraph.paragraphId}`, text: `${labels.original} · ${index + 1}\n${paragraph.text}` },
      { paragraphId: `translation:${paragraph.paragraphId}`, text: `${labels.translation} · ${index + 1}\n${match.translated.paragraphs[index].text}` },
    ]) }
}

export function comparisonParagraphId(mode: ComparisonMode, anchor: ReadingAnchor | undefined): string | undefined {
  if (!anchor) return undefined
  if (mode !== 'comparison') return anchor.paragraphId
  for (const prefix of ['original:', 'translation:']) if (anchor.paragraphId.startsWith(prefix)) return anchor.paragraphId.slice(prefix.length)
}

/** A translated character offset has no exact source counterpart; align only the verified paragraph. */
export function comparisonSwitchAnchor(match: MatchedComparison, mode: ComparisonMode, paragraphId: string | undefined,
  remembered?: ReadingAnchor): ReadingAnchor | undefined {
  const index = match.original.paragraphs.findIndex(paragraph => paragraph.paragraphId === paragraphId)
  if (index < 0) return undefined
  if (mode === 'comparison') return { paragraphId: `original:${paragraphId}`, characterOffset: 0 }
  const paragraph = (mode === 'original' ? match.original : match.translated).paragraphs[index]
  if (remembered && remembered.paragraphId === paragraphId && Number.isSafeInteger(remembered.characterOffset) && remembered.characterOffset >= 0 && remembered.characterOffset < paragraph.text.length &&
    !(remembered.characterOffset > 0 && /[\uD800-\uDBFF]/.test(paragraph.text[remembered.characterOffset - 1]) && /[\uDC00-\uDFFF]/.test(paragraph.text[remembered.characterOffset]))) return remembered
  return { paragraphId: paragraph.paragraphId, characterOffset: 0 }
}
