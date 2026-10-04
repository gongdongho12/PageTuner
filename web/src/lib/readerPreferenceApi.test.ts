import { afterEach, describe, expect, it, vi } from 'vitest'
import { createReaderPreferenceClient, ReaderPreferenceError, validateReaderPreferences, validateReaderPreferenceView, type ReaderPreferenceClient, type ReaderPreferenceMutation, type SharedReaderPreferences } from './readerPreferenceApi'

const preferences: SharedReaderPreferences = { fontSize: 20, lineHeightPercent: 160, pageMargin: 16, touchDirection: 'left-previous', listMode: 'paged' }
const mutation: ReaderPreferenceMutation = { expectedVersion: 0, mutationId: '11111111-1111-1111-1111-111111111111', preferences }
const view = { version: 1, preferences, updatedAt: '2026-09-30T12:00:00Z' }
const json = (value: unknown, status = 200, headers = {}) => new Response(JSON.stringify(value), { status, headers: { 'content-type': status === 200 ? 'application/json' : 'application/problem+json', ...headers } })
const csrf = () => json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
const clients: ReaderPreferenceClient[] = []
function client(fetch: typeof globalThis.fetch) { const api = createReaderPreferenceClient({ username: 'alice', password: 'secret' }, { fetch }); clients.push(api); return api }
afterEach(() => { clients.splice(0).forEach(api => api.close()); vi.restoreAllMocks() })

describe('reader preference transport', () => {
  it('uses bounded same-origin authenticated CSRF writes', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json(view))
    expect(await client(fetch).put(mutation)).toEqual(view)
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/csrf')
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/reader-preferences')
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'PUT', mode: 'same-origin', credentials: 'same-origin', redirect: 'error', cache: 'no-store', headers: { Authorization: `Basic ${btoa('alice:secret')}`, 'X-CSRF-TOKEN': 'csrf' } })
    expect(JSON.parse(fetch.mock.calls[1][1]?.body as string)).toEqual(mutation)
  })
  it('rejects extra fields, out of range numbers, absent preferences and invalid timestamps', () => {
    for (const p of [{ ...preferences, fontSize: 13 }, { ...preferences, fontSize: 36.5 }, { ...preferences, lineHeightPercent: 241 }, { ...preferences, pageMargin: -1 }, { ...preferences, touchDirection: 'up' }, { ...preferences, listMode: 'automatic' }, { ...preferences, password: 'secret' }]) expect(() => validateReaderPreferences(p)).toThrow(ReaderPreferenceError)
    for (const value of [{ ...view, version: 0 }, { ...view, preferences: null }, { ...view, updatedAt: '2026-02-30T00:00:00Z' }, { ...view, updatedAt: '2026-09-30T00:00:00+09:00' }, { ...view, password: 'secret' }, { ...view, version: Number.MAX_SAFE_INTEGER + 1 }]) expect(() => validateReaderPreferenceView(value)).toThrow(ReaderPreferenceError)
    expect(validateReaderPreferenceView({ version: 0, preferences: null, updatedAt: null })).toEqual({ version: 0, preferences: null, updatedAt: null })
  })
  it('rejects mismatching success acknowledgements and hostile CSRF headers', async () => {
    for (const value of [{ ...view, version: 2 }, { ...view, preferences: { ...preferences, fontSize: 24 } }]) {
      const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json(value))
      await expect(client(fetch).put(mutation)).rejects.toMatchObject({ code: 'invalid-response' })
    }
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ headerName: 'Authorization', token: 'steal' }))
    await expect(client(fetch).put(mutation)).rejects.toMatchObject({ code: 'invalid-response' }); expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('preserves conflicts and rate limits without raw server detail', async () => {
    const conflict = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'READER_PREFERENCES_CONFLICT', current: view }, 409))
    await expect(client(conflict).put(mutation)).rejects.toMatchObject({ code: 'conflict', current: view })
    const limited = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json({ code: 'READER_PREFERENCES_LIMIT', detail: 'private token' }, 429, { 'retry-after': '45' }))
    await expect(client(limited).get()).rejects.toMatchObject({ code: 'limit', retryAfterSeconds: 45 })
    const exhausted = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(json({ code: 'READER_PREFERENCES_EXHAUSTED', detail: 'private token' }, 409))
    await expect(client(exhausted).put(mutation)).rejects.toMatchObject({ code: 'exhausted', current: undefined })
  })
  it('classifies interrupted streams as retryable but malformed or oversized bodies as terminal', async () => {
    let reads = 0
    const broken = new Response(new ReadableStream<Uint8Array>({ pull(c) { if (reads++ === 0) c.enqueue(new TextEncoder().encode('{"version":')); else c.error(new TypeError('lost network')) } }), { headers: { 'content-type': 'application/json' } })
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(broken).mockResolvedValueOnce(json(view))
    const api = client(fetch); await expect(api.get()).rejects.toMatchObject({ code: 'network' }); expect(await api.get()).toEqual(view)
    for (const text of [' '.repeat(8193), '{bad json']) {
      const malformed = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(new Response(text, { headers: { 'content-type': 'application/json' } }))
      await expect(client(malformed).get()).rejects.toMatchObject({ code: 'invalid-response' })
    }
  })
  it('discards late responses after the account client closes', async () => {
    let finish!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(() => new Promise(resolve => { finish = resolve })), api = client(fetch)
    const pending = api.get(); api.close(); finish(json(view))
    await expect(pending).rejects.toMatchObject({ code: 'aborted' }); expect(fetch.mock.calls[0][1]?.signal?.aborted).toBe(true)
  })
})
