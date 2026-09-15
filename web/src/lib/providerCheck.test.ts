import { describe, expect, it, vi } from 'vitest'
import { createProviderCheckInput, validateProviderCheck, providerFailureMessage, type ProviderCheckInput } from './providerCheck'
import { createWorkflowClient } from './workflowApi'
import { catalogFailureMessage } from './catalogTranslation'

const input: ProviderCheckInput = { providerKind: 'DEEPSEEK', sourceLanguage: 'auto', targetLanguage: 'ko', model: 'deepseek-flash', apiKey: 'memory-key' }
const success = { status: 'SUCCESS', code: 'PROVIDER_CHECK_OK', message: '번역기 연결과 응답 형식을 확인했습니다.',
  providerKind: 'DEEPSEEK', sourceLanguage: 'auto', targetLanguage: 'ko', model: 'deepseek-flash' }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const csrf = () => json({ headerName: 'X-CSRF-TOKEN', token: 'session-token' })
const credentials = { username: 'reader', password: 'local-password' }

describe('provider connection check', () => {
  it('projects settings only and omits blank keys so the server may use its configured credential', () => {
    const projected = createProviderCheckInput({ ...input, apiKey: '  ', endpoint: ' https://api.deepseek.com/chat/completions/ ',
      chapterRecordId: 'private-chapter', paragraphs: ['private source'], glossary: ['private glossary'] } as ProviderCheckInput)
    expect(projected).toEqual({ providerKind: 'DEEPSEEK', sourceLanguage: 'auto', targetLanguage: 'ko', model: 'deepseek-flash',
      endpoint: 'https://api.deepseek.com/chat/completions' })
    expect(createProviderCheckInput({ ...input, providerKind: 'GOOGLE_CLOUD', endpoint: 'https://other.example', model: 'unused' }))
      .toEqual({ providerKind: 'GOOGLE_CLOUD', sourceLanguage: 'auto', targetLanguage: 'ko', apiKey: 'memory-key' })
  })

  it('rejects unsafe connection settings before sending credentials', () => {
    for (const patch of [
      { apiKey: 'key\nAuthorization: other' }, { apiKey: 'x'.repeat(4097) }, { model: 'x'.repeat(201) }, { model: 'bad\tmodel' },
      { endpoint: 'http://remote.example/chat' }, { endpoint: 'https://user:key@example.com/chat' },
      { endpoint: 'https://example.com/chat?key=secret' }, { endpoint: 'https://example.com/chat#fragment' },
      { targetLanguage: 'auto' }, { sourceLanguage: 'KO' }, { targetLanguage: '' },
    ]) expect(() => createProviderCheckInput({ ...input, ...patch })).toThrow()
    expect(createProviderCheckInput({ ...input, endpoint: 'http://127.0.0.1:9999/chat' }).endpoint).toBe('http://127.0.0.1:9999/chat')
  })

  it('rejects mismatched or contradictory success results and removes extra fields', () => {
    for (const patch of [{ providerKind: 'GOOGLE_CLOUD' }, { targetLanguage: 'ja' }, { sourceLanguage: 'zh' },
      { model: 'different-model' }, { status: 'FAILED' }, { code: 'TRANSLATION_FAILED' }, { message: '' }, { message: 'x'.repeat(501) }])
      expect(() => validateProviderCheck({ ...success, ...patch }, input)).toThrow()
    expect(validateProviderCheck({ ...success, apiKey: 'untrusted-secret', sourceText: 'not a result field' }, input)).toEqual(success)
    const failed = { ...success, status: 'FAILED', code: 'TRANSLATION_RATE_LIMITED' }
    expect(validateProviderCheck(failed, input).code).toBe('TRANSLATION_RATE_LIMITED')
  })

  it.each(['GOOGLE_CLOUD', 'GOOGLE_WEB_TRANSLATE_HTML', 'DEEPSEEK', 'OPENAI_COMPATIBLE_LLM'] as const)
    ('checks %s through the authenticated same-origin server endpoint', async providerKind => {
      const settings = { ...input, providerKind, model: undefined }
      const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ ...success, providerKind }))
      const client = createWorkflowClient(credentials, { fetch: transport })
      try {
        expect((await client.checkProvider(settings)).status).toBe('SUCCESS')
        expect(transport.mock.calls.map(call => call[0])).toEqual(['/api/v1/csrf', '/api/v1/translation-providers/check'])
        const request = transport.mock.calls[1][1]!
        expect(request).toMatchObject({ method: 'POST', credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store' })
        expect(new Headers(request.headers).get('X-CSRF-TOKEN')).toBe('session-token')
        expect(new Headers(request.headers).get('Authorization')).toMatch(/^Basic /)
        expect(JSON.parse(String(request.body))).toEqual(createProviderCheckInput(settings))
      } finally { client.close() }
    })

  it('snapshots a check before awaiting CSRF and never sends later changes to settings', async () => {
    let release!: (value: Response) => void
    const transport = vi.fn<typeof fetch>().mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
      .mockResolvedValueOnce(json(success))
    const client = createWorkflowClient(credentials, { fetch: transport })
    const settings = { ...input }
    try {
      const pending = client.checkProvider(settings)
      settings.apiKey = 'changed-secret'; settings.model = 'changed-model'
      release(csrf())
      await pending
      expect(JSON.parse(String(transport.mock.calls[1][1]?.body))).toMatchObject({ apiKey: 'memory-key', model: 'deepseek-flash' })
    } finally { client.close() }
  })

  it('maps only fixed server problem codes and never displays response details', async () => {
    for (const code of ['PROVIDER_NOT_CONFIGURED', 'ENDPOINT_NOT_ALLOWED', 'INVALID_PROVIDER']) {
      const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code, detail: 'secret-provider-response' }, 400))
      const client = createWorkflowClient(credentials, { fetch: transport })
      try { await expect(client.checkProvider(input)).rejects.toMatchObject({ status: 400, message: providerFailureMessage(code) }) }
      finally { client.close() }
    }
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'UNKNOWN_SECRET', detail: 'secret-response' }, 400))
    const client = createWorkflowClient(credentials, { fetch: transport })
    try { await expect(client.checkProvider(input)).rejects.toMatchObject({ message: '주소와 번역 설정을 확인해 주세요.' }) }
    finally { client.close() }
  })

  it('reports the check cooldown without automatic repeated provider calls', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({}, 429))
    const client = createWorkflowClient(credentials, { fetch: transport })
    try {
      await expect(client.checkProvider(input)).rejects.toMatchObject({ status: 429, message: providerFailureMessage('PROVIDER_CHECK_BUSY') })
      expect(transport).toHaveBeenCalledTimes(2)
    } finally { client.close() }
  })

  it('does not send a check when cancellation arrives while acquiring CSRF', async () => {
    const controller = new AbortController()
    const transport = vi.fn<typeof fetch>().mockImplementationOnce(async () => { controller.abort(); return csrf() })
    const client = createWorkflowClient(credentials, { fetch: transport })
    try {
      await expect(client.checkProvider(input, controller.signal)).rejects.toMatchObject({ kind: 'aborted' })
      expect(transport).toHaveBeenCalledTimes(1)
    } finally { client.close() }
  })

  it('uses actionable provider errors for catalog translation as well as connection checks', () => {
    for (const code of ['TRANSLATION_RATE_LIMITED', 'TRANSLATION_AUTHENTICATION_FAILED', 'TRANSLATION_QUOTA_EXCEEDED', 'TRANSLATION_INVALID_RESPONSE'])
      expect(catalogFailureMessage(code)).toBe(providerFailureMessage(code))
    expect(catalogFailureMessage('CATALOG_TIMEOUT')).toContain('시간이 초과')
  })
})
