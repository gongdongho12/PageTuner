import type { components } from '../generated/catalogTranslations';
import type { NovelBook, ProviderKind } from './workflowTypes';
import { ApiError } from './errors';
import { kotlinTrim, sha256, validRecordId, validTimestamp } from './validation';

type Schemas = components['schemas'];
export type CatalogTranslationRequest = Schemas['CatalogTranslationRequest'];
export type CatalogTranslationResponse = Schemas['CatalogTranslationResponse'];
export type CatalogTranslationOptions = { providerKind: ProviderKind; targetLanguage: string; apiKey?: string; endpoint?: string; model?: string };
const invalid = () => new ApiError('invalid-response', '목록 번역 응답을 확인할 수 없습니다. 원문을 표시합니다.');
const kinds = ['GOOGLE_WEB_TRANSLATE_HTML', 'GOOGLE_CLOUD', 'DEEPSEEK', 'OPENAI_COMPATIBLE_LLM'];
function ensure(value: unknown): asserts value { if (!value) throw invalid(); }
function text(value: unknown): string { ensure(typeof value === 'string'); return value; }
function count(value: unknown): number { ensure(typeof value === 'number' && Number.isSafeInteger(value) && value >= 0); return value; }
function boundedSlice(value: string, length: number) {
  let end = Math.min(value.length, length);
  if (end > 0 && end < value.length && /[\uD800-\uDBFF]/.test(value[end - 1])) end--;
  return value.slice(0, end);
}
/** Description translations are explicitly bounded excerpts; original source identities never change. */
export function createCatalogTranslationRequest(books: readonly NovelBook[], options: CatalogTranslationOptions) {
  if (!books.length || !kinds.includes(options.providerKind) || !/^[A-Za-z][A-Za-z0-9-]{0,23}$/.test(options.targetLanguage) || options.targetLanguage.toLowerCase() === 'auto') {
    throw new ApiError('invalid-request', '목록과 번역 언어를 확인해 주세요.');
  }
  const visible = books.slice(0, 24);
  if (visible.some(book => !book.bookId.trim() || book.bookId.length > 512 || !book.title.trim())) throw invalid();
  const titles = visible.map(book => boundedSlice(book.title, 400));
  let remaining = 24_000 - titles.reduce((sum, title) => sum + title.length, 0);
  const items = visible.map((book, index) => {
    const description = book.description ? boundedSlice(book.description, Math.min(2_000, remaining)) || null : null;
    remaining -= description?.length ?? 0;
    return { key: book.bookId, title: titles[index], description };
  });
  ensure(new Set(items.map(item => item.key)).size === items.length);
  const input: CatalogTranslationRequest = { requestId: crypto.randomUUID(), items, sourceLanguage: 'auto', targetLanguage: options.targetLanguage,
    providerKind: options.providerKind, apiKey: options.apiKey || undefined, endpoint: options.endpoint || undefined, model: options.model || undefined };
  return { input, truncated: visible.length !== books.length || items.some((item, i) => item.title !== visible[i].title || (item.description ?? '') !== (visible[i].description ?? '')) };
}
export function catalogSourceHash(input: CatalogTranslationRequest): Promise<string> {
  return sha256(input.items.map(item => [item.key, item.title, item.description ?? ''].map(value => `${value.length}:${value}`).join('')).join('\n'));
}
export function validateCatalogTranslation(value: unknown): CatalogTranslationResponse {
  ensure(!!value && typeof value === 'object' && !Array.isArray(value));
  const item = value as Record<string, unknown>;
  const status = text(item.status); ensure(['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(status));
  const requestId = text(item.requestId); ensure(validRecordId(requestId));
  const sourceHash = text(item.sourceHash); ensure(/^[0-9a-f]{64}$/.test(sourceHash));
  const providerKind = text(item.providerKind); ensure(kinds.includes(providerKind));
  const targetLanguage = text(item.targetLanguage); ensure(targetLanguage.length > 0 && targetLanguage.length <= 24);
  const updatedAt = text(item.updatedAt); ensure(validTimestamp(updatedAt));
  const completedSegments = count(item.completedSegments), totalSegments = count(item.totalSegments);
  ensure(totalSegments <= 120 && completedSegments <= totalSegments);
  ensure(Array.isArray(item.items) && item.items.length <= 24);
  const items = item.items.map(value => {
    ensure(!!value && typeof value === 'object' && !Array.isArray(value));
    const row = value as Record<string, unknown>;
    const key = text(row.key), title = text(row.title), description = row.description === null ? null : text(row.description);
    ensure(key.trim() && title.trim() && title.length <= 4_000 && (description?.length ?? 0) <= 16_000 && row.targetLanguage === targetLanguage);
    return { key, title, description, targetLanguage };
  });
  ensure(new Set(items.map(row => row.key)).size === items.length);
  ensure(items.reduce((total, row) => total + row.title.length + (row.description?.length ?? 0), 0) <= 96_000);
  if (status === 'COMPLETED') ensure(items.length > 0 && totalSegments > 0 && completedSegments === totalSegments);
  else ensure(items.length === 0);
  const errorCode = item.errorCode === null ? null : text(item.errorCode);
  return { requestId, status: status as CatalogTranslationResponse['status'], sourceHash, providerKind, targetLanguage,
    completedSegments, totalSegments, items, errorCode, updatedAt };
}
export async function verifyCatalogTranslation(result: CatalogTranslationResponse, request: CatalogTranslationRequest) {
  ensure(result.requestId === request.requestId && result.sourceHash === await catalogSourceHash(request) &&
    result.providerKind === (request.providerKind ?? 'GOOGLE_WEB_TRANSLATE_HTML') && result.targetLanguage === (request.targetLanguage ?? 'ko'));
  if (result.status === 'COMPLETED') {
    ensure(JSON.stringify(result.items.map(i => i.key)) === JSON.stringify(request.items.map(i => i.key)));
    ensure(result.items.every((item, index) => !kotlinTrim(request.items[index].description ?? '') || !!kotlinTrim(item.description ?? '')));
  }
  return result;
}
export function catalogFailureMessage(code: string | null) {
  switch (code) {
    case 'PROVIDER_NOT_CONFIGURED': return '제공자 API 키를 확인한 뒤 다시 번역해 주세요.';
    case 'ENDPOINT_NOT_ALLOWED': return '서버에서 허용한 제공자 주소를 사용해 주세요.';
    case 'CATALOG_TIMEOUT': return '목록 번역 시간이 초과되었습니다. 적은 항목으로 다시 시도해 주세요.';
    default: return '목록 번역을 완료하지 못했습니다. 제공자 설정과 연결을 확인해 주세요.';
  }
}
