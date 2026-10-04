import { validRecordId } from './validation'
import type { components } from '../generated/readerPreferences'

export type SharedReaderPreferences = components['schemas']['ReaderPreferences']
export type ReaderPreferenceView = components['schemas']['ReaderPreferencesView']
export type ReaderPreferenceMutation = components['schemas']['PutReaderPreferencesRequest']
export type ReaderPreferenceErrorCode = 'authentication' | 'forbidden' | 'conflict' | 'choice-stale' | 'invalid-request' | 'mutation-reused' | 'limit' | 'exhausted' | 'server' | 'network' | 'timeout' | 'aborted' | 'invalid-response' | 'storage'
export class ReaderPreferenceError extends Error {
  constructor(readonly code: ReaderPreferenceErrorCode, readonly current?: ReaderPreferenceView, readonly retryAfterSeconds?: number) {
    super(`Reader preference synchronization: ${code}`); this.name = 'ReaderPreferenceError'
  }
}
const invalid = (): never => { throw new ReaderPreferenceError('invalid-response') }
function object(value: unknown): Record<string, unknown> { return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid() }
function keys(value: Record<string, unknown>, names: string[]) { if (Object.keys(value).length !== names.length || names.some(name => !Object.hasOwn(value, name))) invalid() }
const integer = (value: unknown, minimum: number, maximum: number): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
export function validateReaderPreferences(value: unknown): SharedReaderPreferences {
  const p = object(value); keys(p, ['fontSize', 'lineHeightPercent', 'pageMargin', 'touchDirection', 'listMode'])
  if (!integer(p.fontSize, 14, 36) || !integer(p.lineHeightPercent, 110, 240) || !integer(p.pageMargin, 0, 48) ||
      !['left-previous', 'left-next', 'buttons-only'].includes(p.touchDirection as string) || !['paged', 'scroll'].includes(p.listMode as string)) return invalid()
  return { fontSize: p.fontSize, lineHeightPercent: p.lineHeightPercent, pageMargin: p.pageMargin, touchDirection: p.touchDirection as SharedReaderPreferences['touchDirection'], listMode: p.listMode as SharedReaderPreferences['listMode'] }
}
function timestamp(value: unknown): string {
  if (typeof value !== 'string') return invalid()
  const parts = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d{1,9})?Z$/.exec(value)
  if (!parts) return invalid()
  const [year, month, day, hour, minute, second] = parts.slice(1).map(Number), leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1] || hour > 23 || minute > 59 || second > 59) return invalid()
  return value
}
export function validateReaderPreferenceView(value: unknown): ReaderPreferenceView {
  const v = object(value); keys(v, ['version', 'preferences', 'updatedAt'])
  if (!integer(v.version, 0, Number.MAX_SAFE_INTEGER)) return invalid()
  if (v.version === 0) {
    if (v.preferences !== null || v.updatedAt !== null) return invalid()
    return { version: 0, preferences: null, updatedAt: null }
  }
  return { version: v.version, preferences: validateReaderPreferences(v.preferences), updatedAt: timestamp(v.updatedAt) }
}
export function validateReaderPreferenceMutation(value: unknown): ReaderPreferenceMutation {
  const m = object(value); keys(m, ['expectedVersion', 'mutationId', 'preferences'])
  if (!integer(m.expectedVersion, 0, Number.MAX_SAFE_INTEGER - 1) || typeof m.mutationId !== 'string' || !validRecordId(m.mutationId)) return invalid()
  return { expectedVersion: m.expectedVersion, mutationId: m.mutationId, preferences: validateReaderPreferences(m.preferences) }
}
export function sameReaderPreferences(a: SharedReaderPreferences, b: SharedReaderPreferences): boolean {
  return a.fontSize === b.fontSize && a.lineHeightPercent === b.lineHeightPercent && a.pageMargin === b.pageMargin && a.touchDirection === b.touchDirection && a.listMode === b.listMode
}
export function sameReaderPreferenceView(a: ReaderPreferenceView | null, b: ReaderPreferenceView | null): boolean {
  return a === null || b === null ? a === b : a.version === b.version && a.updatedAt === b.updatedAt && (a.preferences === null || b.preferences === null ? a.preferences === b.preferences : sameReaderPreferences(a.preferences, b.preferences))
}
async function readJson(response: Response, signal: AbortSignal): Promise<unknown> {
  const maximum = 8192
  if (Number(response.headers.get('content-length')) > maximum) return invalid()
  const reader = response.body?.getReader(); if (!reader) return invalid()
  let bytes = 0, body = ''; const decoder = new TextDecoder('utf-8', { fatal: true })
  try {
    while (true) {
      if (signal.aborted) throw new ReaderPreferenceError('aborted')
      const part = await reader.read().catch(() => { throw new ReaderPreferenceError(signal.aborted ? 'aborted' : 'network') })
      if (part.done) break
      bytes += part.value.byteLength; if (bytes > maximum) return invalid()
      body += decoder.decode(part.value, { stream: true })
    }
    return JSON.parse(body + decoder.decode()) as unknown
  } catch (error) { if (error instanceof ReaderPreferenceError) throw error; return invalid() }
  finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}
export interface ReaderPreferenceClient {
  get(signal?: AbortSignal): Promise<ReaderPreferenceView>
  put(input: ReaderPreferenceMutation, signal?: AbortSignal): Promise<ReaderPreferenceView>
  close(): void
}
export function createReaderPreferenceClient(credentials: { username: string; password: string }, options: { fetch?: typeof fetch; timeoutMs?: number } = {}): ReaderPreferenceClient {
  if (!credentials.username.trim() || /[:\r\n]/.test(credentials.username) || /[\r\n]/.test(credentials.password)) throw new ReaderPreferenceError('invalid-request')
  let authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${credentials.username}:${credentials.password}`), byte => String.fromCharCode(byte)).join(''))}`, closed = false
  const transport = options.fetch ?? globalThis.fetch.bind(globalThis), timeoutMs = options.timeoutMs ?? 20_000, controllers = new Set<AbortController>()
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new ReaderPreferenceError('invalid-request')
  async function request(url: string, signal?: AbortSignal, body?: ReaderPreferenceMutation, csrf?: { headerName: string; token: string }): Promise<unknown> {
    if (closed || signal?.aborted) throw new ReaderPreferenceError('aborted')
    const controller = new AbortController(), abort = () => controller.abort(); let timedOut = false
    controllers.add(controller); signal?.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(() => { timedOut = true; abort() }, timeoutMs)
    try {
      const headers: Record<string, string> = { Authorization: authorization, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
      if (body) headers['Content-Type'] = 'application/json'
      if (csrf) headers[csrf.headerName] = csrf.token
      const response = await transport(url, { method: body ? 'PUT' : 'GET', body: body ? JSON.stringify(body) : undefined, headers, credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store', signal: controller.signal })
      if (closed || controller.signal.aborted) throw new ReaderPreferenceError('aborted')
      if (response.redirected) return invalid()
      if (response.status === 401) throw new ReaderPreferenceError('authentication')
      if (response.status === 403) throw new ReaderPreferenceError('forbidden')
      const type = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()
      if (response.ok ? type !== 'application/json' : !['application/json', 'application/problem+json'].includes(type ?? '')) throw new ReaderPreferenceError(response.status >= 500 ? 'server' : 'invalid-response')
      const value = await readJson(response, controller.signal)
      if (closed || controller.signal.aborted) throw new ReaderPreferenceError('aborted')
      if (response.ok) return value
      const problem = object(value)
      if (response.status === 409 && problem.code === 'READER_PREFERENCES_CONFLICT') throw new ReaderPreferenceError('conflict', validateReaderPreferenceView(problem.current))
      if (response.status === 409 && problem.code === 'READER_PREFERENCES_EXHAUSTED') throw new ReaderPreferenceError('exhausted')
      if (response.status === 400 && problem.code === 'READER_PREFERENCES_MUTATION_REUSED') throw new ReaderPreferenceError('mutation-reused')
      if (response.status === 400 && problem.code === 'READER_PREFERENCES_INVALID' || response.status === 413) throw new ReaderPreferenceError('invalid-request')
      if (response.status === 429 && problem.code === 'READER_PREFERENCES_LIMIT') {
        const retry = Number(response.headers.get('retry-after'))
        throw new ReaderPreferenceError('limit', undefined, Number.isSafeInteger(retry) && retry > 0 && retry <= 86400 ? retry : 60)
      }
      throw new ReaderPreferenceError(response.status >= 500 ? 'server' : 'invalid-response')
    } catch (error) {
      if (closed || signal?.aborted) throw new ReaderPreferenceError('aborted')
      if (timedOut) throw new ReaderPreferenceError('timeout')
      if (error instanceof ReaderPreferenceError) throw error
      throw new ReaderPreferenceError('network')
    } finally { clearTimeout(timer); controllers.delete(controller); signal?.removeEventListener('abort', abort) }
  }
  return {
    async get(signal) { return validateReaderPreferenceView(await request('/api/v1/reader-preferences', signal)) },
    async put(input, signal) {
      let body: ReaderPreferenceMutation
      try { body = validateReaderPreferenceMutation(input) } catch { throw new ReaderPreferenceError('invalid-request') }
      const token = object(await request('/api/v1/csrf', signal))
      if (typeof token.headerName !== 'string' || !['x-csrf-token', 'x-xsrf-token'].includes(token.headerName.toLowerCase()) || typeof token.token !== 'string' || !token.token || token.token.length > 4096 || /[\r\n]/.test(token.token)) return invalid()
      const view = validateReaderPreferenceView(await request('/api/v1/reader-preferences', signal, body, { headerName: token.headerName, token: token.token }))
      if (view.version !== body.expectedVersion + 1 || !view.preferences || !sameReaderPreferences(view.preferences, body.preferences)) return invalid()
      return view
    },
    close() { closed = true; authorization = ''; controllers.forEach(controller => controller.abort()); controllers.clear() },
  }
}
