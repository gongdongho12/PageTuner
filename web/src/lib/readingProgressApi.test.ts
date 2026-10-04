import { afterEach, describe, expect, it, vi } from 'vitest'
import { createReadingProgressClient, ReadingProgressError, validateReadingProgressView, type ReadingProgressClient, type ReadingProgressIdentity } from './readingProgressApi'

const identity: ReadingProgressIdentity = { kind: 'ORIGINAL', recordId: '11111111-1111-1111-1111-111111111111' }
const anchor = { paragraphId: 'p1', characterOffset: 2 }
const mutation = { expectedVersion: 0, mutationId: '22222222-2222-2222-2222-222222222222', anchor }
const initial = { ...identity, version: 0, anchor: null, updatedAt: null }
const accepted = { ...identity, version: 1, anchor, updatedAt: '2026-09-16T01:00:00Z' }
const json = (value: unknown, status = 200, headers = {}) => new Response(JSON.stringify(value), { status, headers: { 'content-type': status === 200 ? 'application/json' : 'application/problem+json', ...headers } })
const csrf = () => json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-only' })
const clients: ReadingProgressClient[] = []
function client(fetch: typeof globalThis.fetch) { const value = createReadingProgressClient({ username: 'alice', password: 'secret' }, { fetch }); clients.push(value); return value }
afterEach(() => { clients.splice(0).forEach(value => value.close()); vi.restoreAllMocks() })

describe('reading progress transport', () => {
  it('uses authenticated same-origin PUT with CSRF and projects only contract fields', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json(accepted))
    expect(await client(fetch).put(identity, { ...mutation, password: 'must-not-be-sent' } as typeof mutation)).toEqual(accepted)
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/csrf')
    const [url, init] = fetch.mock.calls[1]
    expect(url).toBe(`/api/v1/reading-progress/ORIGINAL/${identity.recordId}`)
    expect(init).toMatchObject({ method: 'PUT', mode: 'same-origin', credentials: 'same-origin', redirect: 'error', cache: 'no-store',
      headers: { Authorization: `Basic ${btoa('alice:secret')}`, 'X-CSRF-TOKEN': 'csrf-only' } })
    expect(JSON.parse(init!.body as string)).toEqual(mutation)
  })
  it('checks identity, safe versions, nonempty progress and UTF-16 anchor bounds', () => {
    expect(validateReadingProgressView(initial, identity)).toEqual(initial)
    for (const value of [
      { ...accepted, recordId: mutation.mutationId }, { ...accepted, version: Number.MAX_SAFE_INTEGER + 1 },
      { ...initial, anchor }, { ...accepted, anchor: null }, { ...accepted, updatedAt: 'yesterday' },
      { ...accepted, anchor: { ...anchor, characterOffset: -1 } }, { ...accepted, anchor: { ...anchor, paragraphId: 'a'.repeat(201) } },
      { ...accepted, anchor: { ...anchor, paragraphId: 'p\u0085' } },
    ]) expect(() => validateReadingProgressView(value, identity)).toThrow(ReadingProgressError)
  })
  it('returns only validated conflict data and fixed errors without raw server text', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'READING_PROGRESS_CONFLICT', current: accepted, detail: 'secret-server-stack' }, 409))
    await expect(client(fetch).put(identity, mutation)).rejects.toMatchObject({ code: 'conflict', current: accepted })
    const wrong = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'READING_PROGRESS_CONFLICT', current: { ...accepted, recordId: mutation.mutationId } }, 409))
    await expect(client(wrong).put(identity, mutation)).rejects.toMatchObject({ code: 'invalid-response' })
    const raw = vi.fn<typeof globalThis.fetch>().mockResolvedValue(json({ code: 'HACKED', detail: 'secret-server-stack' }, 500))
    await expect(client(raw).get(identity)).rejects.toMatchObject({ code: 'server' })
    await expect(client(raw).get(identity)).rejects.not.toThrow('secret-server-stack')
  })
  it('rejects oversized, redirected, non-JSON and mismatched acknowledgements', async () => {
    const oversized = vi.fn<typeof globalThis.fetch>().mockResolvedValue(new Response(' '.repeat(8193), { headers: { 'content-type': 'application/json' } }))
    await expect(client(oversized).get(identity)).rejects.toMatchObject({ code: 'invalid-response' })
    const redirected = json(initial); Object.defineProperty(redirected, 'redirected', { value: true })
    await expect(client(vi.fn<typeof globalThis.fetch>().mockResolvedValue(redirected)).get(identity)).rejects.toMatchObject({ code: 'invalid-response' })
    await expect(client(vi.fn<typeof globalThis.fetch>().mockResolvedValue(new Response('html'))).get(identity)).rejects.toMatchObject({ code: 'invalid-response' })
    const wrong = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ ...accepted, version: 2 }))
    await expect(client(wrong).put(identity, mutation)).rejects.toMatchObject({ code: 'invalid-response' })
  })
  it('allowlists mutation and rate errors and preserves Retry-After', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'READING_PROGRESS_LIMIT' }, 429, { 'retry-after': '60' }))
    await expect(client(fetch).put(identity, mutation)).rejects.toMatchObject({ code: 'limit', retryAfterSeconds: 60 })
    const reused = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'READING_PROGRESS_MUTATION_REUSED' }, 400))
    await expect(client(reused).put(identity, mutation)).rejects.toMatchObject({ code: 'mutation-reused' })
  })
  it('rejects invalid inputs without a request and ignores late responses after close', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(json(initial)), api = client(fetch)
    await expect(api.put(identity, { ...mutation, expectedVersion: Number.MAX_SAFE_INTEGER })).rejects.toMatchObject({ code: 'invalid-request' })
    expect(fetch).not.toHaveBeenCalled()
    let resolve!: (value: Response) => void
    fetch.mockImplementation(() => new Promise(done => { resolve = done }))
    const pending = api.get(identity); api.close(); resolve(json(initial))
    await expect(pending).rejects.toMatchObject({ code: 'aborted' })
    await expect(api.get(identity)).rejects.toMatchObject({ code: 'aborted' })
    expect((fetch.mock.calls[0][1]?.signal as AbortSignal).aborted).toBe(true)
  })
})
