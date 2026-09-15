import { webcrypto } from 'node:crypto'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import fixture from '../../../contracts/fixtures/translation-v1/stored-response.json'
import { createTranslationClient } from './api'
import { kotlinTrim, sha256, validateTranslation, validateSummary } from './validation'

beforeAll(() => { vi.stubGlobal('crypto', webcrypto) })
afterEach(() => { vi.useRealTimers() })

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const copy = () => JSON.parse(JSON.stringify(fixture)) as typeof fixture
const summary = () => {
  const { paragraphs, created: _created, ...fields } = copy()
  return { ...fields, paragraphCount: paragraphs.length }
}
const page = () => ({ items: [summary()], page: 0, size: 12, totalItems: 1, totalPages: 1, hasNext: false })

describe('shared translation contract', () => {
  it('preserves optional display titles without changing translation identity or requiring legacy titles', async () => {
    const titles = { bookTitle: '사용자가 읽는 책 제목', chapterTitle: '첫 번째 회차' }
    const titled = await validateTranslation({ ...copy(), ...titles })
    expect(titled).toEqual({ ...fixture, ...titles })
    expect(titled.revision).toBe(fixture.revision)
    expect(await validateSummary({ ...summary(), ...titles })).toEqual({ ...summary(), ...titles })
    expect(await validateTranslation(copy())).not.toHaveProperty('bookTitle')
    await expect(validateTranslation({ ...copy(), bookTitle: null })).rejects.toMatchObject({ kind: 'invalid-response' })
  })
  it('reproduces all three Kotlin hashes from the shared fixture', async () => {
    expect(await validateTranslation(copy())).toEqual(fixture)
    expect(await sha256(fixture.paragraphs.map(p => `${p.paragraphId}:${p.text}`).join('\n'))).toBe(fixture.payloadHash)
  })

  it('uses Kotlin whitespace semantics for content identities', () => {
    expect(kotlinTrim('\u001c provider \u001f')).toBe('provider')
    expect(kotlinTrim('\ufeffprovider\ufeff')).toBe('\ufeffprovider\ufeff')
    expect(kotlinTrim('\u00a0provider\u3000')).toBe('provider')
  })

  it.each(['artifactId', 'revision', 'payloadHash', 'sourceRevision', 'targetLanguage', 'modelId', 'promptRevision', 'glossaryRevision'])(
    'rejects tampered %s', async field => {
      const changed: Record<string, unknown> = copy()
      changed[field] = ['artifactId', 'revision', 'payloadHash'].includes(field) ? 'f'.repeat(64) : 'changed'
      await expect(validateTranslation(changed)).rejects.toMatchObject({ kind: 'integrity' })
    },
  )

  it('rejects body tampering, partial paragraphs, wrong primitives and unexpected fields', async () => {
    const changed = copy()
    changed.paragraphs[0].text = 'tampered'
    await expect(validateTranslation(changed)).rejects.toMatchObject({ kind: 'integrity' })
    for (const value of [
      { ...fixture, paragraphs: [] },
      { ...fixture, paragraphs: [fixture.paragraphs[0], fixture.paragraphs[0]] },
      { ...fixture, created: 'true' },
      { ...fixture, Authorization: 'secret' },
    ]) await expect(validateTranslation(value)).rejects.toMatchObject({ kind: 'invalid-response' })
  })
})

describe('same-origin API client', () => {
  it('uses only explicit memory authentication and validates list/get', async () => {
    const transport = vi.fn(async (input: RequestInfo | URL, _options?: RequestInit) => json(String(input).includes('?') ? page() : fixture))
    const client = createTranslationClient({ username: 'reader', password: 'password' }, { fetch: transport })
    expect((await client.list()).items[0].paragraphCount).toBe(2)
    expect(await client.get(fixture.recordId)).toEqual(fixture)
    expect(transport.mock.calls[0][0]).toBe('/api/v1/translations?page=0&size=12')
    expect(transport.mock.calls[1][0]).toBe(`/api/v1/translations/${fixture.recordId}`)
    const options = transport.mock.calls[0][1]!
    expect(options).toMatchObject({ credentials: 'omit', redirect: 'error', cache: 'no-store', mode: 'same-origin' })
    expect(options.headers).toMatchObject({ Authorization: `Basic ${btoa('reader:password')}`, 'X-Requested-With': 'XMLHttpRequest' })
    expect(transport.mock.calls.map(call => String(call[0])).join()).not.toContain('password')
  })

  it.each([[401, 'authentication'], [403, 'forbidden'], [404, 'not-found'], [409, 'conflict'], [500, 'server']] as const)(
    'exposes HTTP %i without copying private server diagnostics', async (status, kind) => {
      const client = createTranslationClient({ username: 'u', password: 'p' }, { fetch: async () => json({ detail: 'private-secret' }, status) })
      const error = await client.get(fixture.recordId).catch(value => value)
      expect(error).toMatchObject({ kind, status })
      expect(error.message).not.toContain('private-secret')
    },
  )

  it('rejects mismatched record/page identity and inconsistent pagination', async () => {
    const responses = [
      { ...fixture, recordId: '00000000-0000-0000-0000-000000000001' },
      { ...page(), page: 1, items: [] },
      { ...page(), totalPages: 2 },
    ]
    const transport = vi.fn(async () => json(responses.shift()))
    const client = createTranslationClient({ username: 'u', password: 'p' }, { fetch: transport })
    await expect(client.get(fixture.recordId)).rejects.toMatchObject({ kind: 'invalid-response' })
    await expect(client.list()).rejects.toMatchObject({ kind: 'invalid-response' })
    await expect(client.list()).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it('rejects invalid requests before any network access', async () => {
    const transport = vi.fn()
    const client = createTranslationClient({ username: 'u', password: 'p' }, { fetch: transport })
    await expect(client.get('../account')).rejects.toMatchObject({ kind: 'invalid-request' })
    await expect(client.list(-1)).rejects.toMatchObject({ kind: 'invalid-request' })
    expect(transport).not.toHaveBeenCalled()
    expect(() => createTranslationClient({ username: 'name:bad', password: 'secret' })).toThrow()
  })

  it('rejects non-JSON and oversized responses', async () => {
    for (const response of [
      new Response('<html>Login</html>', { headers: { 'Content-Type': 'text/html' } }),
      new Response('not-json', { headers: { 'Content-Type': 'application/json' } }),
      new Response('{}', { headers: { 'Content-Type': 'application/json', 'Content-Length': String(5 * 1024 * 1024) } }),
    ]) {
      const client = createTranslationClient({ username: 'u', password: 'p' }, { fetch: async () => response })
      await expect(client.get(fixture.recordId)).rejects.toMatchObject({ kind: 'invalid-response' })
    }
  })

  it('aborts old requests and reports timeouts separately', async () => {
    vi.useFakeTimers()
    const transport = vi.fn((_input: RequestInfo | URL, options?: RequestInit): Promise<Response> => new Promise((_resolve, reject) => {
      options!.signal!.addEventListener('abort', () => reject(new DOMException('Abort', 'AbortError')), { once: true })
    }))
    const client = createTranslationClient({ username: 'u', password: 'p' }, { fetch: transport, timeoutMs: 20 })
    const controller = new AbortController()
    const cancelled = expect(client.get(fixture.recordId, controller.signal)).rejects.toMatchObject({ kind: 'aborted' })
    controller.abort()
    await cancelled
    const timedOut = expect(client.list()).rejects.toMatchObject({ kind: 'timeout' })
    await vi.advanceTimersByTimeAsync(21)
    await timedOut
    await expect(client.list(0, controller.signal)).rejects.toMatchObject({ kind: 'aborted' })
    expect(transport).toHaveBeenCalledTimes(2)
  })
})
