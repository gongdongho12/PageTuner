import { sameLibraryIdentity, validateLibraryIdentity, type LibraryDocumentIdentity } from './libraryIdentity'
import { validRecordId } from './validation'

export type LibraryIdentityErrorCode = 'authentication' | 'forbidden' | 'not-found' | 'mismatch' | 'unavailable' | 'invalid-request' | 'invalid-response' | 'server' | 'network' | 'timeout' | 'aborted'
export class LibraryIdentityError extends Error { constructor(readonly code: LibraryIdentityErrorCode) { super(`Library identity verification: ${code}`); this.name = 'LibraryIdentityError' } }
export type LibraryIdentityResult = { kind: LibraryDocumentIdentity['kind']; recordId: string; verified: true; identity: LibraryDocumentIdentity }
export interface LibraryIdentityClient { verify(recordId: string, identity: LibraryDocumentIdentity, signal?: AbortSignal): Promise<LibraryIdentityResult>; close(): void }
const invalid = (): never => { throw new LibraryIdentityError('invalid-response') }
const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid()
async function readJson(response: Response, signal: AbortSignal): Promise<unknown> {
  const maximum = 64 * 1024
  if (Number(response.headers.get('content-length')) > maximum) return invalid()
  const reader = response.body?.getReader(); if (!reader) return invalid()
  const decoder = new TextDecoder('utf-8', { fatal: true }); let size = 0, body = ''
  try {
    while (true) { if (signal.aborted) throw new LibraryIdentityError('aborted'); const part = await reader.read(); if (part.done) break; size += part.value.byteLength; if (size > maximum) return invalid(); body += decoder.decode(part.value, { stream: true }) }
    return JSON.parse(body + decoder.decode()) as unknown
  } catch (error) { if (error instanceof LibraryIdentityError) throw error; return invalid() }
  finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}
export function createLibraryIdentityClient(credentials: { username: string; password: string }, options: { fetch?: typeof fetch; timeoutMs?: number } = {}): LibraryIdentityClient {
  if (!credentials.username.trim() || /[:\r\n]/.test(credentials.username) || /[\r\n]/.test(credentials.password)) throw new LibraryIdentityError('invalid-request')
  let authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${credentials.username}:${credentials.password}`), byte => String.fromCharCode(byte)).join(''))}`, closed = false
  const transport = options.fetch ?? globalThis.fetch.bind(globalThis), timeoutMs = options.timeoutMs ?? 20_000, controllers = new Set<AbortController>()
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new LibraryIdentityError('invalid-request')
  async function request(path: string, body?: unknown, csrf?: { headerName: string; token: string }, signal?: AbortSignal) {
    if (closed || signal?.aborted) throw new LibraryIdentityError('aborted')
    const controller = new AbortController(), abort = () => controller.abort(); let timedOut = false
    controllers.add(controller); signal?.addEventListener('abort', abort, { once: true }); const timer = setTimeout(() => { timedOut = true; abort() }, timeoutMs)
    try {
      const headers: Record<string, string> = { Authorization: authorization, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
      if (body) headers['Content-Type'] = 'application/json'
      if (csrf) headers[csrf.headerName] = csrf.token
      const response = await transport(path, { method: body ? 'POST' : 'GET', body: body ? JSON.stringify(body) : undefined, headers, signal: controller.signal, credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store' })
      if (closed || controller.signal.aborted) throw new LibraryIdentityError('aborted')
      if (response.redirected) return invalid()
      if (response.status === 401) throw new LibraryIdentityError('authentication')
      if (response.status === 403) throw new LibraryIdentityError('forbidden')
      const type = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()
      if (response.ok ? type !== 'application/json' : !['application/json', 'application/problem+json'].includes(type ?? '')) throw new LibraryIdentityError(response.status >= 500 ? 'server' : 'invalid-response')
      const value = await readJson(response, controller.signal)
      if (closed || controller.signal.aborted) throw new LibraryIdentityError('aborted')
      if (response.ok) return value
      const code = object(value).code
      if (response.status === 404 && code === 'LIBRARY_IDENTITY_NOT_FOUND') throw new LibraryIdentityError('not-found')
      if (response.status === 409 && code === 'LIBRARY_IDENTITY_MISMATCH') throw new LibraryIdentityError('mismatch')
      if (response.status === 409 && code === 'LIBRARY_IDENTITY_UNAVAILABLE') throw new LibraryIdentityError('unavailable')
      if (response.status === 413 || response.status === 400 && code === 'LIBRARY_IDENTITY_INVALID') throw new LibraryIdentityError('invalid-request')
      throw new LibraryIdentityError(response.status >= 500 ? 'server' : 'invalid-response')
    } catch (error) {
      if (closed || signal?.aborted) throw new LibraryIdentityError('aborted')
      if (timedOut) throw new LibraryIdentityError('timeout')
      if (error instanceof LibraryIdentityError) throw error
      throw new LibraryIdentityError('network')
    } finally { clearTimeout(timer); controllers.delete(controller); signal?.removeEventListener('abort', abort) }
  }
  return {
    async verify(recordId, input, signal) {
      let identity: LibraryDocumentIdentity
      try { identity = validateLibraryIdentity(input); if (!validRecordId(recordId) || new TextEncoder().encode(JSON.stringify({ kind: identity.kind, recordId, identity })).length > 64 * 1024) throw new Error() } catch { throw new LibraryIdentityError('invalid-request') }
      recordId = recordId.toLowerCase()
      const token = object(await request('/api/v1/csrf', undefined, undefined, signal))
      if (typeof token.headerName !== 'string' || !['x-csrf-token', 'x-xsrf-token'].includes(token.headerName.toLowerCase()) || typeof token.token !== 'string' || !token.token || token.token.length > 4096 || /[\r\n]/.test(token.token)) return invalid()
      const result = object(await request('/api/v1/library-identity/verify', { kind: identity.kind, recordId, identity }, { headerName: token.headerName, token: token.token }, signal))
      let accepted: LibraryDocumentIdentity
      try { accepted = validateLibraryIdentity(result.identity) } catch { return invalid() }
      if (Object.keys(result).length !== 4 || result.verified !== true || result.kind !== identity.kind || result.recordId !== recordId || !sameLibraryIdentity(accepted, identity)) return invalid()
      return { kind: identity.kind, recordId, verified: true, identity: accepted }
    },
    close() { closed = true; authorization = ''; controllers.forEach(controller => controller.abort()); controllers.clear() },
  }
}
