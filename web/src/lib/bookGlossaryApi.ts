import { validRecordId } from './validation'
import type { components } from '../generated/bookGlossary'

export type BookGlossaryEntry = components['schemas']['BookGlossaryEntry']
export type BookGlossary = BookGlossaryEntry[] | null
export type BookGlossaryView = components['schemas']['BookGlossaryView']
export type BookGlossaryMutation = Omit<components['schemas']['PutBookGlossaryRequest'], 'providerId' | 'bookId' | 'targetLanguage'>
export type BookGlossaryScope = Pick<BookGlossaryView, 'providerId' | 'bookId' | 'targetLanguage'>
export type BookGlossaryErrorCode = 'authentication' | 'forbidden' | 'conflict' | 'choice-stale' | 'invalid-request' | 'mutation-reused' | 'limit' | 'exhausted' | 'not-found' | 'server' | 'network' | 'timeout' | 'aborted' | 'invalid-response' | 'storage'
export class BookGlossaryError extends Error {
  constructor(readonly code: BookGlossaryErrorCode, readonly current?: BookGlossaryView, readonly retryAfterSeconds?: number) {
    super(`Book glossary synchronization: ${code}`); this.name = 'BookGlossaryError'
  }
}
const invalid = (): never => { throw new BookGlossaryError('invalid-response') }
function object(value: unknown): Record<string, unknown> { return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid() }
function keys(value: Record<string, unknown>, names: string[]) { if (Object.keys(value).length !== names.length || names.some(name => !Object.hasOwn(value, name))) invalid() }
const integer = (value: unknown, minimum: number, maximum: number): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
export function glossaryText(input: unknown, minimum: number, maximum: number): string {
  if (typeof input !== 'string' || input.length < minimum || input.length > maximum || minimum > 0 && !input.trim() || /[\u0000-\u001f\u007f-\u009f]/.test(input)) return invalid()
  for (let i = 0; i < input.length; i++) {
    const code = input.charCodeAt(i)
    if (code >= 0xd800 && code <= 0xdbff) { const next = input.charCodeAt(++i); if (!(next >= 0xdc00 && next <= 0xdfff)) return invalid() }
    else if (code >= 0xdc00 && code <= 0xdfff) return invalid()
  }
  return input
}
function identityText(value: unknown, maximum: number) { const text = glossaryText(value, 1, maximum); if (text !== text.trim()) return invalid(); return text }
export function validateBookGlossary(value: unknown): BookGlossary {
  if (value === null) return null
  if (!Array.isArray(value) || value.length > 500) return invalid()
  const entries = value.map(input => {
    const e = object(input); keys(e, ['id', 'sourceTerm', 'translatedTerm', 'displayTerm', 'kind', 'caseSensitive', 'enabled'])
    if (!['Character', 'Place', 'Term'].includes(e.kind as string) || typeof e.caseSensitive !== 'boolean' || typeof e.enabled !== 'boolean') return invalid()
    return { id: identityText(e.id, 200), sourceTerm: glossaryText(e.sourceTerm, 1, 200), translatedTerm: glossaryText(e.translatedTerm, 1, 200), displayTerm: glossaryText(e.displayTerm, 0, 200), kind: e.kind as BookGlossaryEntry['kind'], caseSensitive: e.caseSensitive, enabled: e.enabled }
  })
  if (new Set(entries.map(e => e.id)).size !== entries.length) return invalid()
  return entries
}
export function validateBookGlossaryScope(value: unknown): BookGlossaryScope {
  const scope = object(value); keys(scope, ['providerId', 'bookId', 'targetLanguage'])
  const targetLanguage = glossaryText(scope.targetLanguage, 2, 24)
  if (targetLanguage === 'auto' || !/^[a-z]{2,8}(?:-[a-z0-9]{1,8})*$/.test(targetLanguage)) return invalid()
  return { providerId: identityText(scope.providerId, 100), bookId: identityText(scope.bookId, 2000), targetLanguage }
}
export const bookGlossaryKey = (scope: BookGlossaryScope) => JSON.stringify([scope.providerId, scope.bookId, scope.targetLanguage])
export function sameBookGlossaryScope(a: BookGlossaryScope, b: BookGlossaryScope): boolean { return bookGlossaryKey(a) === bookGlossaryKey(b) }
function timestamp(value: unknown): string {
  if (typeof value !== 'string') return invalid()
  const parts = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d{1,9})?Z$/.exec(value)
  if (!parts) return invalid()
  const [year, month, day, hour, minute, second] = parts.slice(1).map(Number), leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1] || hour > 23 || minute > 59 || second > 59) return invalid()
  return value
}
export function validateBookGlossaryView(value: unknown): BookGlossaryView {
  const v = object(value); keys(v, ['providerId', 'bookId', 'targetLanguage', 'version', 'entries', 'updatedAt'])
  const scope = validateBookGlossaryScope({ providerId: v.providerId, bookId: v.bookId, targetLanguage: v.targetLanguage })
  if (!integer(v.version, 0, Number.MAX_SAFE_INTEGER)) return invalid()
  if (v.version === 0) {
    if (v.entries !== null || v.updatedAt !== null) return invalid()
    return { ...scope, version: 0, entries: null, updatedAt: null }
  }
  return { ...scope, version: v.version, entries: validateBookGlossary(v.entries), updatedAt: timestamp(v.updatedAt) }
}
export function validateBookGlossaryMutation(value: unknown): BookGlossaryMutation {
  const m = object(value); keys(m, ['expectedVersion', 'mutationId', 'entries'])
  if (!integer(m.expectedVersion, 0, Number.MAX_SAFE_INTEGER - 1) || typeof m.mutationId !== 'string' || !validRecordId(m.mutationId)) return invalid()
  return { expectedVersion: m.expectedVersion, mutationId: m.mutationId, entries: validateBookGlossary(m.entries) }
}
export function sameBookGlossary(a: BookGlossary, b: BookGlossary): boolean { return JSON.stringify(a) === JSON.stringify(b) }
/** Reserve worst-case version digits before persisting an intent that must later fit the wire. */
export function validateBookGlossaryPayload(scope: BookGlossaryScope, entries: BookGlossary): BookGlossary {
  let checked: BookGlossary
  try { checked = validateBookGlossary(entries) } catch { throw new BookGlossaryError('invalid-request') }
  if (new TextEncoder().encode(JSON.stringify({ ...scope, expectedVersion: Number.MAX_SAFE_INTEGER - 1, mutationId: '00000000-0000-4000-8000-000000000000', entries: checked })).byteLength > 1024 * 1024) throw new BookGlossaryError('invalid-request')
  return checked
}
export function sameBookGlossaryView(a: BookGlossaryView | null, b: BookGlossaryView | null): boolean {
  return a === null || b === null ? a === b : sameBookGlossaryScope(a, b) && a.version === b.version && a.updatedAt === b.updatedAt && (a.entries === null || b.entries === null ? a.entries === b.entries : sameBookGlossary(a.entries, b.entries))
}
async function readJson(response: Response, signal: AbortSignal): Promise<unknown> {
  const maximum = 2 * 1024 * 1024
  if (Number(response.headers.get('content-length')) > maximum) return invalid()
  const reader = response.body?.getReader(); if (!reader) return invalid()
  let bytes = 0, body = ''; const decoder = new TextDecoder('utf-8', { fatal: true })
  try {
    while (true) {
      if (signal.aborted) throw new BookGlossaryError('aborted')
      const part = await reader.read().catch(() => { throw new BookGlossaryError(signal.aborted ? 'aborted' : 'network') })
      if (part.done) break
      bytes += part.value.byteLength; if (bytes > maximum) return invalid()
      body += decoder.decode(part.value, { stream: true })
    }
    return JSON.parse(body + decoder.decode()) as unknown
  } catch (error) { if (error instanceof BookGlossaryError) throw error; return invalid() }
  finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}
export interface BookGlossaryClient {
  get(scope: BookGlossaryScope, signal?: AbortSignal): Promise<BookGlossaryView>
  put(scope: BookGlossaryScope, input: BookGlossaryMutation, signal?: AbortSignal): Promise<BookGlossaryView>
  /** Restore the maximum persisted account deadline before starting any document controllers. */
  deferWritesUntil?(epochMs: number): void
  close(): void
}
export function createBookGlossaryClient(credentials: { username: string; password: string }, options: { fetch?: typeof fetch; timeoutMs?: number } = {}): BookGlossaryClient {
  if (!credentials.username.trim() || /[:\r\n]/.test(credentials.username) || /[\r\n]/.test(credentials.password)) throw new BookGlossaryError('invalid-request')
  let authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${credentials.username}:${credentials.password}`), byte => String.fromCharCode(byte)).join(''))}`, closed = false
  const transport = options.fetch ?? globalThis.fetch.bind(globalThis), timeoutMs = options.timeoutMs ?? 20_000, controllers = new Set<AbortController>()
  let writeAfter = 0
  function deferWritesUntil(epochMs: number) { if (Number.isSafeInteger(epochMs) && epochMs >= 0) writeAfter = Math.max(writeAfter, epochMs) }
  function checkWriteLimit() { if (writeAfter > Date.now()) throw new BookGlossaryError('limit', undefined, Math.ceil((writeAfter - Date.now()) / 1000)) }
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new BookGlossaryError('invalid-request')
  async function request(url: string, signal?: AbortSignal, body?: BookGlossaryMutation | BookGlossaryScope, csrf?: { headerName: string; token: string }, scope?: BookGlossaryScope, method?: 'POST'): Promise<unknown> {
    if (closed || signal?.aborted) throw new BookGlossaryError('aborted')
    const controller = new AbortController(), abort = () => controller.abort(); let timedOut = false
    controllers.add(controller); signal?.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(() => { timedOut = true; abort() }, timeoutMs)
    try {
      const headers: Record<string, string> = { Authorization: authorization, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
      if (body) headers['Content-Type'] = 'application/json'
      if (csrf) headers[csrf.headerName] = csrf.token
      const response = await transport(url, { method: method ?? (body ? 'PUT' : 'GET'), body: body ? JSON.stringify({ ...scope, ...body }) : undefined, headers, credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store', signal: controller.signal })
      if (closed || controller.signal.aborted) throw new BookGlossaryError('aborted')
      if (response.redirected) return invalid()
      if (response.status === 401) throw new BookGlossaryError('authentication')
      if (response.status === 403) throw new BookGlossaryError('forbidden')
      const type = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()
      if (response.ok ? type !== 'application/json' : !['application/json', 'application/problem+json'].includes(type ?? '')) throw new BookGlossaryError(response.status >= 500 ? 'server' : 'invalid-response')
      const value = await readJson(response, controller.signal)
      if (closed || controller.signal.aborted) throw new BookGlossaryError('aborted')
      if (response.ok) return value
      const problem = object(value)
      if (response.status === 409 && problem.code === 'BOOK_GLOSSARY_CONFLICT') {
        const current = validateBookGlossaryView(problem.current)
        if (!scope || !sameBookGlossaryScope(scope, current)) return invalid()
        throw new BookGlossaryError('conflict', current)
      }
      if (response.status === 404 && problem.code === 'BOOK_GLOSSARY_NOT_FOUND') throw new BookGlossaryError('not-found')
      if (response.status === 409 && problem.code === 'BOOK_GLOSSARY_EXHAUSTED') throw new BookGlossaryError('exhausted')
      if (response.status === 400 && problem.code === 'BOOK_GLOSSARY_MUTATION_REUSED') throw new BookGlossaryError('mutation-reused')
      if (response.status === 400 && problem.code === 'BOOK_GLOSSARY_INVALID' || response.status === 413) throw new BookGlossaryError('invalid-request')
      if (response.status === 429 && problem.code === 'BOOK_GLOSSARY_LIMIT') {
        const retry = Number(response.headers.get('retry-after'))
        const seconds = Number.isSafeInteger(retry) && retry > 0 && retry <= 86400 ? retry : 60
        deferWritesUntil(Date.now() + seconds * 1000)
        throw new BookGlossaryError('limit', undefined, seconds)
      }
      throw new BookGlossaryError(response.status >= 500 ? 'server' : 'invalid-response')
    } catch (error) {
      if (closed || signal?.aborted) throw new BookGlossaryError('aborted')
      if (timedOut) throw new BookGlossaryError('timeout')
      if (error instanceof BookGlossaryError) throw error
      throw new BookGlossaryError('network')
    } finally { clearTimeout(timer); controllers.delete(controller); signal?.removeEventListener('abort', abort) }
  }
  return {
    async get(inputScope, signal) {
      let scope: BookGlossaryScope
      try { scope = validateBookGlossaryScope(inputScope) } catch { throw new BookGlossaryError('invalid-request') }
      const token = object(await request('/api/v1/csrf', signal))
      if (typeof token.headerName !== 'string' || !['x-csrf-token', 'x-xsrf-token'].includes(token.headerName.toLowerCase()) || typeof token.token !== 'string' || !token.token || token.token.length > 4096 || /[\r\n]/.test(token.token)) return invalid()
      // Body-based read avoids URL/header limits for the contract's 2000 UTF-16 book IDs.
      const view = validateBookGlossaryView(await request('/api/v1/book-glossary/query', signal, scope, { headerName: token.headerName, token: token.token }, scope, 'POST'))
      if (!sameBookGlossaryScope(scope, view)) return invalid()
      return view
    },
    async put(inputScope, input, signal) {
      let body: BookGlossaryMutation
      let scope: BookGlossaryScope
      try { body = validateBookGlossaryMutation(input); scope = validateBookGlossaryScope(inputScope); if (new TextEncoder().encode(JSON.stringify({ ...scope, ...body })).byteLength > 1024 * 1024) throw new Error() } catch { throw new BookGlossaryError('invalid-request') }
      if (closed || signal?.aborted) throw new BookGlossaryError('aborted')
      checkWriteLimit()
      const token = object(await request('/api/v1/csrf', signal))
      if (typeof token.headerName !== 'string' || !['x-csrf-token', 'x-xsrf-token'].includes(token.headerName.toLowerCase()) || typeof token.token !== 'string' || !token.token || token.token.length > 4096 || /[\r\n]/.test(token.token)) return invalid()
      checkWriteLimit()
      const view = validateBookGlossaryView(await request('/api/v1/book-glossary', signal, body, { headerName: token.headerName, token: token.token }, scope))
      if (!sameBookGlossaryScope(scope, view) || view.version !== body.expectedVersion + 1 || !sameBookGlossary(view.entries, body.entries)) return invalid()
      return view
    },
    deferWritesUntil,
    close() { closed = true; authorization = ''; controllers.forEach(controller => controller.abort()); controllers.clear() },
  }
}
