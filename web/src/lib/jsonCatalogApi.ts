import { ApiError } from './errors'
import { MAX_LOCAL_FILE_BYTES } from './localDocuments'
import { publicCatalogUrl, validateJsonCatalog, type JsonCatalog } from './jsonCatalog'

export interface JsonCatalogClient {
  catalog(url: string, signal?: AbortSignal): Promise<JsonCatalog>
  file(url: string, signal?: AbortSignal): Promise<Uint8Array>
}

async function boundedBytes(response: Response, max: number, signal: AbortSignal): Promise<Uint8Array> {
  if (Number(response.headers.get('content-length')) > max) throw new ApiError('invalid-response', '카탈로그 응답이 허용 크기를 초과했습니다.')
  const reader = response.body?.getReader()
  if (!reader) throw new ApiError('invalid-response', '카탈로그 응답이 비어 있습니다.')
  const chunks: Uint8Array[] = []; let length = 0
  try {
    while (true) {
      signal.throwIfAborted()
      const { value, done } = await reader.read()
      if (done) break
      length += value.length
      if (length > max) throw new ApiError('invalid-response', '카탈로그 응답이 허용 크기를 초과했습니다.')
      chunks.push(value)
    }
    signal.throwIfAborted()
    if (!length) throw new ApiError('invalid-response', '카탈로그 응답이 비어 있습니다.')
    const bytes = new Uint8Array(length); let at = 0
    for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length }
    return bytes
  } finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}

/** Credentials stay in this closure; remote URLs are data sent only to the same-origin server. */
export function createJsonCatalogClient(credentials: { username: string; password: string }, options: { fetch?: typeof fetch; timeoutMs?: number } = {}): JsonCatalogClient {
  if (!credentials.username.trim() || /[:\r\n]/.test(credentials.username) || /[\r\n]/.test(credentials.password)) throw new ApiError('invalid-request', '계정 이름과 비밀번호 형식을 확인해 주세요.')
  const authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${credentials.username}:${credentials.password}`), byte => String.fromCharCode(byte)).join(''))}`
  const fetchRequest = options.fetch ?? globalThis.fetch.bind(globalThis), timeoutMs = options.timeoutMs ?? 70_000
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new ApiError('invalid-request', '요청 제한 시간이 올바르지 않습니다.')
  async function request(url: string, file: boolean, signal?: AbortSignal) {
    const target = publicCatalogUrl(url)
    if (signal?.aborted) throw new ApiError('aborted', '요청이 취소되었습니다.')
    const controller = new AbortController(); let timedOut = false
    const cancel = () => controller.abort()
    signal?.addEventListener('abort', cancel, { once: true })
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs)
    try {
      const response = await fetchRequest(`${file ? '/api/v1/catalog-files' : '/api/v1/catalogs/json'}?url=${encodeURIComponent(target)}`, {
        method: 'GET', credentials: 'omit', redirect: 'error', mode: 'same-origin', cache: 'no-store', signal: controller.signal,
        headers: { Authorization: authorization, Accept: file ? 'application/octet-stream' : 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
      })
      if (response.redirected) throw new ApiError('network', '서버 주소가 변경되어 요청을 중단했습니다.')
      if (!response.ok) {
        if (response.status === 401) throw new ApiError('authentication', '계정 이름 또는 비밀번호를 확인해 주세요.', response.status)
        if (response.status === 400) throw new ApiError('invalid-request', '공개 HTTPS 카탈로그 또는 파일 주소를 입력해 주세요.', response.status)
        throw new ApiError('server', '카탈로그나 파일을 가져오지 못했습니다. 주소와 원격 서버 상태를 확인해 주세요.', response.status)
      }
      if (!response.headers.get('content-type')?.toLowerCase().startsWith(file ? 'application/octet-stream' : 'application/json')) throw new ApiError('invalid-response', '카탈로그 응답의 형식을 확인할 수 없습니다.')
      return await boundedBytes(response, file ? MAX_LOCAL_FILE_BYTES : 5 * 1024 * 1024, controller.signal)
    } catch (error) {
      if (controller.signal.aborted) throw new ApiError(timedOut ? 'timeout' : 'aborted', timedOut ? '응답 시간이 초과되었습니다. 다시 시도해 주세요.' : '요청이 취소되었습니다.')
      if (error instanceof ApiError) throw error
      throw new ApiError('network', '서버에 연결할 수 없습니다. 연결 상태를 확인해 주세요.')
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', cancel) }
  }
  return {
    async catalog(url, signal) {
      const bytes = await request(url, false, signal)
      try { return validateJsonCatalog(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))) }
      catch (error) { if (error instanceof ApiError) throw error; throw new ApiError('invalid-response', '카탈로그 응답의 형식을 확인할 수 없습니다.') }
    },
    file: (url, signal) => request(url, true, signal),
  }
}
