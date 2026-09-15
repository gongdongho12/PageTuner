import { validRecordId } from './validation'
import type { components } from '../generated/readingProgress'

export type ReadingProgressView = components['schemas']['ReadingProgressView']
export type ReadingProgressIdentity = Pick<ReadingProgressView, 'kind' | 'recordId'>
export type ReadingProgressAnchor = components['schemas']['ReadingProgressAnchor']
export type ReadingProgressMutation = components['schemas']['PutReadingProgressRequest']
export type ReadingProgressErrorCode = 'authentication' | 'forbidden' | 'not-found' | 'conflict' | 'invalid-request' | 'mutation-reused' | 'limit' | 'server' | 'network' | 'timeout' | 'aborted' | 'invalid-response' | 'storage'

const messages: Record<ReadingProgressErrorCode, string> = {
  authentication: '읽기 위치를 동기화하려면 다시 로그인해 주세요.',
  forbidden: '읽기 위치를 동기화할 권한을 확인할 수 없습니다.',
  'not-found': '동기화할 서버 문서를 찾을 수 없습니다.',
  conflict: '다른 기기에서 읽기 위치가 변경되었습니다. 사용할 위치를 선택해 주세요.',
  'invalid-request': '읽기 위치를 확인해 주세요.',
  'mutation-reused': '읽기 위치 변경 요청을 확인할 수 없습니다.',
  limit: '동기화 요청이 많습니다. 잠시 후 다시 시도해 주세요.',
  server: '서버에서 읽기 위치를 저장하지 못했습니다.',
  network: '서버에 연결할 수 없습니다. 기기에 보관한 위치는 다시 연결할 때 동기화됩니다.',
  timeout: '읽기 위치 동기화 시간이 초과되었습니다. 다시 시도해 주세요.',
  aborted: '읽기 위치 동기화가 취소되었습니다.',
  'invalid-response': '서버의 읽기 위치 응답을 확인할 수 없습니다.',
  storage: '이 기기에 읽기 위치 변경을 보관하지 못했습니다.',
}
export class ReadingProgressError extends Error {
  constructor(readonly code: ReadingProgressErrorCode, readonly current?: ReadingProgressView, readonly retryAfterSeconds?: number) {
    super(messages[code]); this.name = 'ReadingProgressError'
  }
}
export function validReadingProgressIdentity(value: ReadingProgressIdentity): boolean {
  return !!value && ['ORIGINAL', 'TRANSLATION'].includes(value.kind) && typeof value.recordId === 'string' && validRecordId(value.recordId)
}
export function validReadingProgressAnchor(value: unknown): value is ReadingProgressAnchor {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const anchor = value as ReadingProgressAnchor
  return typeof anchor.paragraphId === 'string' && !!anchor.paragraphId.trim() && anchor.paragraphId.length <= 200 && !/[\u0000-\u001f\u007f-\u009f]/.test(anchor.paragraphId) &&
    Number.isSafeInteger(anchor.characterOffset) && anchor.characterOffset >= 0 && anchor.characterOffset <= 2_147_483_647
}
export function sameReadingProgressAnchor(a: ReadingProgressAnchor | null, b: ReadingProgressAnchor | null): boolean {
  return a === b || (!!a && !!b && a.paragraphId === b.paragraphId && a.characterOffset === b.characterOffset)
}
export function validateReadingProgressView(value: unknown, identity?: ReadingProgressIdentity): ReadingProgressView {
  const invalid = () => { throw new ReadingProgressError('invalid-response') }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  const view = value as ReadingProgressView
  if (!validReadingProgressIdentity(view) || (identity && (view.kind !== identity.kind || view.recordId !== identity.recordId)) ||
      !Number.isSafeInteger(view.version) || view.version < 0 ||
      (view.version === 0 ? view.anchor !== null || view.updatedAt !== null : !validReadingProgressAnchor(view.anchor) ||
        typeof view.updatedAt !== 'string' || view.updatedAt.length > 64 || !/^\d{4}-\d\d-\d\dT.+(?:Z|[+-]\d\d:\d\d)$/.test(view.updatedAt) || !Number.isFinite(Date.parse(view.updatedAt)))) return invalid()
  return { kind: view.kind, recordId: view.recordId, version: view.version,
    anchor: view.anchor ? { paragraphId: view.anchor.paragraphId, characterOffset: view.anchor.characterOffset } : null, updatedAt: view.updatedAt }
}
export function validReadingProgressMutation(value: ReadingProgressMutation): boolean {
  return !!value && Number.isSafeInteger(value.expectedVersion) && value.expectedVersion >= 0 && value.expectedVersion < Number.MAX_SAFE_INTEGER &&
    typeof value.mutationId === 'string' && validRecordId(value.mutationId) && validReadingProgressAnchor(value.anchor)
}

async function readJson(response: Response, signal: AbortSignal): Promise<unknown> {
  if (Number(response.headers.get('content-length')) > 8192) throw new ReadingProgressError('invalid-response')
  const reader = response.body?.getReader()
  if (!reader) throw new ReadingProgressError('invalid-response')
  let bytes = 0, text = ''
  const decoder = new TextDecoder('utf-8', { fatal: true })
  try {
    while (true) {
      if (signal.aborted) throw new ReadingProgressError('aborted')
      const chunk = await reader.read()
      if (chunk.done) break
      bytes += chunk.value.byteLength
      if (bytes > 8192) throw new ReadingProgressError('invalid-response')
      text += decoder.decode(chunk.value, { stream: true })
    }
    return JSON.parse(text + decoder.decode()) as unknown
  } catch (error) {
    if (error instanceof ReadingProgressError) throw error
    throw new ReadingProgressError('invalid-response')
  } finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}

export interface ReadingProgressClient {
  get(identity: ReadingProgressIdentity, signal?: AbortSignal): Promise<ReadingProgressView>
  put(identity: ReadingProgressIdentity, input: ReadingProgressMutation, signal?: AbortSignal): Promise<ReadingProgressView>
  close(): void
}

/** Credentials exist only in this closure; all requests remain on the current origin. */
export function createReadingProgressClient(
  credentials: { username: string; password: string },
  options: { fetch?: typeof fetch; timeoutMs?: number } = {},
): ReadingProgressClient {
  if (!credentials.username.trim() || /[:\r\n]/.test(credentials.username) || /[\r\n]/.test(credentials.password)) throw new ReadingProgressError('invalid-request')
  let authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${credentials.username}:${credentials.password}`), byte => String.fromCharCode(byte)).join(''))}`
  let closed = false
  const controllers = new Set<AbortController>(), transport = options.fetch ?? globalThis.fetch.bind(globalThis)
  const timeoutMs = options.timeoutMs ?? 20_000
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new ReadingProgressError('invalid-request')
  async function request(path: string, signal?: AbortSignal, input?: ReadingProgressMutation, csrf?: { headerName: string; token: string }, identity?: ReadingProgressIdentity): Promise<unknown> {
    if (closed || signal?.aborted) throw new ReadingProgressError('aborted')
    const controller = new AbortController(), abort = () => controller.abort()
    controllers.add(controller); signal?.addEventListener('abort', abort, { once: true })
    let timedOut = false
    const timer = setTimeout(() => { timedOut = true; abort() }, timeoutMs)
    try {
      const headers: Record<string, string> = { Authorization: authorization, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
      if (csrf) headers[csrf.headerName] = csrf.token
      if (input) headers['Content-Type'] = 'application/json'
      const response = await transport(path, { method: input ? 'PUT' : 'GET', body: input ? JSON.stringify(input) : undefined,
        headers, credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store', signal: controller.signal })
      if (closed || controller.signal.aborted) throw new ReadingProgressError('aborted')
      if (response.redirected) throw new ReadingProgressError('invalid-response')
      if (response.status === 401) throw new ReadingProgressError('authentication')
      if (response.status === 403) throw new ReadingProgressError('forbidden')
      if (response.status === 404) throw new ReadingProgressError('not-found')
      const contentType = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()
      if (!response.ok && !['application/json', 'application/problem+json'].includes(contentType ?? '')) throw new ReadingProgressError(response.status >= 500 ? 'server' : 'invalid-response')
      if (response.ok && contentType !== 'application/json') throw new ReadingProgressError('invalid-response')
      const value = await readJson(response, controller.signal)
      if (closed || controller.signal.aborted) throw new ReadingProgressError('aborted')
      if (response.ok) return value
      const problem = value && typeof value === 'object' ? value as Record<string, unknown> : {}
      if (response.status === 409 && problem.code === 'READING_PROGRESS_CONFLICT' && identity) throw new ReadingProgressError('conflict', validateReadingProgressView(problem.current, identity))
      if (response.status === 400 && problem.code === 'READING_PROGRESS_MUTATION_REUSED') throw new ReadingProgressError('mutation-reused')
      if (response.status === 400 && problem.code === 'READING_PROGRESS_INVALID' || response.status === 413) throw new ReadingProgressError('invalid-request')
      if (response.status === 429 && problem.code === 'READING_PROGRESS_LIMIT') {
        const retry = Number(response.headers.get('retry-after'))
        throw new ReadingProgressError('limit', undefined, Number.isSafeInteger(retry) && retry > 0 && retry <= 86400 ? retry : undefined)
      }
      throw new ReadingProgressError(response.status >= 500 ? 'server' : 'invalid-response')
    } catch (error) {
      if (closed || signal?.aborted) throw new ReadingProgressError('aborted')
      if (timedOut) throw new ReadingProgressError('timeout')
      if (error instanceof ReadingProgressError) throw error
      throw new ReadingProgressError('network')
    } finally { clearTimeout(timer); controllers.delete(controller); signal?.removeEventListener('abort', abort) }
  }
  function path(identity: ReadingProgressIdentity) {
    if (!validReadingProgressIdentity(identity)) throw new ReadingProgressError('invalid-request')
    return `/api/v1/reading-progress/${identity.kind}/${identity.recordId}`
  }
  return {
    async get(identity, signal) { return validateReadingProgressView(await request(path(identity), signal, undefined, undefined, identity), identity) },
    async put(identity, input, signal) {
      const url = path(identity)
      if (!validReadingProgressMutation(input)) throw new ReadingProgressError('invalid-request')
      // Project known fields so callers cannot accidentally persist extra document or credential data.
      const body = { expectedVersion: input.expectedVersion, mutationId: input.mutationId, anchor: { paragraphId: input.anchor.paragraphId, characterOffset: input.anchor.characterOffset } }
      const value = await request('/api/v1/csrf', signal) as Record<string, unknown>
      if (!value || typeof value.headerName !== 'string' || !['x-csrf-token', 'x-xsrf-token'].includes(value.headerName.toLowerCase()) ||
          typeof value.token !== 'string' || !value.token || value.token.length > 4096 || /[\r\n]/.test(value.token)) throw new ReadingProgressError('invalid-response')
      const view = validateReadingProgressView(await request(url, signal, body, { headerName: value.headerName, token: value.token }, identity), identity)
      if (view.version !== input.expectedVersion + 1 || !sameReadingProgressAnchor(view.anchor, input.anchor)) throw new ReadingProgressError('invalid-response')
      return view
    },
    close() { closed = true; authorization = ''; controllers.forEach(controller => controller.abort()); controllers.clear() },
  }
}
