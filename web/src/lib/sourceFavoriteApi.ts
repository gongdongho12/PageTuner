import { validRecordId } from './validation'

import type { components } from '../generated/sourceBookFavorites'

export type SourceBookIdentity = Pick<SourceFavoriteItem, 'providerId' | 'bookId'>
export type SourceFavoriteBook = components['schemas']['SourceBookFavoriteMetadata']
export type SourceFavoriteChange = Pick<SourceFavoriteItem, 'deleted' | 'book'>
export type SourceFavoriteItem = components['schemas']['SourceBookFavoriteItem']
export type SourceFavoriteMutation = components['schemas']['PutSourceBookFavoriteRequest']
export type SourceFavoriteQuery = { afterRevision: number; untilRevision?: number; limit?: number }
export type SourceFavoritePage = components['schemas']['SourceBookFavoriteChanges']
export type SourceFavoriteErrorCode = 'authentication' | 'forbidden' | 'conflict' | 'choice-stale' | 'invalid-request' | 'mutation-reused' | 'limit' | 'exhausted' | 'server' | 'network' | 'timeout' | 'aborted' | 'invalid-response' | 'storage'
export class SourceFavoriteError extends Error {
  constructor(readonly code: SourceFavoriteErrorCode, readonly current?: SourceFavoriteItem, readonly retryAfterSeconds?: number) { super(`Source favorite synchronization: ${code}`); this.name = 'SourceFavoriteError' }
}
const invalid = (): never => { throw new SourceFavoriteError('invalid-response') }
const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
function object(v: unknown): Record<string, unknown> { return v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : invalid() }
function keys(v: Record<string, unknown>, expected: string[]) { if (Object.keys(v).length !== expected.length || expected.some(key => !Object.hasOwn(v, key))) invalid() }
function text(v: unknown, max: number): string {
  if (typeof v !== 'string' || !v.length || v.length > max || v !== v.trim() || /[\u0000-\u001f\u007f-\u009f]/.test(v)) return invalid()
  for (let i = 0; i < v.length; i++) { const c = v.charCodeAt(i); if (c >= 0xd800 && c <= 0xdbff) { const n = v.charCodeAt(++i); if (!(n >= 0xdc00 && n <= 0xdfff)) return invalid() } else if (c >= 0xdc00 && c <= 0xdfff) return invalid() }
  return v
}
export const sourceBookKey = (id: SourceBookIdentity) => JSON.stringify([id.providerId, id.bookId])
export function validateSourceBookIdentity(value: unknown): SourceBookIdentity { const v = object(value); return { providerId: text(v.providerId, 100), bookId: text(v.bookId, 500) } }
export function validateSourceFavoriteBook(value: unknown): SourceFavoriteBook {
  const v = object(value); keys(v, ['title', 'authors', 'language', 'url'])
  if (!Array.isArray(v.authors) || v.authors.length > 20) return invalid()
  const url = text(v.url, 2048)
  // Reject browser URL repair and punycode normalization: the shared contract uses an ASCII authority.
  const authority = /^https?:\/\/([^/?#]+)/i.exec(url)?.[1]
  if (!authority || /[^\x21-\x7e]/.test(url) || /[\\]/.test(url) || /%(?![0-9a-f]{2})/i.test(url) || /[@%]/.test(authority)) return invalid()
  let parsed: URL; try { parsed = new URL(url) } catch { return invalid() }
  if (!['http:', 'https:'].includes(parsed.protocol) || !parsed.hostname || parsed.username || parsed.password || /[<>\"{}|^`]/.test(url) || !parsed.hostname.startsWith('[') && !/^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)*[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.?$/i.test(parsed.hostname)) return invalid()
  const rawHost = authority.startsWith('[') ? authority.slice(0, authority.indexOf(']') + 1) : authority.split(':')[0]
  if (rawHost.startsWith('[')) {
    if (!/^\[[0-9a-f:]+\]$/i.test(rawHost)) return invalid()
  } else {
    const name = rawHost.replace(/\.$/, ''), labels = name.split('.'), last = labels.at(-1)!
    if (name.length > 253 || labels.some(label => !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label))) return invalid()
    const numeric = /^(?:[0-9]+|0x[0-9a-f]*)$/i.test(last)
    if (numeric ? !(labels.length === 4 && !rawHost.endsWith('.') && labels.every(label => /^(?:0|[1-9][0-9]{0,2})$/.test(label) && Number(label) <= 255)) : labels.length > 1 && !/^[a-z]/i.test(last)) return invalid()
  }
  const path = url.slice(url.indexOf(authority) + authority.length).split(/[?#]/)[0]
  if (/[\[\]]/.test(path)) return invalid()
  return { title: text(v.title, 500), authors: v.authors.map(a => text(a, 200)), language: text(v.language, 35), url }
}
export function validateSourceFavoriteChange(value: unknown): SourceFavoriteChange {
  const v = object(value); if (typeof v.deleted !== 'boolean' || v.deleted && v.book !== null) return invalid()
  return { deleted: v.deleted, book: v.deleted ? null : validateSourceFavoriteBook(v.book) }
}
function validTimestamp(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const parts = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d{1,9})?Z$/.exec(value)
  if (!parts) return false
  const [year, month, day, hour, minute, second] = parts.slice(1).map(Number), leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  return year > 0 && month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1] && hour <= 23 && minute <= 59 && second <= 59
}
export function validateSourceFavoriteItem(value: unknown, allowEmpty = false): SourceFavoriteItem {
  const v = object(value); keys(v, ['providerId', 'bookId', 'version', 'changeRevision', 'deleted', 'book', 'updatedAt'])
  const id = validateSourceBookIdentity(v), change = validateSourceFavoriteChange(v)
  if (!integer(v.version) || !integer(v.changeRevision)) return invalid()
  if (v.version === 0) { if (!allowEmpty || v.changeRevision !== 0 || !change.deleted || v.updatedAt !== null) return invalid() }
  else if (v.changeRevision < 1 || !validTimestamp(v.updatedAt)) return invalid()
  return { ...id, ...change, version: v.version, changeRevision: v.changeRevision, updatedAt: v.updatedAt as string | null }
}
export function validateSourceFavoriteMutation(value: unknown): SourceFavoriteMutation {
  const v = object(value); keys(v, ['providerId', 'bookId', 'expectedVersion', 'mutationId', 'deleted', 'book'])
  if (!integer(v.expectedVersion) || v.expectedVersion >= Number.MAX_SAFE_INTEGER || !validRecordId(v.mutationId)) return invalid()
  return { ...validateSourceBookIdentity(v), ...validateSourceFavoriteChange(v), expectedVersion: v.expectedVersion, mutationId: v.mutationId }
}
export const sameSourceFavoriteChange = (a: SourceFavoriteChange, b: SourceFavoriteChange) => a.deleted === b.deleted && JSON.stringify(a.book) === JSON.stringify(b.book)
export function validateSourceFavoritePage(value: unknown, query: SourceFavoriteQuery): SourceFavoritePage {
  const p = object(value); keys(p, ['items', 'nextAfterRevision', 'watermark', 'hasMore'])
  if (!Array.isArray(p.items) || p.items.length > (query.limit ?? 50) || !integer(p.nextAfterRevision) || !integer(p.watermark) || typeof p.hasMore !== 'boolean' || p.watermark < query.afterRevision || query.untilRevision !== undefined && p.watermark !== query.untilRevision || p.nextAfterRevision < query.afterRevision || p.nextAfterRevision > p.watermark) return invalid()
  let previous = query.afterRevision; const versions = new Map<string, number>()
  const items = p.items.map(v => { const item = validateSourceFavoriteItem(v), key = sourceBookKey(item); if (item.changeRevision <= previous || item.changeRevision > (p.watermark as number) || (versions.get(key) ?? 0) >= item.version) return invalid(); previous = item.changeRevision; versions.set(key, item.version); return item })
  if (p.hasMore ? !items.length || p.nextAfterRevision !== previous || p.nextAfterRevision >= p.watermark : p.nextAfterRevision !== p.watermark) return invalid()
  return { items, nextAfterRevision: p.nextAfterRevision, watermark: p.watermark, hasMore: p.hasMore }
}
async function readJson(response: Response, signal: AbortSignal): Promise<unknown> {
  const maximum = 4 * 1024 * 1024
  if (Number(response.headers.get('content-length')) > maximum) return invalid()
  const reader = response.body?.getReader(); if (!reader) return invalid()
  let bytes = 0, body = ''; const decoder = new TextDecoder('utf-8', { fatal: true })
  try { while (true) { if (signal.aborted) throw new SourceFavoriteError('aborted'); const part = await reader.read().catch(() => { throw new SourceFavoriteError(signal.aborted ? 'aborted' : 'network') }); if (part.done) break; bytes += part.value.byteLength; if (bytes > maximum) return invalid(); body += decoder.decode(part.value, { stream: true }) } return JSON.parse(body + decoder.decode()) as unknown }
  catch (e) { if (e instanceof SourceFavoriteError) throw e; return invalid() }
  finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}
export interface SourceFavoriteClient { list(query: SourceFavoriteQuery, signal?: AbortSignal): Promise<SourceFavoritePage>; put(input: SourceFavoriteMutation, signal?: AbortSignal): Promise<SourceFavoriteItem>; close(): void }
export function createSourceFavoriteClient(credentials: { username: string; password: string }, options: { fetch?: typeof fetch; timeoutMs?: number } = {}): SourceFavoriteClient {
  if (!credentials.username.trim() || /[:\r\n]/.test(credentials.username) || /[\r\n]/.test(credentials.password)) throw new SourceFavoriteError('invalid-request')
  let authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${credentials.username}:${credentials.password}`), b => String.fromCharCode(b)).join(''))}`, closed = false
  const controllers = new Set<AbortController>(), transport = options.fetch ?? globalThis.fetch.bind(globalThis), timeoutMs = options.timeoutMs ?? 20_000
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new SourceFavoriteError('invalid-request')
  async function request(url: string, signal?: AbortSignal, body?: SourceFavoriteMutation, csrf?: { headerName: string; token: string }): Promise<unknown> {
    if (closed || signal?.aborted) throw new SourceFavoriteError('aborted')
    const controller = new AbortController(), abort = () => controller.abort(); let timedOut = false
    controllers.add(controller); signal?.addEventListener('abort', abort, { once: true }); const timer = setTimeout(() => { timedOut = true; abort() }, timeoutMs)
    try {
      const headers: Record<string, string> = { Authorization: authorization, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
      if (body) headers['Content-Type'] = 'application/json'; if (csrf) headers[csrf.headerName] = csrf.token
      const response = await transport(url, { method: body ? 'PUT' : 'GET', body: body ? JSON.stringify(body) : undefined, headers, credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store', signal: controller.signal })
      if (closed || controller.signal.aborted) throw new SourceFavoriteError('aborted')
      if (response.redirected) return invalid()
      if (response.status === 401) throw new SourceFavoriteError('authentication'); if (response.status === 403) throw new SourceFavoriteError('forbidden')
      const type = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()
      if (response.ok ? type !== 'application/json' : !['application/json', 'application/problem+json'].includes(type ?? '')) throw new SourceFavoriteError(response.status >= 500 ? 'server' : 'invalid-response')
      const value = await readJson(response, controller.signal)
      if (closed || controller.signal.aborted) throw new SourceFavoriteError('aborted')
      if (response.ok) return value
      const problem = object(value)
      if (response.status === 409 && problem.code === 'SOURCE_BOOK_FAVORITE_CONFLICT' && body) { const current = validateSourceFavoriteItem(problem.current, true); if (sourceBookKey(current) !== sourceBookKey(body)) return invalid(); throw new SourceFavoriteError('conflict', current) }
      if (response.status === 409 && ['SOURCE_BOOK_FAVORITE_EXHAUSTED', 'SOURCE_BOOK_FAVORITE_REVISION_EXHAUSTED'].includes(problem.code as string)) throw new SourceFavoriteError('exhausted')
      if (response.status === 400 && problem.code === 'SOURCE_BOOK_FAVORITE_MUTATION_REUSED') throw new SourceFavoriteError('mutation-reused')
      if (response.status === 400 && problem.code === 'SOURCE_BOOK_FAVORITE_INVALID' || response.status === 413) throw new SourceFavoriteError('invalid-request')
      if (response.status === 429) { const retry = Number(response.headers.get('retry-after')); throw new SourceFavoriteError('limit', undefined, Number.isSafeInteger(retry) && retry > 0 && retry <= 86400 ? retry : 60) }
      throw new SourceFavoriteError(response.status >= 500 ? 'server' : 'invalid-response')
    } catch (e) { if (closed || signal?.aborted) throw new SourceFavoriteError('aborted'); if (timedOut) throw new SourceFavoriteError('timeout'); if (e instanceof SourceFavoriteError) throw e; throw new SourceFavoriteError('network') }
    finally { clearTimeout(timer); controllers.delete(controller); signal?.removeEventListener('abort', abort) }
  }
  return {
    async list(query, signal) { const limit = query.limit ?? 50; if (!integer(query.afterRevision) || !integer(limit) || limit < 1 || limit > 100 || query.untilRevision !== undefined && (!integer(query.untilRevision) || query.untilRevision < query.afterRevision)) throw new SourceFavoriteError('invalid-request'); const params = new URLSearchParams({ afterRevision: String(query.afterRevision), limit: String(limit) }); if (query.untilRevision !== undefined) params.set('untilRevision', String(query.untilRevision)); return validateSourceFavoritePage(await request(`/api/v1/source-book-favorites?${params}`, signal), query) },
    async put(input, signal) { let body: SourceFavoriteMutation; try { body = validateSourceFavoriteMutation(input) } catch { throw new SourceFavoriteError('invalid-request') }
      if (new TextEncoder().encode(JSON.stringify(body)).byteLength > 32 * 1024) throw new SourceFavoriteError('invalid-request')
      const token = object(await request('/api/v1/csrf', signal)); if (typeof token.headerName !== 'string' || !['x-csrf-token', 'x-xsrf-token'].includes(token.headerName.toLowerCase()) || typeof token.token !== 'string' || !token.token || token.token.length > 4096 || /[\r\n]/.test(token.token)) return invalid()
      const item = validateSourceFavoriteItem(await request('/api/v1/source-book-favorites', signal, body, { headerName: token.headerName, token: token.token }))
      if (sourceBookKey(item) !== sourceBookKey(body) || item.version !== body.expectedVersion + 1 || !sameSourceFavoriteChange(item, body)) return invalid(); return item
    },
    close() { closed = true; authorization = ''; controllers.forEach(c => c.abort()); controllers.clear() },
  }
}
