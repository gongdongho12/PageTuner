import { ApiError } from './errors'
import { validatePage, validateTranslation, validRecordId } from './validation'
import type { TranslationPage, TranslationResponse } from './types'

export { ApiError } from './errors'
export type { ApiErrorKind } from './errors'
export type { TranslationPage, TranslationResponse, TranslationSummary } from './types'

export interface TranslationClient {
  list(page?: number, signal?: AbortSignal): Promise<TranslationPage>
  get(recordId: string, signal?: AbortSignal): Promise<TranslationResponse>
}

export interface TranslationClientOptions {
  fetch?: typeof globalThis.fetch
  timeoutMs?: number
}

const pageSize = 12
const maxResponseBytes = 4 * 1024 * 1024

function basicAuth(username: string, password: string): string {
  if (!username.trim() || /[:\r\n]/.test(username) || /[\r\n]/.test(password)) {
    throw new ApiError('invalid-request', '계정 이름과 비밀번호 형식을 확인해 주세요.')
  }
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  return `Basic ${btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''))}`
}

function statusError(status: number): ApiError {
  if (status === 401) return new ApiError('authentication', '계정 이름 또는 비밀번호를 확인해 주세요.', status)
  if (status === 403) return new ApiError('forbidden', '이 번역에 접근할 권한이 없습니다.', status)
  if (status === 404) return new ApiError('not-found', '번역을 찾을 수 없습니다.', status)
  if (status === 409) return new ApiError('conflict', '이 번역은 현재 복원할 수 없습니다. 앱에서 다시 저장해 주세요.', status)
  if (status >= 400 && status < 500) return new ApiError('invalid-request', '요청을 처리할 수 없습니다.', status)
  return new ApiError('server', '서버에서 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.', status)
}

async function responseJson(response: Response, signal: AbortSignal): Promise<unknown> {
  if (!response.headers.get('content-type')?.toLowerCase().startsWith('application/json')) {
    throw new ApiError('invalid-response', '서버가 올바른 JSON 응답을 보내지 않았습니다.')
  }
  if (Number(response.headers.get('content-length')) > maxResponseBytes) {
    throw new ApiError('invalid-response', '번역 응답이 저장 가능한 크기를 초과했습니다.')
  }
  const reader = response.body?.getReader()
  if (!reader) throw new ApiError('invalid-response', '서버 응답이 비어 있습니다.')
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let text = ''
  let bytes = 0
  try {
    while (true) {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      const part = await reader.read()
      if (part.done) break
      bytes += part.value.byteLength
      if (bytes > maxResponseBytes) throw new ApiError('invalid-response', '번역 응답이 저장 가능한 크기를 초과했습니다.')
      text += decoder.decode(part.value, { stream: true })
    }
    text += decoder.decode()
    return JSON.parse(text) as unknown
  } catch (error) {
    if (signal.aborted || error instanceof ApiError) throw error
    throw new ApiError('invalid-response', '서버 응답을 읽을 수 없습니다.')
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}

/** Same-origin requests only; the Basic header stays in this closure and is never persisted. */
export function createTranslationClient(
  credentials: { username: string; password: string },
  options: TranslationClientOptions = {},
): TranslationClient {
  const authorization = basicAuth(credentials.username, credentials.password)
  const fetchRequest = options.fetch ?? globalThis.fetch.bind(globalThis)
  const timeoutMs = options.timeoutMs ?? 15_000
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new ApiError('invalid-request', '요청 제한 시간이 올바르지 않습니다.')

  async function request<T>(path: string, validate: (value: unknown) => Promise<T>, signal?: AbortSignal): Promise<T> {
    if (signal?.aborted) throw new ApiError('aborted', '요청이 취소되었습니다.')
    const controller = new AbortController()
    let timedOut = false
    const abort = () => controller.abort()
    signal?.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs)
    try {
      const response = await fetchRequest(path, {
        method: 'GET', credentials: 'omit', redirect: 'error', mode: 'same-origin', cache: 'no-store',
        headers: { Authorization: authorization, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }, signal: controller.signal,
      })
      if (response.redirected) throw new ApiError('network', '서버 주소가 변경되어 요청을 중단했습니다.')
      if (!response.ok) throw statusError(response.status)
      const value = await validate(await responseJson(response, controller.signal))
      if (controller.signal.aborted) throw new DOMException('Aborted', 'AbortError')
      return value
    } catch (error) {
      if (controller.signal.aborted) throw new ApiError(timedOut ? 'timeout' : 'aborted', timedOut ? '응답 시간이 초과되었습니다. 다시 시도해 주세요.' : '요청이 취소되었습니다.')
      if (error instanceof ApiError) throw error
      throw new ApiError('network', '서버에 연결할 수 없습니다. 연결 상태를 확인해 주세요.')
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
    }
  }

  return {
    async list(page = 0, signal) {
      if (!Number.isSafeInteger(page) || page < 0) throw new ApiError('invalid-request', '페이지 번호가 올바르지 않습니다.')
      const result = await request(`/api/v1/translations?page=${page}&size=${pageSize}`, validatePage, signal)
      if (result.page !== page || result.size !== pageSize) throw new ApiError('invalid-response', '요청한 페이지와 응답이 일치하지 않습니다.')
      return result
    },
    async get(recordId, signal) {
      if (!validRecordId(recordId)) throw new ApiError('invalid-request', '번역 식별자가 올바르지 않습니다.')
      const result = await request(`/api/v1/translations/${encodeURIComponent(recordId)}`, validateTranslation, signal)
      if (result.recordId.toLowerCase() !== recordId.toLowerCase()) throw new ApiError('invalid-response', '요청한 번역과 응답이 일치하지 않습니다.')
      return result
    },
  }
}
