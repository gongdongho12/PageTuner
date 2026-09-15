import { ApiError } from './errors'
import { localFileHash, MAX_LOCAL_FILE_BYTES, parseLocalDocument, type LocalDocument, type LocalEncoding } from './localDocuments'
import type { components } from '../generated/jsonCatalog'

export type JsonCatalogEntry = components['schemas']['JsonCatalogEntry']
export type JsonCatalog = components['schemas']['JsonCatalogDocument']

const invalid = () => new ApiError('invalid-response', '카탈로그 응답의 형식을 확인할 수 없습니다.')
const object = (value: unknown): Record<string, unknown> => { if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid(); return value as Record<string, unknown> }
const string = (value: unknown, max = 2000): string => { if (typeof value !== 'string' || !value.trim() || value.length > max) throw invalid(); return value }
const nullable = (value: unknown, max = 2000) => value === null ? null : string(value, max)
const array = <T>(value: unknown, max: number, parse: (value: unknown) => T): T[] => { if (!Array.isArray(value) || value.length > max) throw invalid(); return value.map(parse) }

export function validateJsonCatalog(value: unknown): JsonCatalog {
  const o = object(value)
  if (o.version !== 'pagetuner.catalog.v0') throw invalid()
  const items = array(o.items, 1000, value => {
    const item = object(value), hints = object(item.translationHints)
    if (!['txt', 'markdown', 'epub', 'pdf'].includes(item.format as string) ||
      (item.size !== null && (typeof item.size !== 'number' || !Number.isSafeInteger(item.size) || item.size < 0))) throw invalid()
    return { id: string(item.id), title: string(item.title), authors: array(item.authors, 100, value => string(value)), format: item.format as JsonCatalogEntry['format'],
      href: string(item.href, 4096), language: nullable(item.language, 24), type: nullable(item.type), size: item.size as number | null,
      checksum: nullable(item.checksum), updatedAt: nullable(item.updatedAt), cover: nullable(item.cover, 4096),
      translationHints: { sourceLanguage: string(hints.sourceLanguage, 24), targetLanguages: array(hints.targetLanguages, 100, value => string(value, 24)) } }
  })
  if (new Set(items.map(item => item.id)).size !== items.length) throw invalid()
  return { version: o.version, id: string(o.id), title: string(o.title), catalogUrl: string(o.catalogUrl, 4096), updatedAt: nullable(o.updatedAt), items,
    links: array(o.links, 100, value => { const link = object(value); return { rel: string(link.rel, 100), href: string(link.href, 4096), type: nullable(link.type) } }) }
}

/** The server additionally checks every DNS address used by the socket and every redirect. */
export function publicCatalogUrl(value: string): string {
  const invalidUrl = () => new ApiError('invalid-request', '공개 HTTPS 카탈로그 또는 파일 주소를 입력해 주세요.')
  if (value.length > 4096 || /[\x00-\x20\x7f\\]/.test(value)) throw invalidUrl()
  let url: URL
  try { url = new URL(value) } catch { throw invalidUrl() }
  if (url.protocol !== 'https:' || (url.port && url.port !== '443') || url.username || url.password || url.hash ||
      !url.hostname.includes('.') || /^[\d.]+$/.test(url.hostname) || /:/.test(url.hostname) || /\.(localhost|local|internal)\.?$/i.test(url.hostname)) throw invalidUrl()
  return url.href
}

export function filterJsonCatalog(items: JsonCatalogEntry[], query: string): JsonCatalogEntry[] {
  const key = query.trim().normalize('NFKC').toLowerCase()
  return !key ? items : items.filter(item => [item.title, ...item.authors].some(text => text.normalize('NFKC').toLowerCase().includes(key)))
}

export function catalogFileAllowed(entry: JsonCatalogEntry): void {
  publicCatalogUrl(entry.href)
  if (entry.size !== null && (entry.size <= 0 || entry.size > MAX_LOCAL_FILE_BYTES)) throw new ApiError('unsupported', '32MB 이하의 비어 있지 않은 파일만 가져올 수 있습니다.')
  if (entry.checksum !== null && !/^(?:sha256:)?[a-f0-9]{64}$/i.test(entry.checksum)) throw new ApiError('unsupported', '이 파일의 체크섬 방식은 지원하지 않습니다. SHA-256 카탈로그를 사용해 주세요.')
}

export async function parseCatalogFile(entry: JsonCatalogEntry, bytes: Uint8Array, options: { encoding?: LocalEncoding; signal?: AbortSignal } = {}): Promise<LocalDocument> {
  catalogFileAllowed(entry); options.signal?.throwIfAborted()
  if (!bytes.byteLength || bytes.byteLength > MAX_LOCAL_FILE_BYTES) throw new ApiError('invalid-response', '카탈로그 파일이 비어 있거나 32MB를 초과했습니다.')
  if (entry.size !== null && entry.size !== bytes.byteLength) throw new ApiError('integrity', '다운로드한 파일 크기가 카탈로그와 일치하지 않습니다.')
  if (entry.checksum && await localFileHash(bytes) !== entry.checksum.replace(/^sha256:/i, '').toLowerCase()) throw new ApiError('integrity', '다운로드한 파일의 SHA-256이 카탈로그와 일치하지 않습니다.')
  const suffix = entry.format === 'markdown' ? 'md' : entry.format
  const name = `${entry.title.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 180)}.${suffix}`
  const document = await parseLocalDocument({ name, size: bytes.byteLength, arrayBuffer: async () => Uint8Array.from(bytes).buffer }, options)
  options.signal?.throwIfAborted()
  const language = entry.language || entry.translationHints.sourceLanguage
  return { ...document, bookTitle: entry.title, language: language.length >= 2 && language.length <= 24 ? language : document.language }
}
