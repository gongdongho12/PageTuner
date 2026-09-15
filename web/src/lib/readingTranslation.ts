import type { StartTranslation, StoredChapter } from './workflowTypes'
import type { components } from '../generated/readingTranslations'
import { ApiError } from './errors'
import { sha256, validRecordId, validTimestamp } from './validation'

type Schemas = components['schemas']
export type ReadingFragment = Schemas['ReadingFragment']
export type ReadingPagination = { documentId: string; pages: readonly (readonly ReadingFragment[])[]; page: number }
export type ReadingPace = 'READING' | 'FAST' | 'OFFLINE_PREFETCH'
export type ReadingTranslationSettings = Pick<StartTranslation, 'providerKind' | 'targetLanguage' | 'sourceLanguage' | 'endpoint' | 'model' | 'apiKey' | 'glossary'> & {
  readingWordsPerMinute: number; paceMode: ReadingPace
}
export type ReadingTranslationRequest = Schemas['ReadingTranslationRequest'] & ReadingTranslationSettings
export type ReadingTranslationItem = Schemas['ReadingTranslationItem']
export type ReadingTranslationResponse = Schemas['ReadingTranslationResponse']
export interface ReadingTranslationClient {
  startReadingTranslation(input: ReadingTranslationRequest, signal?: AbortSignal): Promise<ReadingTranslationResponse>
  getReadingTranslation(id: string, signal?: AbortSignal): Promise<ReadingTranslationResponse>
  cancelReadingTranslation(id: string, signal?: AbortSignal): Promise<ReadingTranslationResponse>
}

export const readingFragmentKey = (fragment: ReadingFragment) => `${fragment.paragraphId.length}:${fragment.paragraphId}:${fragment.start}:${fragment.end}`
const invalid = () => new ApiError('invalid-response', '읽기 번역 응답이 원문 범위와 일치하지 않습니다. 원문을 계속 읽을 수 있습니다.')
function check(condition: unknown): asserts condition { if (!condition) throw invalid() }
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const text = (value: unknown): value is string => typeof value === 'string'
const record = (value: unknown): Record<string, unknown> => { check(value && typeof value === 'object' && !Array.isArray(value)); return value as Record<string, unknown> }
export function validateReadingTranslation(value: unknown): ReadingTranslationResponse {
  const input = record(value)
  check(input.scope === 'READING_PREVIEW' && typeof input.requestId === 'string' && validRecordId(input.requestId) &&
    typeof input.chapterRecordId === 'string' && validRecordId(input.chapterRecordId))
  check(['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(String(input.status)))
  check(text(input.sourceHash) && /^[a-f0-9]{64}$/.test(input.sourceHash) && text(input.sourceRevision) && /^[a-f0-9]{64}$/.test(input.sourceRevision))
  check(['GOOGLE_WEB_TRANSLATE_HTML', 'GOOGLE_CLOUD', 'DEEPSEEK', 'OPENAI_COMPATIBLE_LLM'].includes(String(input.providerKind)))
  check(text(input.targetLanguage) && /^[A-Za-z][A-Za-z0-9-]{0,23}$/.test(input.targetLanguage) && input.targetLanguage.toLowerCase() !== 'auto')
  check(integer(input.completedFragments) && integer(input.totalFragments) && input.totalFragments > 0 && input.totalFragments <= 64 && input.completedFragments <= input.totalFragments)
  check(validTimestamp(input.updatedAt) && (input.errorCode === null || text(input.errorCode) && input.errorCode.length <= 100))
  check(Array.isArray(input.items) && input.items.length <= 64)
  const items = input.items.map(value => {
    const item = record(value)
    check(text(item.paragraphId) && item.paragraphId.length > 0 && item.paragraphId.length <= 200 && integer(item.start) && integer(item.end) && item.end > item.start &&
      text(item.text) && item.text.trim() && item.text.length <= 96_000)
    return { paragraphId: item.paragraphId, start: item.start, end: item.end, text: item.text }
  })
  check(items.reduce((size, item) => size + item.text.length, 0) <= 96_000 && new Set(items.map(readingFragmentKey)).size === items.length)
  if (input.status === 'COMPLETED') check(items.length === input.totalFragments && input.completedFragments === input.totalFragments && input.errorCode === null)
  else check(items.length === 0)
  return { ...input, items } as ReadingTranslationResponse
}
export async function readingSourceHash(input: Pick<ReadingTranslationRequest, 'chapterRecordId' | 'sourceRevision' | 'fragments'>): Promise<string> {
  return sha256([input.chapterRecordId, input.sourceRevision, input.fragments.map(readingFragmentKey).join('\n')].join('\n'))
}
export async function verifyReadingTranslation(response: ReadingTranslationResponse, request: ReadingTranslationRequest): Promise<void> {
  validateReadingTranslation(response)
  check(response.requestId === request.requestId && response.chapterRecordId === request.chapterRecordId && response.sourceRevision === request.sourceRevision &&
    response.sourceHash === await readingSourceHash(request) && response.providerKind === request.providerKind && response.targetLanguage.toLowerCase() === request.targetLanguage.toLowerCase() && response.totalFragments === request.fragments.length)
  if (response.status === 'COMPLETED') check(response.items.every((item, index) => readingFragmentKey(item) === readingFragmentKey(request.fragments[index])))
}
export function readingFragmentText(chapter: StoredChapter, fragment: ReadingFragment): string {
  const paragraph = chapter.paragraphs.find(item => item.paragraphId === fragment.paragraphId)
  check(paragraph && integer(fragment.start) && integer(fragment.end) && fragment.end > fragment.start && fragment.end <= paragraph.text.length)
  for (const offset of [fragment.start, fragment.end]) check(offset === 0 || offset === paragraph.text.length ||
    !(/[\uD800-\uDBFF]/.test(paragraph.text[offset - 1]) && /[\uDC00-\uDFFF]/.test(paragraph.text[offset])))
  return paragraph.text.slice(fragment.start, fragment.end)
}
