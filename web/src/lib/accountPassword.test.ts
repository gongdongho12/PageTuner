import { describe, expect, it, vi } from 'vitest'
import { validatePasswordChange } from './accountPassword'
import { createAccountClient } from './accountApi'

const input = { currentPassword: '  old secret  ', newPassword: '  새 password  ' }
const csrf = () => new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' }), { headers: { 'Content-Type': 'application/json' } })
const credentials = { username: 'reader', password: input.currentPassword }
const errorResponse = (code: string, status: number) => new Response(JSON.stringify({ code, detail: input.newPassword }), { status, headers: { 'Content-Type': 'application/problem+json' } })

describe('account password change', () => {
  it('preserves whitespace, allows short legacy current passwords, and projects only contract fields', () => {
    expect(validatePasswordChange({ ...input, confirmation: input.newPassword } as typeof input, input.newPassword)).toEqual(input)
    expect(validatePasswordChange({ ...input, currentPassword: 'old' })).toEqual({ ...input, currentPassword: 'old' })
    expect(validatePasswordChange({ ...input, newPassword: '한'.repeat(24) }).newPassword).toHaveLength(24)
    expect(validatePasswordChange({ ...input, newPassword: '😀'.repeat(10) }).newPassword).toBe('😀'.repeat(10))
  })
  it('rejects policy violations, unchanged passwords, and confirmation mismatch before fetching', async () => {
    for (const newPassword of ['short', '한'.repeat(25), '😀'.repeat(5), 'a'.repeat(73), 'abcdefghij\n', input.currentPassword]) {
      const fetch = vi.fn()
      await expect(createAccountClient(credentials, { fetch }).changePassword({ ...input, newPassword })).rejects.toMatchObject({ kind: 'invalid-request' })
      expect(fetch).not.toHaveBeenCalled()
    }
    expect(() => validatePasswordChange(input, 'mismatch')).toThrow('일치하지 않습니다')
    for (const currentPassword of ['', 'a'.repeat(73), 'secret\u0085'])
      expect(() => validatePasswordChange({ ...input, currentPassword })).toThrow()
  })
  it('uses authenticated CSRF and exact POST body, accepts204 with no JSON, then can close old credentials', async () => {
    const transport = vi.fn(async (url: RequestInfo | URL, _options?: RequestInit) => String(url).endsWith('/csrf') ? csrf() : new Response(null, { status: 204 }))
    const client = createAccountClient(credentials, { fetch: transport })
    await expect(client.changePassword(input)).resolves.toBeUndefined()
    expect(transport.mock.calls.map(call => call[0])).toEqual(['/api/v1/csrf', '/api/v1/accounts/me/password'])
    expect(transport.mock.calls[1][1]).toMatchObject({ method: 'POST', credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store', body: JSON.stringify(input),
      headers: { Authorization: `Basic ${btoa('reader:' + input.currentPassword)}`, 'X-CSRF-TOKEN': 'csrf-token' } })
    client.close()
    await expect(client.me()).rejects.toMatchObject({ kind: 'aborted' })
    expect(transport).toHaveBeenCalledTimes(2)
  })
  it('maps only bounded allowlisted errors without retaining server secrets or registration conflict copy', async () => {
    for (const [code, status, message] of [
      ['CURRENT_PASSWORD_INCORRECT', 400, '현재 비밀번호가 일치하지 않습니다.'],
      ['PASSWORD_UNCHANGED', 400, '현재 비밀번호와 다른 새 비밀번호를 입력해 주세요.'],
      ['PASSWORD_CHANGE_LIMIT', 429, '비밀번호 변경 요청이 많습니다. 잠시 후 다시 시도해 주세요.'],
      ['PASSWORD_CHANGE_CONFLICT', 409, '다른 요청에서 비밀번호가 변경되었습니다. 다시 로그인해 주세요.'],
      ['untrusted-secret', 400, '비밀번호 변경 결과를 확인하지 못했습니다. 새 비밀번호로 로그인을 확인해 주세요.'],
    ] as const) {
      const transport = vi.fn(async (url: RequestInfo | URL) => String(url).endsWith('/csrf') ? csrf() : errorResponse(code, status))
      await expect(createAccountClient(credentials, { fetch: transport }).changePassword(input)).rejects.toMatchObject({ message, status })
      expect(transport).toHaveBeenCalledTimes(2)
    }
    const large = vi.fn(async (url: RequestInfo | URL) => String(url).endsWith('/csrf') ? csrf() : errorResponse('x'.repeat(5000), 500))
    await expect(createAccountClient(credentials, { fetch: large }).changePassword(input)).rejects.toMatchObject({ kind: 'server' })
  })
  it('keeps CSRF and authentication failures distinct and never sends a mutation after failed CSRF', async () => {
    for (const status of [401, 403]) {
      const transport = vi.fn(async () => errorResponse('untrusted', status))
      await expect(createAccountClient(credentials, { fetch: transport }).changePassword(input)).rejects.toMatchObject({ kind: status === 401 ? 'authentication' : 'forbidden' })
      expect(transport).toHaveBeenCalledTimes(1)
    }
  })
  it('does not publish a late mutation response or retry when the client closes or the response is uncertain', async () => {
    let deliver!: (response: Response) => void
    let sent!: () => void
    const ready = new Promise<void>(resolve => { sent = resolve })
    const transport = vi.fn(async (url: RequestInfo | URL) => {
      if (String(url).endsWith('/csrf')) return csrf()
      sent(); return new Promise<Response>(resolve => { deliver = resolve })
    })
    const client = createAccountClient(credentials, { fetch: transport }), pending = client.changePassword(input)
    await ready; client.close(); deliver(new Response(null, { status: 204 }))
    await expect(pending).rejects.toMatchObject({ kind: 'aborted' })
    expect(transport).toHaveBeenCalledTimes(2)
    const bad = vi.fn(async (url: RequestInfo | URL) => String(url).endsWith('/csrf') ? csrf() : new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    await expect(createAccountClient(credentials, { fetch: bad }).changePassword(input)).rejects.toMatchObject({ kind: 'invalid-response' })
    expect(bad).toHaveBeenCalledTimes(2)
  })
  it('distinguishes an uncertain POST timeout from explicit cancellation', async () => {
    vi.useFakeTimers()
    try {
      let sent!: () => void
      const ready = new Promise<void>(resolve => { sent = resolve })
      const transport = vi.fn(async (url: RequestInfo | URL, options?: RequestInit) => {
        if (String(url).endsWith('/csrf')) return csrf()
        sent()
        return new Promise<Response>((_resolve, reject) => options?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true }))
      })
      const pending = createAccountClient(credentials, { fetch: transport }).changePassword(input)
      const assertion = expect(pending).rejects.toMatchObject({ kind: 'timeout' })
      await ready; await vi.advanceTimersByTimeAsync(20_000); await assertion
      expect(transport).toHaveBeenCalledTimes(2)
    } finally { vi.useRealTimers() }
  })
})
