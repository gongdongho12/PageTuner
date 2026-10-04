import { validRecordId } from './validation'
import type { components } from '../generated/libraryOrganization'

export type LibraryOrganization = components['schemas']['LibraryOrganization']
export type LibraryOrganizationView = components['schemas']['LibraryOrganizationView']
export type LibraryOrganizationMutation = components['schemas']['PutLibraryOrganizationRequest']
export type LibraryOrganizationScope = Pick<LibraryOrganizationView, 'kind' | 'recordId'>
export type LibraryOrganizationErrorCode = 'authentication' | 'forbidden' | 'conflict' | 'choice-stale' | 'invalid-request' | 'mutation-reused' | 'limit' | 'exhausted' | 'not-found' | 'server' | 'network' | 'timeout' | 'aborted' | 'invalid-response' | 'storage'
export class LibraryOrganizationError extends Error {
  constructor(readonly code: LibraryOrganizationErrorCode, readonly current?: LibraryOrganizationView, readonly retryAfterSeconds?: number) {
    super(`Library organization synchronization: ${code}`); this.name = 'LibraryOrganizationError'
  }
}
const invalid = (): never => { throw new LibraryOrganizationError('invalid-response') }
function object(value: unknown): Record<string, unknown> { return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid() }
function keys(value: Record<string, unknown>, names: string[]) { if (Object.keys(value).length !== names.length || names.some(name => !Object.hasOwn(value, name))) invalid() }
const integer = (value: unknown, minimum: number, maximum: number): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
export function validateLibraryOrganization(value: unknown): LibraryOrganization {
  const p = object(value); keys(p, ['folder', 'tags', 'favorite'])
  function text(input: unknown, minimum: number, maximum: number): string {
    if (typeof input !== 'string' || input.length < minimum || input.length > maximum || input !== input.trim() || /[\u0000-\u001f\u007f-\u009f]/.test(input)) return invalid()
    for (let i = 0; i < input.length; i++) {
      const code = input.charCodeAt(i)
      if (code >= 0xd800 && code <= 0xdbff) { const next = input.charCodeAt(++i); if (!(next >= 0xdc00 && next <= 0xdfff)) return invalid() }
      else if (code >= 0xdc00 && code <= 0xdfff) return invalid()
    }
    return input
  }
  if (!Array.isArray(p.tags) || p.tags.length > 32 || typeof p.favorite !== 'boolean') return invalid()
  const tags = p.tags.map(tag => text(tag, 1, 60))
  if (new Set(tags).size !== tags.length) return invalid()
  return { folder: text(p.folder, 0, 200), tags, favorite: p.favorite }
}
export function validateLibraryOrganizationScope(value: unknown): LibraryOrganizationScope {
  const scope = object(value); keys(scope, ['kind', 'recordId'])
  if (!['ORIGINAL', 'TRANSLATION'].includes(scope.kind as string) || !validRecordId(scope.recordId)) return invalid()
  return { kind: scope.kind as LibraryOrganizationScope['kind'], recordId: scope.recordId.toLowerCase() }
}
export function sameLibraryOrganizationScope(a: LibraryOrganizationScope, b: LibraryOrganizationScope): boolean { return a.kind === b.kind && a.recordId.toLowerCase() === b.recordId.toLowerCase() }
function timestamp(value: unknown): string {
  if (typeof value !== 'string') return invalid()
  const parts = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d{1,9})?Z$/.exec(value)
  if (!parts) return invalid()
  const [year, month, day, hour, minute, second] = parts.slice(1).map(Number), leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1] || hour > 23 || minute > 59 || second > 59) return invalid()
  return value
}
export function validateLibraryOrganizationView(value: unknown): LibraryOrganizationView {
  const v = object(value); keys(v, ['kind', 'recordId', 'version', 'organization', 'updatedAt'])
  const scope = validateLibraryOrganizationScope({ kind: v.kind, recordId: v.recordId })
  if (!integer(v.version, 0, Number.MAX_SAFE_INTEGER)) return invalid()
  if (v.version === 0) {
    if (v.organization !== null || v.updatedAt !== null) return invalid()
    return { ...scope, version: 0, organization: null, updatedAt: null }
  }
  return { ...scope, version: v.version, organization: validateLibraryOrganization(v.organization), updatedAt: timestamp(v.updatedAt) }
}
export function validateLibraryOrganizationMutation(value: unknown): LibraryOrganizationMutation {
  const m = object(value); keys(m, ['expectedVersion', 'mutationId', 'organization'])
  if (!integer(m.expectedVersion, 0, Number.MAX_SAFE_INTEGER - 1) || typeof m.mutationId !== 'string' || !validRecordId(m.mutationId)) return invalid()
  return { expectedVersion: m.expectedVersion, mutationId: m.mutationId, organization: validateLibraryOrganization(m.organization) }
}
export function sameLibraryOrganization(a: LibraryOrganization, b: LibraryOrganization): boolean {
  return a.folder === b.folder && a.favorite === b.favorite && a.tags.length === b.tags.length && a.tags.every((tag, index) => tag === b.tags[index])
}
export function sameLibraryOrganizationView(a: LibraryOrganizationView | null, b: LibraryOrganizationView | null): boolean {
  return a === null || b === null ? a === b : sameLibraryOrganizationScope(a, b) && a.version === b.version && a.updatedAt === b.updatedAt && (a.organization === null || b.organization === null ? a.organization === b.organization : sameLibraryOrganization(a.organization, b.organization))
}
async function readJson(response: Response, signal: AbortSignal): Promise<unknown> {
  const maximum = 8192
  if (Number(response.headers.get('content-length')) > maximum) return invalid()
  const reader = response.body?.getReader(); if (!reader) return invalid()
  let bytes = 0, body = ''; const decoder = new TextDecoder('utf-8', { fatal: true })
  try {
    while (true) {
      if (signal.aborted) throw new LibraryOrganizationError('aborted')
      const part = await reader.read().catch(() => { throw new LibraryOrganizationError(signal.aborted ? 'aborted' : 'network') })
      if (part.done) break
      bytes += part.value.byteLength; if (bytes > maximum) return invalid()
      body += decoder.decode(part.value, { stream: true })
    }
    return JSON.parse(body + decoder.decode()) as unknown
  } catch (error) { if (error instanceof LibraryOrganizationError) throw error; return invalid() }
  finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}
export interface LibraryOrganizationClient {
  get(scope: LibraryOrganizationScope, signal?: AbortSignal): Promise<LibraryOrganizationView>
  put(scope: LibraryOrganizationScope, input: LibraryOrganizationMutation, signal?: AbortSignal): Promise<LibraryOrganizationView>
  /** Restore the maximum persisted account deadline before starting any document controllers. */
  deferWritesUntil?(epochMs: number): void
  close(): void
}
export function createLibraryOrganizationClient(credentials: { username: string; password: string }, options: { fetch?: typeof fetch; timeoutMs?: number } = {}): LibraryOrganizationClient {
  if (!credentials.username.trim() || /[:\r\n]/.test(credentials.username) || /[\r\n]/.test(credentials.password)) throw new LibraryOrganizationError('invalid-request')
  let authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${credentials.username}:${credentials.password}`), byte => String.fromCharCode(byte)).join(''))}`, closed = false
  const transport = options.fetch ?? globalThis.fetch.bind(globalThis), timeoutMs = options.timeoutMs ?? 20_000, controllers = new Set<AbortController>()
  let writeAfter = 0
  function deferWritesUntil(epochMs: number) { if (Number.isSafeInteger(epochMs) && epochMs >= 0) writeAfter = Math.max(writeAfter, epochMs) }
  function checkWriteLimit() { if (writeAfter > Date.now()) throw new LibraryOrganizationError('limit', undefined, Math.ceil((writeAfter - Date.now()) / 1000)) }
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new LibraryOrganizationError('invalid-request')
  async function request(url: string, signal?: AbortSignal, body?: LibraryOrganizationMutation, csrf?: { headerName: string; token: string }, scope?: LibraryOrganizationScope): Promise<unknown> {
    if (closed || signal?.aborted) throw new LibraryOrganizationError('aborted')
    const controller = new AbortController(), abort = () => controller.abort(); let timedOut = false
    controllers.add(controller); signal?.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(() => { timedOut = true; abort() }, timeoutMs)
    try {
      const headers: Record<string, string> = { Authorization: authorization, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
      if (body) headers['Content-Type'] = 'application/json'
      if (csrf) headers[csrf.headerName] = csrf.token
      const response = await transport(url, { method: body ? 'PUT' : 'GET', body: body ? JSON.stringify(body) : undefined, headers, credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store', signal: controller.signal })
      if (closed || controller.signal.aborted) throw new LibraryOrganizationError('aborted')
      if (response.redirected) return invalid()
      if (response.status === 401) throw new LibraryOrganizationError('authentication')
      if (response.status === 403) throw new LibraryOrganizationError('forbidden')
      const type = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()
      if (response.ok ? type !== 'application/json' : !['application/json', 'application/problem+json'].includes(type ?? '')) throw new LibraryOrganizationError(response.status >= 500 ? 'server' : 'invalid-response')
      const value = await readJson(response, controller.signal)
      if (closed || controller.signal.aborted) throw new LibraryOrganizationError('aborted')
      if (response.ok) return value
      const problem = object(value)
      if (response.status === 409 && problem.code === 'LIBRARY_ORGANIZATION_CONFLICT') {
        const current = validateLibraryOrganizationView(problem.current)
        if (!scope || !sameLibraryOrganizationScope(scope, current)) return invalid()
        throw new LibraryOrganizationError('conflict', current)
      }
      if (response.status === 404 && problem.code === 'LIBRARY_ORGANIZATION_NOT_FOUND') throw new LibraryOrganizationError('not-found')
      if (response.status === 409 && problem.code === 'LIBRARY_ORGANIZATION_EXHAUSTED') throw new LibraryOrganizationError('exhausted')
      if (response.status === 400 && problem.code === 'LIBRARY_ORGANIZATION_MUTATION_REUSED') throw new LibraryOrganizationError('mutation-reused')
      if (response.status === 400 && problem.code === 'LIBRARY_ORGANIZATION_INVALID' || response.status === 413) throw new LibraryOrganizationError('invalid-request')
      if (response.status === 429 && problem.code === 'LIBRARY_ORGANIZATION_LIMIT') {
        const retry = Number(response.headers.get('retry-after'))
        const seconds = Number.isSafeInteger(retry) && retry > 0 && retry <= 86400 ? retry : 60
        deferWritesUntil(Date.now() + seconds * 1000)
        throw new LibraryOrganizationError('limit', undefined, seconds)
      }
      throw new LibraryOrganizationError(response.status >= 500 ? 'server' : 'invalid-response')
    } catch (error) {
      if (closed || signal?.aborted) throw new LibraryOrganizationError('aborted')
      if (timedOut) throw new LibraryOrganizationError('timeout')
      if (error instanceof LibraryOrganizationError) throw error
      throw new LibraryOrganizationError('network')
    } finally { clearTimeout(timer); controllers.delete(controller); signal?.removeEventListener('abort', abort) }
  }
  return {
    async get(inputScope, signal) {
      let scope: LibraryOrganizationScope
      try { scope = validateLibraryOrganizationScope(inputScope) } catch { throw new LibraryOrganizationError('invalid-request') }
      const view = validateLibraryOrganizationView(await request(`/api/v1/library-organization/${scope.kind}/${scope.recordId}`, signal, undefined, undefined, scope))
      if (!sameLibraryOrganizationScope(scope, view)) return invalid()
      return view
    },
    async put(inputScope, input, signal) {
      let body: LibraryOrganizationMutation
      let scope: LibraryOrganizationScope
      try { body = validateLibraryOrganizationMutation(input); scope = validateLibraryOrganizationScope(inputScope); if (new TextEncoder().encode(JSON.stringify(body)).byteLength > 8192) throw new Error() } catch { throw new LibraryOrganizationError('invalid-request') }
      if (closed || signal?.aborted) throw new LibraryOrganizationError('aborted')
      checkWriteLimit()
      const token = object(await request('/api/v1/csrf', signal))
      if (typeof token.headerName !== 'string' || !['x-csrf-token', 'x-xsrf-token'].includes(token.headerName.toLowerCase()) || typeof token.token !== 'string' || !token.token || token.token.length > 4096 || /[\r\n]/.test(token.token)) return invalid()
      checkWriteLimit()
      const view = validateLibraryOrganizationView(await request(`/api/v1/library-organization/${scope.kind}/${scope.recordId}`, signal, body, { headerName: token.headerName, token: token.token }, scope))
      if (!sameLibraryOrganizationScope(scope, view) || view.version !== body.expectedVersion + 1 || !view.organization || !sameLibraryOrganization(view.organization, body.organization)) return invalid()
      return view
    },
    deferWritesUntil,
    close() { closed = true; authorization = ''; controllers.forEach(controller => controller.abort()); controllers.clear() },
  }
}
